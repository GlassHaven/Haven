package sh.haven.core.local.proot

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "GuestRecipeRepository"

/**
 * Persists user-imported community Guest App Packs and Guest Service Presets (#891)
 * alongside the compiled-in [GuestAppCatalog] and [GuestServiceCatalog].
 */
@Singleton
class GuestRecipeRepository internal constructor(
    private val storeFile: File,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.filesDir, "proot/imported-recipes.json"),
    )

    data class ImportSummary(
        val packIds: List<String>,
        val serviceIds: List<String>,
        val sourceRepo: String?,
    )

    private val lock = Any()

    private val _importedManifest = MutableStateFlow(loadInitial())
    val importedManifest: StateFlow<GuestRecipeManifest> = _importedManifest.asStateFlow()

    fun allPacks(): List<GuestAppPack> =
        GuestAppCatalog.PACKS + _importedManifest.value.packs

    fun allServicePresets(): List<GuestServicePreset> =
        GuestServiceCatalog.PRESETS + _importedManifest.value.services

    fun findPackById(id: String): GuestAppPack? =
        GuestAppCatalog.byId(id) ?: _importedManifest.value.packs.firstOrNull { it.id == id }

    fun findServicePresetById(id: String): GuestServicePreset? =
        GuestServiceCatalog.byId(id) ?: _importedManifest.value.services.firstOrNull { it.id == id }

    fun isImportedPack(id: String): Boolean =
        _importedManifest.value.packs.any { it.id == id }

    fun isImportedService(id: String): Boolean =
        _importedManifest.value.services.any { it.id == id }

    /**
     * Validate [jsonText] as a [GuestRecipeManifest], ensure no imported entry
     * shadows a built-in pack or service id, merge (upserting by id) into the
     * persisted store, and publish the updated [importedManifest].
     */
    fun importJson(jsonText: String): ImportSummary = synchronized(lock) {
        val incoming = GuestRecipeManifest.parse(jsonText)
        for (p in incoming.packs) {
            if (GuestAppCatalog.byId(p.id) != null) {
                throw RecipeValidationException(
                    "Imported pack id '${p.id}' conflicts with built-in GuestAppCatalog entry",
                )
            }
        }
        for (s in incoming.services) {
            if (GuestServiceCatalog.byId(s.id) != null) {
                throw RecipeValidationException(
                    "Imported service id '${s.id}' conflicts with built-in GuestServiceCatalog entry",
                )
            }
        }

        val cur = _importedManifest.value
        val incomingPackIds = incoming.packs.map { it.id }.toSet()
        val incomingServiceIds = incoming.services.map { it.id }.toSet()

        val mergedPacks = cur.packs.filterNot { it.id in incomingPackIds } + incoming.packs
        val mergedServices = cur.services.filterNot { it.id in incomingServiceIds } + incoming.services
        val updated = GuestRecipeManifest(
            schemaVersion = GuestRecipeManifest.CURRENT_SCHEMA_VERSION,
            sourceRepo = incoming.sourceRepo ?: cur.sourceRepo,
            packs = mergedPacks,
            services = mergedServices,
        )
        persistLocked(updated)
        _importedManifest.value = updated
        ImportSummary(
            packIds = incoming.packs.map { it.id },
            serviceIds = incoming.services.map { it.id },
            sourceRepo = updated.sourceRepo,
        )
    }

    /** Remove an imported pack or service by [id]. Built-in entries are unaffected. */
    fun removeImported(id: String): Boolean = synchronized(lock) {
        val cur = _importedManifest.value
        val nextPacks = cur.packs.filterNot { it.id == id }
        val nextServices = cur.services.filterNot { it.id == id }
        if (nextPacks.size == cur.packs.size && nextServices.size == cur.services.size) {
            return false
        }
        val updated = cur.copy(packs = nextPacks, services = nextServices)
        if (updated.packs.isEmpty() && updated.services.isEmpty()) {
            storeFile.delete()
        } else {
            persistLocked(updated)
        }
        _importedManifest.value = updated
        true
    }

    fun clearImported() = synchronized(lock) {
        storeFile.delete()
        _importedManifest.value = GuestRecipeManifest()
    }

    /**
     * Fetch a recipe manifest JSON document over HTTPS (`<= 1 MiB`), enforcing
     * HTTPS across redirects and verifying [expectedSha256] when provided.
     */
    fun fetchManifestJson(httpsUrl: String, expectedSha256: String? = null): String {
        val trimmed = httpsUrl.trim()
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw RecipeValidationException("Invalid recipe URL: ${e.message}")
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw RecipeValidationException("Recipe manifest URL must use https://")
        }
        var current = URL(trimmed)
        var redirects = 0
        while (true) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code in 301..308) {
                    if (++redirects > 5) {
                        throw RecipeValidationException("Too many redirects fetching recipe manifest")
                    }
                    val loc = conn.getHeaderField("Location")
                        ?: throw RecipeValidationException("Redirect missing Location header")
                    val next = URL(current, loc)
                    if (!next.protocol.equals("https", ignoreCase = true)) {
                        throw RecipeValidationException("Refusing non-HTTPS redirect for recipe manifest")
                    }
                    current = next
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw RecipeValidationException("HTTP $code fetching recipe manifest")
                }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (out.size() + n > MAX_MANIFEST_BYTES) {
                            throw RecipeValidationException(
                                "Recipe manifest exceeds ${MAX_MANIFEST_BYTES / 1024} KiB limit",
                            )
                        }
                        out.write(buf, 0, n)
                    }
                }
                return decodeAndVerifyManifestBytes(out.toByteArray(), expectedSha256)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun loadInitial(): GuestRecipeManifest {
        if (!storeFile.exists()) return GuestRecipeManifest()
        return try {
            GuestRecipeManifest.parse(storeFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable $storeFile", e)
            GuestRecipeManifest()
        }
    }

    private fun persistLocked(manifest: GuestRecipeManifest) {
        storeFile.parentFile?.mkdirs()
        val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
        tmp.writeText(GuestRecipeManifest.toJson(manifest), Charsets.UTF_8)
        if (!tmp.renameTo(storeFile)) {
            tmp.copyTo(storeFile, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 1024 * 1024 // 1 MiB

        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        internal fun decodeAndVerifyManifestBytes(
            bytes: ByteArray,
            expectedSha256: String?,
        ): String {
            if (bytes.size > MAX_MANIFEST_BYTES) {
                throw RecipeValidationException(
                    "Recipe manifest exceeds ${MAX_MANIFEST_BYTES / 1024} KiB limit",
                )
            }
            if (!expectedSha256.isNullOrBlank()) {
                val want = expectedSha256.trim().lowercase()
                if (!SHA256_HEX.matches(want)) {
                    throw RecipeValidationException("expectedSha256 must be 64 hex characters")
                }
                val got = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                if (got != want) {
                    throw RecipeValidationException(
                        "Recipe manifest SHA-256 mismatch (expected $want, got $got)",
                    )
                }
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }
}
