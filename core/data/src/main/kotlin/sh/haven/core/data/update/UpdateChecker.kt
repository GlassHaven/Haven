package sh.haven.core.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.haven.core.data.NativeFeatures
import sh.haven.core.data.preferences.UserPreferencesRepository
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Certificate of the key that signs the APKs attached to GitHub releases,
 * lower-case hex SHA-256 of the X.509 DER — the same digest
 * `apksigner verify --print-certs` prints. Read off the published
 * `haven-5.87.49-armv7-terminal-release.apk`, not just off the keystore,
 * so it is the digest a user's installed copy will actually carry.
 */
private const val GITHUB_RELEASE_SIGNER_SHA256 =
    "ea03a3a70e1c11d0a78932f959b21f20d8735d9cd750997657cb7f7d7c2b90b3"

/**
 * Certificate of F-Droid's per-app signing key for `sh.haven.app` (matches
 * README.md). An F-Droid install cannot install a GitHub APK over itself, so
 * Settings links straight to Haven's F-Droid page instead (#874).
 */
private const val FDROID_SIGNER_SHA256 =
    "ea05a89431961b8ac53c36725452673c2be1c2d7b6e48771617b974e6092b332"

private const val LATEST_RELEASE_API =
    "https://api.github.com/repos/GlassHaven/Haven/releases/latest"

/** Enough for the release JSON; a redirected or hostile reply cannot fill memory. */
private const val MAX_RESPONSE_BYTES = 512 * 1024

/** Hard upper bound for a Haven release APK download (512 MiB). */
private const val MAX_APK_BYTES = 512L * 1024 * 1024

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val APK_READ_TIMEOUT_MS = 60_000

private val TRUSTED_ASSET_PREFIXES = listOf(
    "https://github.com/GlassHaven/Haven/releases/download/",
    "https://github.com/GlassOnTin/Haven/releases/download/",
)

/**
 * Launch checks no oftener than this, however many times Haven is opened.
 *
 * #597: the trigger is every foreground (UpdateNotifier), so this is the only
 * thing keeping "check on launch" from making a request on every open. One
 * small GET per hour is a fraction of GitHub's unauthenticated 60/hour per-IP
 * limit, and the per-version dedup already stops repeat notifications, so the
 * interval only paces the query. 24h here meant an update released just after
 * a check could stay invisible for a full day on a phone that opens Haven
 * daily.
 */
internal const val LAUNCH_CHECK_INTERVAL_MS = 60L * 60 * 1000

private const val TAG = "UpdateChecker"

/**
 * Opt-in update check (#578).
 *
 * Haven ships under two signing keys. F-Droid does not distribute our APK; it
 * builds from source and signs with its own key, and Android will not replace
 * an installed package with one signed differently. So an update offer that
 * pointed everybody at the latest GitHub release would hand an F-Droid user a
 * download that cannot install — worse than offering nothing, because it reads
 * as a broken app rather than a mismatch of channels.
 *
 * The running copy therefore has to establish which channel it came from, and
 * it does that from its own signing certificate rather than from
 * `installerPackageName`, which any installer can set to anything. Only a copy
 * signed with [GITHUB_RELEASE_SIGNER_SHA256] is offered a GitHub release.
 * Everything else — F-Droid, a self-built debug APK, a re-signed copy — gets
 * [Channel.OTHER] and this class does nothing at all, makes no network request
 * and shows no UI.
 *
 * The check is also off by default and never runs unless
 * [UserPreferencesRepository.updateCheckEnabled] is on.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository,
) {

    /** Which distribution the running copy was signed for. */
    enum class Channel {
        /** Signed with the GitHub-release key; a release download will install. */
        GITHUB_RELEASE,

        /** Signed with F-Droid's per-app key; Settings links to F-Droid (#874). */
        FDROID,

        /** Self-built or re-signed copy. No update is offered and no request is made. */
        OTHER,
    }

    /** Which feature variant of Haven is running (`release.yml` APK suffix). */
    enum class Flavour {
        FULL,
        STORAGE,
        TERMINAL,
    }

    sealed interface DownloadResult {
        data class Ready(val apkFile: File, val sha256: String) : DownloadResult
        data class Failed(val message: String) : DownloadResult
    }

    sealed interface Result {
        /** Installed version is the newest published one. */
        data class UpToDate(val installedVersion: String) : Result

        data class Available(
            val version: String,
            val releaseUrl: String,
            val releaseNotes: String = "",
            val apkAssetName: String? = null,
            val apkDownloadUrl: String? = null,
            val sha256SumsUrl: String? = null,
        ) : Result

        /** Network, parse or API failure. [message] is fit to show the user. */
        data class Failed(val message: String) : Result

        /** Not a GitHub-release install; nothing was requested. */
        data object WrongChannel : Result
    }

    private val _latestResult = MutableStateFlow<Result?>(null)

    /**
     * Most recent [check] outcome observed in this process, whether triggered by
     * [checkOnLaunch], Settings -> About -> Check now, or MCP `check_for_update`.
     * Drives the quiet update indicator on Settings / About (#874).
     */
    val latestResult: StateFlow<Result?> = _latestResult.asStateFlow()

    /**
     * The channel of the running copy. Cheap, local and side-effect free —
     * Settings calls it on every composition to decide whether to offer the
     * toggle at all.
     */
    fun channel(): Channel {
        val digests = ownSignerSha256Digests()
        val resolved = resolveChannel(digests)
        if (resolved == Channel.GITHUB_RELEASE) {
            Log.i(TAG, "channel=GITHUB_RELEASE")
        } else {
            // Not an error: an F-Droid or self-built copy lands here by design.
            Log.i(TAG, "channel=$resolved (signers=$digests)")
        }
        return resolved
    }

    /** The running app's own version name, e.g. `5.87.50`. */
    fun installedVersion(): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""

    /** Detect the running build's feature flavour from its packaged native libraries. */
    fun installedFlavour(): Flavour {
        val native = NativeFeatures(context)
        return resolveFlavour(anyDesktop = native.anyDesktop, rclone = native.rclone)
    }

    /** Primary release ABI tag (`arm64`, `armv7`, `x64`), or null if unsupported. */
    fun primaryAbiTag(): String? = resolveAbiTag(Build.SUPPORTED_ABIS.orEmpty().toList())

    /**
     * Ask GitHub for the newest release and compare. Runs regardless of the
     * opt-in preference — this is the "check now" path, where the user asked
     * for it in the moment. Still refuses on the wrong channel.
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        if (channel() != Channel.GITHUB_RELEASE) {
            return@withContext Result.WrongChannel.also { _latestResult.value = it }
        }

        val installed = installedVersion()
        val abiTag = primaryAbiTag()
        val flavour = installedFlavour()
        val body = runCatching { fetchLatestRelease() }.getOrElse { e ->
            Log.w(TAG, "release query failed", e)
            return@withContext Result.Failed(e.message ?: e.javaClass.simpleName)
                .also { _latestResult.value = it }
        }

        parseReleaseJson(body, installed, abiTag = abiTag, flavour = flavour).also { result ->
            when (result) {
                is Result.Available -> Log.i(TAG, "update available: $installed -> ${result.version}")
                is Result.UpToDate -> Log.i(TAG, "up to date at $installed")
                else -> Unit
            }
            _latestResult.value = result
        }
    }

    /**
     * Download [available]'s matching APK into `cacheDir/updates/`, verifying
     * both `SHA256SUMS` and the X.509 signing certificate before returning
     * [DownloadResult.Ready] (#874 Slice 2).
     */
    suspend fun downloadVerifiedApk(
        available: Result.Available,
        onProgress: (Int?) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (channel() != Channel.GITHUB_RELEASE) {
            return@withContext DownloadResult.Failed("Self-update is only supported on GitHub Release installs")
        }
        val assetName = available.apkAssetName
            ?: return@withContext DownloadResult.Failed("No matching APK asset for this device")
        val apkUrl = available.apkDownloadUrl
            ?: return@withContext DownloadResult.Failed("No matching APK download URL for this device")
        val sumsUrl = available.sha256SumsUrl
            ?: return@withContext DownloadResult.Failed("Release is missing SHA256SUMS")
        if (!isTrustedReleaseAssetUrl(apkUrl) || !isTrustedReleaseAssetUrl(sumsUrl)) {
            return@withContext DownloadResult.Failed("Untrusted release download URL")
        }

        onProgress(null)
        val sumsManifest = runCatching { fetchSmallText(sumsUrl) }.getOrElse { e ->
            Log.w(TAG, "SHA256SUMS fetch failed", e)
            return@withContext DownloadResult.Failed(e.message ?: "Failed to download SHA256SUMS")
        }
        val expectedSha256 = parseSha256Sums(sumsManifest, assetName)
            ?: return@withContext DownloadResult.Failed("No SHA256SUMS entry found for $assetName")

        val updatesDir = File(context.cacheDir, "updates")
        val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = APK_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Haven/${installedVersion()}")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext DownloadResult.Failed("APK download returned HTTP $code")
            }
            if (!conn.url.protocol.equals("https", ignoreCase = true)) {
                return@withContext DownloadResult.Failed("Refusing non-HTTPS redirect for APK download")
            }
            val expectedLength = conn.contentLengthLong
            if (expectedLength > MAX_APK_BYTES) {
                return@withContext DownloadResult.Failed("APK exceeds size limit ($expectedLength bytes)")
            }
            conn.inputStream.use { input ->
                stageAndVerifyApk(
                    updatesDir = updatesDir,
                    assetName = assetName,
                    expectedSha256 = expectedSha256,
                    expectedLength = expectedLength,
                    input = input,
                    signerDigestsOf = ::archiveSignerSha256Digests,
                    onProgress = onProgress,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "APK download failed", e)
            DownloadResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The launch-time path. Returns null — having done nothing — unless the
     * preference is on, the channel is right, the throttle has expired and the
     * newer version is one the user has not already been told about. A non-null
     * [Result.Available] is the caller's cue to notify.
     */
    suspend fun checkOnLaunch(nowMs: Long): Result.Available? {
        val enabled = preferences.updateCheckEnabled.first()
        val lastRun = preferences.updateCheckLastRunMs.first()
        shouldQuery(enabled, { channel() }, lastRun, nowMs)?.let { skip ->
            Log.i(TAG, "launch check skipped: $skip")
            return null
        }
        preferences.setUpdateCheckLastRunMs(nowMs)

        val result = check()
        val lastNotified = preferences.updateCheckLastNotifiedVersion.first()
        shouldNotify(result, lastNotified)?.let { skip ->
            Log.i(TAG, "launch check found nothing to say: $skip")
            return null
        }
        val available = result as Result.Available
        preferences.setUpdateCheckLastNotifiedVersion(available.version)
        return available
    }

    private fun fetchLatestRelease(): String = fetchSmallText(
        url = LATEST_RELEASE_API,
        accept = "application/vnd.github+json",
        apiVersion = "2022-11-28",
    )

    private fun fetchSmallText(
        url: String,
        accept: String? = null,
        apiVersion: String? = null,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (accept != null) setRequestProperty("Accept", accept)
            if (apiVersion != null) setRequestProperty("X-GitHub-Api-Version", apiVersion)
            // GitHub rejects requests with no User-Agent.
            setRequestProperty("User-Agent", "Haven/${installedVersion()}")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 403 here is nearly always the unauthenticated rate limit.
                throw IllegalStateException("GitHub returned HTTP $code")
            }
            if (!conn.url.protocol.equals("https", ignoreCase = true)) {
                throw IllegalStateException("Refusing non-HTTPS redirect")
            }
            return conn.inputStream.use { input ->
                val buf = ByteArray(MAX_RESPONSE_BYTES)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Lower-case hex SHA-256 of every certificate on an uninstalled `.apk` archive. */
    private fun archiveSignerSha256Digests(apkFile: File): List<String> {
        val signatures: Array<Signature> = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                info?.signingInfo?.let { it.apkContentsSigners ?: it.signingCertificateHistory }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNATURES,
                )?.signatures
            }
        }.getOrNull() ?: return emptyList()

        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            sha.reset()
            sha.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    /** Lower-case hex SHA-256 of every certificate the installed package carries. */
    private fun ownSignerSha256Digests(): List<String> {
        val signatures: Array<Signature> = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES,
                ).signatures
            }
        }.getOrNull() ?: return emptyList()

        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            sha.reset()
            sha.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Why a launch check stayed quiet. Named rather than a bare null so a test
     * can assert WHICH gate stopped it — "no notification appeared" on its own
     * is compatible with the preference being off, the wrong signing key, the
     * throttle, a network failure and an outright bug, which makes it nearly
     * worthless as evidence.
     */
    internal enum class LaunchSkip {
        DISABLED,
        WRONG_CHANNEL,
        THROTTLED,
        NOT_NEWER,
        ALREADY_NOTIFIED,
    }

    internal companion object {
        fun resolveChannel(signerSha256Digests: List<String>): Channel = when {
            signerSha256Digests.any { it.equals(GITHUB_RELEASE_SIGNER_SHA256, ignoreCase = true) } ->
                Channel.GITHUB_RELEASE
            signerSha256Digests.any { it.equals(FDROID_SIGNER_SHA256, ignoreCase = true) } ->
                Channel.FDROID
            else -> Channel.OTHER
        }

        fun resolveFlavour(anyDesktop: Boolean, rclone: Boolean): Flavour = when {
            anyDesktop -> Flavour.FULL
            rclone -> Flavour.STORAGE
            else -> Flavour.TERMINAL
        }

        fun resolveAbiTag(supportedAbis: List<String>): String? {
            for (abi in supportedAbis) {
                when (abi) {
                    "arm64-v8a" -> return "arm64"
                    "x86_64" -> return "x64"
                    "armeabi-v7a", "armeabi" -> return "armv7"
                }
            }
            return null
        }

        fun expectedApkAssetName(version: String, abiTag: String, flavour: Flavour): String =
            when (flavour) {
                Flavour.FULL -> "haven-$version-$abiTag-release.apk"
                Flavour.STORAGE -> "haven-$version-$abiTag-storage-release.apk"
                Flavour.TERMINAL -> "haven-$version-$abiTag-terminal-release.apk"
            }

        fun isTrustedReleaseAssetUrl(url: String): Boolean =
            TRUSTED_ASSET_PREFIXES.any { url.startsWith(it) }

        fun parseSha256Sums(manifest: String, assetName: String): String? {
            for (rawLine in manifest.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val spaceIdx = line.indexOf(' ')
                if (spaceIdx != 64) continue
                val hex = line.substring(0, 64)
                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) continue
                val target = line.substring(64).trimStart().removePrefix("*")
                if (target == assetName) return hex.lowercase()
            }
            return null
        }

        fun stageAndVerifyApk(
            updatesDir: File,
            assetName: String,
            expectedSha256: String,
            expectedLength: Long,
            input: InputStream,
            signerDigestsOf: (File) -> List<String>,
            onProgress: (Int?) -> Unit = {},
        ): DownloadResult {
            if ('/' in assetName || '\\' in assetName || assetName.contains("..")) {
                return DownloadResult.Failed("Invalid asset filename")
            }
            updatesDir.mkdirs()
            val partFile = File(updatesDir, "$assetName.part")
            val finalFile = File(updatesDir, assetName)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var totalRead = 0L
                var lastPct = -1
                partFile.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        totalRead += n
                        if (totalRead > MAX_APK_BYTES) {
                            partFile.delete()
                            return DownloadResult.Failed("APK exceeds size limit")
                        }
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        if (expectedLength > 0L) {
                            val pct = ((totalRead * 100L) / expectedLength).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
                if (expectedLength > 0L && totalRead != expectedLength) {
                    partFile.delete()
                    return DownloadResult.Failed(
                        "Download truncated ($totalRead of $expectedLength bytes)",
                    )
                }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    partFile.delete()
                    return DownloadResult.Failed("SHA-256 mismatch for $assetName")
                }
                val signers = signerDigestsOf(partFile)
                if (signers.none { it.equals(GITHUB_RELEASE_SIGNER_SHA256, ignoreCase = true) }) {
                    partFile.delete()
                    return DownloadResult.Failed(
                        "Downloaded APK is not signed with the Haven release key",
                    )
                }
                // Sweep older staged APKs so cacheDir does not accumulate previous updates.
                updatesDir.listFiles()?.forEach { existing ->
                    if (existing != partFile && existing != finalFile) {
                        existing.delete()
                    }
                }
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                return DownloadResult.Ready(finalFile, actualSha256)
            } catch (e: Exception) {
                partFile.delete()
                return DownloadResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }

        fun parseReleaseJson(
            body: String,
            installed: String,
            abiTag: String? = null,
            flavour: Flavour = Flavour.FULL,
        ): Result {
            val json = runCatching { JSONObject(body) }.getOrElse {
                return Result.Failed("Unreadable reply from GitHub")
            }
            val tag = json.optString("tag_name").ifBlank {
                return Result.Failed("Release carried no tag")
            }
            val url = json.optString("html_url").ifBlank {
                "https://github.com/GlassHaven/Haven/releases/tag/$tag"
            }
            val notes = if (json.isNull("body")) "" else json.optString("body").trim()
            val latest = normaliseVersion(tag)

            if (!isNewer(latest, installed)) {
                return Result.UpToDate(installed)
            }

            var matchedApkName: String? = null
            var matchedApkUrl: String? = null
            var sumsUrl: String? = null
            val targetApkName = abiTag?.let { expectedApkAssetName(latest, it, flavour) }
            val assets = json.optJSONArray("assets")
            if (assets != null && targetApkName != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val dlUrl = asset.optString("browser_download_url")
                    if (!isTrustedReleaseAssetUrl(dlUrl)) continue
                    if (name == targetApkName) {
                        matchedApkName = name
                        matchedApkUrl = dlUrl
                    } else if (name == "SHA256SUMS") {
                        sumsUrl = dlUrl
                    }
                }
            }
            val hasVerifiedPair = matchedApkName != null && matchedApkUrl != null && sumsUrl != null
            return Result.Available(
                version = latest,
                releaseUrl = url,
                releaseNotes = notes,
                apkAssetName = if (hasVerifiedPair) matchedApkName else null,
                apkDownloadUrl = if (hasVerifiedPair) matchedApkUrl else null,
                sha256SumsUrl = if (hasVerifiedPair) sumsUrl else null,
            )
        }

        /**
         * Gate one: may we make the request at all? Returns null to proceed.
         *
         * [channelOf] is a lambda rather than a value because the order matters:
         * a disabled check must not read the signing certificate, and neither
         * case may touch the network. The throttle is evaluated last, so a
         * disabled or wrongly-signed copy is never even throttle-tested.
         */
        fun shouldQuery(
            enabled: Boolean,
            channelOf: () -> Channel,
            lastRunMs: Long,
            nowMs: Long,
        ): LaunchSkip? {
            if (!enabled) return LaunchSkip.DISABLED
            if (channelOf() != Channel.GITHUB_RELEASE) return LaunchSkip.WRONG_CHANNEL
            if (lastRunMs == 0L) return null // never run — always allowed
            val elapsed = nowMs - lastRunMs
            // A clock moved backwards would otherwise wedge the throttle shut
            // until it caught up, so only a forward-and-recent gap throttles.
            if (elapsed in 0 until LAUNCH_CHECK_INTERVAL_MS) return LaunchSkip.THROTTLED
            return null
        }

        /**
         * Gate two: having asked, is there anything to tell the user? Returns
         * null to notify. [LaunchSkip.ALREADY_NOTIFIED] is the one that stops a
         * user who chose not to update being told again on every launch.
         */
        fun shouldNotify(result: Result, lastNotifiedVersion: String): LaunchSkip? {
            if (result !is Result.Available) return LaunchSkip.NOT_NEWER
            if (lastNotifiedVersion == result.version) return LaunchSkip.ALREADY_NOTIFIED
            return null
        }

        /** `v5.87.50` and `5.87.50` are the same version. */
        fun normaliseVersion(tag: String): String = tag.trim().removePrefix("v")

        /**
         * Numeric component-wise comparison. A component that is not a number
         * compares as -1, so `5.87.50` beats `5.87.50-rc1` and neither beats a
         * version it cannot be ordered against. Returns false when equal, so a
         * caller only ever offers a strictly newer build.
         */
        fun isNewer(candidate: String, installed: String): Boolean {
            if (candidate.isBlank() || installed.isBlank()) return false
            val a = components(candidate)
            val b = components(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun components(version: String): List<Int> =
            normaliseVersion(version).split('.').map { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull() ?: -1
            }
    }
}
