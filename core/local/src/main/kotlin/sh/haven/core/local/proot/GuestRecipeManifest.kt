package sh.haven.core.local.proot

import java.io.File
import java.net.URI

/**
 * Importable JSON recipe manifest for standalone community Guest App Packs and
 * Guest Service Presets (#891).
 *
 * Uses a self-contained RFC 8259 parser/serializer so validation behaves
 * identically on Android and in plain-JVM unit tests without relying on
 * `android.jar`'s stubbed `org.json`.
 */
class RecipeValidationException(message: String) : IllegalArgumentException(message)

internal fun Arch.unamePatterns(): List<String> = when (this) {
    Arch.AARCH64 -> listOf("aarch64", "arm64")
    Arch.X86_64 -> listOf("x86_64", "amd64")
    Arch.ARM -> listOf("armv7l", "armv8l")
}

internal fun appendGuestArchGuard(sb: StringBuilder, supportedArches: Set<Arch>) {
    val allArches = Arch.entries.toSet()
    if (supportedArches.isEmpty() || supportedArches == allArches) return
    val patterns = Arch.entries
        .filter { it in supportedArches }
        .flatMap { it.unamePatterns() }
        .joinToString("|")
    sb.append("case \"\$(uname -m)\" in\n")
    sb.append("  ").append(patterns).append(") ;;\n")
    sb.append("  *) echo \"Unsupported guest architecture: \$(uname -m)\" >&2; exit 1 ;;\n")
    sb.append("esac\n")
}

internal fun appendGuestDistroGuard(sb: StringBuilder, distroRules: List<DistroRule>) {
    if (distroRules.isEmpty()) return
    sb.append("HAVEN_OS_FILE=\"\${HAVEN_OS_RELEASE:-/etc/os-release}\"\n")
    sb.append("if [ ! -r \"\$HAVEN_OS_FILE\" ]; then\n")
    sb.append("  echo \"Missing \$HAVEN_OS_FILE; cannot verify guest distro\" >&2\n")
    sb.append("  exit 1\n")
    sb.append("fi\n")
    sb.append("HAVEN_OS_ID=\$(sed -n 's/^ID=//p' \"\$HAVEN_OS_FILE\" | head -n 1 | tr -d '\\042\\047')\n")
    sb.append("HAVEN_OS_VER=\$(sed -n 's/^VERSION_ID=//p' \"\$HAVEN_OS_FILE\" | head -n 1 | tr -d '\\042\\047')\n")
    if (distroRules.any { it.minVersion != null }) {
        sb.append("haven_ver_ge() {\n")
        sb.append("  [ -n \"\$1\" ] && awk -v v=\"\$1\" -v m=\"\$2\" 'BEGIN {\n")
        sb.append("    na = split(v, a, \".\"); nb = split(m, b, \".\");\n")
        sb.append("    n = (na > nb ? na : nb);\n")
        sb.append("    for (i = 1; i <= n; i++) {\n")
        sb.append("      va = (i <= na ? a[i] + 0 : 0);\n")
        sb.append("      vb = (i <= nb ? b[i] + 0 : 0);\n")
        sb.append("      if (va > vb) exit 0;\n")
        sb.append("      if (va < vb) exit 1;\n")
        sb.append("    }\n")
        sb.append("    exit 0;\n")
        sb.append("  }'\n")
        sb.append("}\n")
    }
    sb.append("HAVEN_DISTRO_MATCH=0\n")
    sb.append("HAVEN_EXTRA_PKGS=''\n")
    for (rule in distroRules) {
        sb.append("if [ \"\$HAVEN_DISTRO_MATCH\" = \"0\" ] && [ \"\$HAVEN_OS_ID\" = '${rule.osId}' ]")
        if (rule.minVersion != null) {
            sb.append(" && haven_ver_ge \"\$HAVEN_OS_VER\" '${rule.minVersion}'")
        }
        sb.append("; then\n")
        sb.append("  HAVEN_DISTRO_MATCH=1\n")
        if (rule.extraPackages.isNotEmpty()) {
            sb.append("  HAVEN_EXTRA_PKGS='${rule.extraPackages.joinToString(" ")}'\n")
        }
        sb.append("fi\n")
    }
    val requiredDesc = distroRules.joinToString(", ") { it.summaryLabel() }
    sb.append("if [ \"\$HAVEN_DISTRO_MATCH\" != \"1\" ]; then\n")
    sb.append(
        "  echo \"Unsupported guest distro/version: \${HAVEN_OS_ID:-unknown} \${HAVEN_OS_VER:-unversioned} " +
            "(requires: $requiredDesc)\" >&2\n",
    )
    sb.append("  exit 1\n")
    sb.append("fi\n")
}

/**
 * Evaluate [distroRules] on the Android host against an extracted [rootfsDir]'s
 * `etc/os-release` (when present) or Haven's built-in [DistroCatalog] IDs so UI
 * pickers and MCP `compatibleWithActive` hide incompatible recipes up front.
 * Unknown unextracted custom distros return `true` and defer to the runtime
 * `/etc/os-release` guard in [appendGuestDistroGuard].
 */
fun matchesGuestDistro(
    distroRules: List<DistroRule>,
    distroId: String,
    rootfsDir: File? = null,
): Boolean {
    if (distroRules.isEmpty()) return true
    val (osId, osVer) = readOsReleaseOrBuiltIn(distroId, rootfsDir) ?: return true
    return distroRules.any { rule ->
        rule.osId == osId &&
            (rule.minVersion == null || (osVer != null && compareDotVersions(osVer, rule.minVersion) >= 0))
    }
}

private fun readOsReleaseOrBuiltIn(distroId: String, rootfsDir: File?): Pair<String, String?>? {
    val osReleaseFile = rootfsDir?.let { File(it, "etc/os-release") }?.takeIf { it.isFile }
    if (osReleaseFile != null) {
        var osId: String? = null
        var osVer: String? = null
        osReleaseFile.forEachLine { raw ->
            val line = raw.trim()
            when {
                osId == null && line.startsWith("ID=") ->
                    osId = line.removePrefix("ID=").trim('"', '\'')
                osVer == null && line.startsWith("VERSION_ID=") ->
                    osVer = line.removePrefix("VERSION_ID=").trim('"', '\'').ifEmpty { null }
            }
        }
        val resolvedId = osId?.takeIf { it.isNotEmpty() }
        if (resolvedId != null) return resolvedId to osVer
    }
    val baseId = Arch.entries
        .firstOrNull { distroId.endsWith("-${it.slug}") }
        ?.let { distroId.removeSuffix("-${it.slug}") }
        ?: distroId
    return when (baseId) {
        "alpine-3.21" -> "alpine" to "3.21"
        "debian-bookworm" -> "debian" to "12"
        "debian-trixie" -> "debian" to "13"
        "ubuntu-noble" -> "ubuntu" to "24.04"
        "ubuntu-resolute" -> "ubuntu" to "26.04"
        "archlinux" -> "arch" to null
        "void" -> "void" to null
        else -> null
    }
}

private fun compareDotVersions(v: String, m: String): Int {
    val a = v.split('.')
    val b = m.split('.')
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val va = a.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        val vb = b.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        if (va != vb) return va.compareTo(vb)
    }
    return 0
}

data class GuestRecipeManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val sourceRepo: String? = null,
    val packs: List<GuestAppPack> = emptyList(),
    val services: List<GuestServicePreset> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        private val RECIPE_ID_REGEX = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        private val OS_ID_REGEX = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        private val DISTRO_VER_REGEX = Regex("^[0-9]+(\\.[0-9]+){0,3}$")
        private val PKG_TOKEN_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9+._-]{0,127}$")
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        private val RESOLUTION_REGEX = Regex("^\\d{3,5}x\\d{3,5}$")
        private val SUPPORTED_FAMILIES = setOf(
            PackageFamily.APK,
            PackageFamily.APT,
            PackageFamily.PACMAN,
            PackageFamily.XBPS,
        )

        fun builtIn(): GuestRecipeManifest = GuestRecipeManifest(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            packs = GuestAppCatalog.PACKS,
            services = GuestServiceCatalog.PRESETS,
        )

        fun parse(jsonText: String): GuestRecipeManifest {
            val root = StrictJsonParser(jsonText).parseDocument().asObject("root")
            val version = root.reqInt("schemaVersion", "root")
            if (version != CURRENT_SCHEMA_VERSION) {
                throw RecipeValidationException(
                    "Unsupported recipe schemaVersion $version (expected $CURRENT_SCHEMA_VERSION)",
                )
            }
            val sourceRepo = root.optNullableString("sourceRepo", "root")?.let { raw ->
                validateHttpsRepoUrl(raw, "root.sourceRepo")
            }
            val packsArr = root.optArray("packs", "root") ?: emptyList()
            val servicesArr = root.optArray("services", "root") ?: emptyList()

            val seenPackIds = HashSet<String>()
            val packs = packsArr.mapIndexed { idx, v ->
                val pack = parsePack(v.asObject("packs[$idx]"), "packs[$idx]")
                if (!seenPackIds.add(pack.id)) {
                    throw RecipeValidationException("Duplicate pack id '${pack.id}'")
                }
                pack
            }

            val seenServiceIds = HashSet<String>()
            val services = servicesArr.mapIndexed { idx, v ->
                val svc = parseService(v.asObject("services[$idx]"), "services[$idx]")
                if (!seenServiceIds.add(svc.id)) {
                    throw RecipeValidationException("Duplicate service id '${svc.id}'")
                }
                svc
            }

            return GuestRecipeManifest(
                schemaVersion = version,
                sourceRepo = sourceRepo,
                packs = packs,
                services = services,
            )
        }

        fun toJson(manifest: GuestRecipeManifest): String {
            val sb = StringBuilder()
            val w = JsonWriter(sb)
            w.obj {
                field("schemaVersion", manifest.schemaVersion)
                if (manifest.sourceRepo != null) {
                    field("sourceRepo", manifest.sourceRepo)
                }
                fieldArray("packs") {
                    for (pack in manifest.packs) {
                        elementObj { writePack(pack) }
                    }
                }
                fieldArray("services") {
                    for (svc in manifest.services) {
                        elementObj { writeService(svc) }
                    }
                }
            }
            sb.append('\n')
            return sb.toString()
        }

        /**
         * Human-readable preview of a [GuestAppPack] recipe for consent UI before
         * running custom or unpinned scripts inside the guest (#891).
         */
        fun previewPackInstall(
            pack: GuestAppPack,
            family: PackageFamily,
            includeAssets: Boolean = true,
        ): String {
            val pkgs = pack.packages[family]
                ?: throw RecipeValidationException("Pack '${pack.id}' does not declare packages for $family")
            val script = buildPackInstallScript(pack, pkgs, family, includeAssets)
            return buildString {
                append("# Recipe: ").append(pack.label).append(" (").append(pack.id).append(")\n")
                append("# Package family: ").append(family.name).append('\n')
                append("# Supported arches: ")
                append(Arch.entries.filter { it in pack.supportedArches }.joinToString(", ") { it.slug })
                append('\n')
                if (pack.distroRules.isNotEmpty()) {
                    append("# Supported distros: ")
                    append(pack.distroRules.joinToString(", ") { it.summaryLabel() })
                    append('\n')
                }
                append("# Registers app window: ").append(pack.appLabel).append(" -> ").append(pack.appCommand).append('\n')
                if (pack.needsAudioBridge) append("# Enables PulseAudio bridge: true\n")
                append('\n')
                append(script)
            }
        }

        /**
         * Human-readable preview of a [GuestServicePreset] recipe (install script +
         * supervised daemon command) before execution (#891).
         */
        fun previewServiceInstall(
            preset: GuestServicePreset,
            family: PackageFamily,
            includeAssets: Boolean = true,
        ): String {
            val pkgs = preset.packages[family]
                ?: throw RecipeValidationException("Service '${preset.id}' does not declare packages for $family")
            val script = buildServicePresetInstallScript(preset, pkgs, family, includeAssets)
            return buildString {
                append("# Service recipe: ").append(preset.label).append(" (").append(preset.id).append(")\n")
                append("# Package family: ").append(family.name).append('\n')
                append("# Supported arches: ")
                append(Arch.entries.filter { it in preset.supportedArches }.joinToString(", ") { it.slug })
                append('\n')
                if (preset.distroRules.isNotEmpty()) {
                    append("# Supported distros: ")
                    append(preset.distroRules.joinToString(", ") { it.summaryLabel() })
                    append('\n')
                }
                if (preset.port > 0) {
                    append("# Loopback endpoint: 127.0.0.1:").append(preset.port).append('\n')
                }
                append("\n# --- Install phase ---\n")
                append(script)
                append("\n# --- Supervised service command ---\n")
                append(preset.serviceCommand.trim()).append('\n')
            }
        }

        private fun parsePack(obj: JsonObj, ctx: String): GuestAppPack {
            val id = validateRecipeId(obj.reqString("id", ctx), "$ctx.id")
            val label = validateText(obj.reqString("label", ctx), "$ctx.label")
            val description = validateText(obj.reqString("description", ctx), "$ctx.description")
            val supportedArches = parseSupportedArches(obj.optArray("supportedArches", ctx), "$ctx.supportedArches")
            val distroRules = parseDistroRules(obj.optArray("distroRules", ctx), "$ctx.distroRules")
            val packages = parsePackagesMap(obj.reqObject("packages", ctx), "$ctx.packages")
            val verifyBinary = validateRootfsRelativePath(obj.reqString("verifyBinary", ctx), "$ctx.verifyBinary")
            val configWrites = parseConfigWrites(obj.optArray("configWrites", ctx), "$ctx.configWrites")
            val needsAudioBridge = obj.optBoolean("needsAudioBridge", ctx) ?: false
            val appLabel = validateText(obj.reqString("appLabel", ctx), "$ctx.appLabel")
            val appCommand = validateText(obj.reqString("appCommand", ctx), "$ctx.appCommand")
            val fullscreen = obj.optBoolean("fullscreen", ctx) ?: true
            val multiWindow = obj.optBoolean("multiWindow", ctx) ?: false
            val swayRules = (obj.optArray("swayRules", ctx) ?: emptyList()).mapIndexed { i, r ->
                val rule = r.asString("$ctx.swayRules[$i]").trim()
                if (rule.isEmpty() || rule.any { it == '\n' || it == '\r' || it == '\u0000' }) {
                    throw RecipeValidationException("$ctx.swayRules[$i] must be a single non-empty line")
                }
                rule
            }
            val resolution = obj.optNullableString("resolution", ctx)?.let { res ->
                val trimmed = res.trim()
                if (trimmed != "auto" && !RESOLUTION_REGEX.matches(trimmed)) {
                    throw RecipeValidationException("$ctx.resolution must be 'auto' or '<W>x<H>' (got '$res')")
                }
                trimmed
            }
            val scale = obj.optNullableDouble("scale", ctx)?.let { raw ->
                val f = raw.toFloat()
                if (!f.isFinite() || f < 0.25f || f > 4.0f) {
                    throw RecipeValidationException("$ctx.scale must be in 0.25..4.0 (got $raw)")
                }
                f
            }
            val assets = parseAssets(obj.optArray("assets", ctx), "$ctx.assets")
            val postInstallScript = obj.optNullableString("postInstallScript", ctx)?.let { s ->
                if (s.contains('\u0000')) throw RecipeValidationException("$ctx.postInstallScript contains NUL")
                s
            } ?: ""
            val verifiedFamilies = (obj.optArray("verifiedFamilies", ctx) ?: emptyList()).mapIndexed { i, v ->
                val fam = parseFamily(v.asString("$ctx.verifiedFamilies[$i]"), "$ctx.verifiedFamilies[$i]")
                if (fam !in packages.keys) {
                    throw RecipeValidationException(
                        "$ctx.verifiedFamilies[$i] ($fam) must be declared in $ctx.packages",
                    )
                }
                fam
            }.toCollection(LinkedHashSet())

            return GuestAppPack(
                id = id,
                label = label,
                description = description,
                packages = packages,
                verifyBinary = verifyBinary,
                configWrites = configWrites,
                needsAudioBridge = needsAudioBridge,
                appLabel = appLabel,
                appCommand = appCommand,
                fullscreen = fullscreen,
                multiWindow = multiWindow,
                swayRules = swayRules,
                resolution = resolution,
                scale = scale,
                assets = assets,
                postInstallScript = postInstallScript,
                verifiedFamilies = verifiedFamilies,
                supportedArches = supportedArches,
                distroRules = distroRules,
            )
        }

        private fun parseService(obj: JsonObj, ctx: String): GuestServicePreset {
            val id = validateRecipeId(obj.reqString("id", ctx), "$ctx.id")
            val label = validateText(obj.reqString("label", ctx), "$ctx.label")
            val description = validateText(obj.reqString("description", ctx), "$ctx.description")
            val port = obj.reqInt("port", ctx)
            if (port !in 0..65535) {
                throw RecipeValidationException("$ctx.port must be in 0..65535 (got $port)")
            }
            val supportedArches = parseSupportedArches(obj.optArray("supportedArches", ctx), "$ctx.supportedArches")
            val distroRules = parseDistroRules(obj.optArray("distroRules", ctx), "$ctx.distroRules")
            val packages = parsePackagesMap(obj.reqObject("packages", ctx), "$ctx.packages")
            val verifyArr = obj.reqArray("verifyBinaries", ctx)
            if (verifyArr.isEmpty()) {
                throw RecipeValidationException("$ctx.verifyBinaries must not be empty")
            }
            val verifyBinaries = verifyArr.mapIndexed { i, v ->
                validateRootfsRelativePath(v.asString("$ctx.verifyBinaries[$i]"), "$ctx.verifyBinaries[$i]")
            }
            val configWrites = parseConfigWrites(obj.optArray("configWrites", ctx), "$ctx.configWrites")
            val assets = parseAssets(obj.optArray("assets", ctx), "$ctx.assets")
            val postInstallScript = obj.optNullableString("postInstallScript", ctx)?.let { s ->
                if (s.contains('\u0000')) throw RecipeValidationException("$ctx.postInstallScript contains NUL")
                s
            } ?: ""
            val serviceCommand = validateText(obj.reqString("serviceCommand", ctx), "$ctx.serviceCommand")
            val autostart = obj.optBoolean("autostart", ctx) ?: false

            return GuestServicePreset(
                id = id,
                label = label,
                description = description,
                port = port,
                packages = packages,
                verifyBinaries = verifyBinaries,
                configWrites = configWrites,
                postInstallScript = postInstallScript,
                serviceCommand = serviceCommand,
                autostart = autostart,
                assets = assets,
                supportedArches = supportedArches,
                distroRules = distroRules,
            )
        }

        private fun parseSupportedArches(arr: List<JsonVal>?, ctx: String): Set<Arch> {
            if (arr == null) return Arch.entries.toSet()
            if (arr.isEmpty()) {
                throw RecipeValidationException("$ctx must not be empty when specified")
            }
            val out = LinkedHashSet<Arch>()
            arr.forEachIndexed { i, v ->
                val raw = v.asString("$ctx[$i]").trim()
                val arch = Arch.entries.firstOrNull {
                    it.slug.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true)
                } ?: throw RecipeValidationException("$ctx[$i]: unknown arch '$raw'")
                out.add(arch)
            }
            return out
        }

        private fun parseDistroRules(arr: List<JsonVal>?, ctx: String): List<DistroRule> {
            if (arr == null) return emptyList()
            if (arr.isEmpty()) {
                throw RecipeValidationException("$ctx must not be empty when specified")
            }
            return arr.mapIndexed { i, v ->
                val o = v.asObject("$ctx[$i]")
                val osId = o.reqString("osId", "$ctx[$i]").trim()
                if (!OS_ID_REGEX.matches(osId)) {
                    throw RecipeValidationException("$ctx[$i].osId must match ${OS_ID_REGEX.pattern} (got '$osId')")
                }
                val minVersion = o.optNullableString("minVersion", "$ctx[$i]")?.let { raw ->
                    val trimmed = raw.trim()
                    if (!DISTRO_VER_REGEX.matches(trimmed)) {
                        throw RecipeValidationException(
                            "$ctx[$i].minVersion must match ${DISTRO_VER_REGEX.pattern} (got '$raw')",
                        )
                    }
                    trimmed
                }
                val extraPackages = (o.optArray("extraPackages", "$ctx[$i]") ?: emptyList()).mapIndexed { j, p ->
                    val token = p.asString("$ctx[$i].extraPackages[$j]").trim()
                    if (!PKG_TOKEN_REGEX.matches(token)) {
                        throw RecipeValidationException(
                            "$ctx[$i].extraPackages[$j]: invalid package name '$token'",
                        )
                    }
                    token
                }
                DistroRule(osId = osId, minVersion = minVersion, extraPackages = extraPackages)
            }
        }

        private fun parsePackagesMap(obj: JsonObj, ctx: String): Map<PackageFamily, List<String>> {
            if (obj.entries.isEmpty()) {
                throw RecipeValidationException("$ctx must declare at least one package family")
            }
            val out = LinkedHashMap<PackageFamily, List<String>>()
            for ((rawFamily, value) in obj.entries) {
                val family = parseFamily(rawFamily, "$ctx.$rawFamily")
                val pkgArr = value.asArray("$ctx.$rawFamily")
                if (pkgArr.isEmpty()) {
                    throw RecipeValidationException("$ctx.$rawFamily package list must not be empty")
                }
                val pkgs = pkgArr.mapIndexed { i, item ->
                    val token = item.asString("$ctx.$rawFamily[$i]").trim()
                    if (!PKG_TOKEN_REGEX.matches(token)) {
                        throw RecipeValidationException(
                            "$ctx.$rawFamily[$i]: invalid package name '$token'",
                        )
                    }
                    token
                }
                out[family] = pkgs
            }
            return out
        }

        private fun parseFamily(raw: String, ctx: String): PackageFamily {
            val fam = PackageFamily.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw RecipeValidationException("$ctx: unknown package family '$raw'")
            if (fam !in SUPPORTED_FAMILIES) {
                throw RecipeValidationException("$ctx: package family '$fam' is not yet supported by recipes")
            }
            return fam
        }

        private fun parseConfigWrites(arr: List<JsonVal>?, ctx: String): List<PackConfigWrite> {
            if (arr == null) return emptyList()
            return arr.mapIndexed { i, v ->
                val o = v.asObject("$ctx[$i]")
                val path = validateAbsoluteGuestFilePath(o.reqString("path", "$ctx[$i]"), "$ctx[$i].path")
                val stanza = o.reqString("stanza", "$ctx[$i]")
                if (stanza.isBlank() || stanza.contains('\u0000') || stanza.lineSequence().first().isBlank()) {
                    throw RecipeValidationException(
                        "$ctx[$i].stanza must be non-blank and start with a non-blank guard line",
                    )
                }
                PackConfigWrite(path = path, stanza = stanza)
            }
        }

        private fun parseAssets(arr: List<JsonVal>?, ctx: String): List<PackAsset> {
            if (arr == null) return emptyList()
            return arr.mapIndexed { i, v ->
                val o = v.asObject("$ctx[$i]")
                val url = validateAssetUrl(o.reqString("url", "$ctx[$i]"), "$ctx[$i].url")
                val sha = o.reqString("sha256", "$ctx[$i]").trim().lowercase()
                if (!SHA256_REGEX.matches(sha)) {
                    throw RecipeValidationException("$ctx[$i].sha256 must be 64 hex characters")
                }
                val destDir = validateAbsoluteGuestDirPath(o.reqString("destDir", "$ctx[$i]"), "$ctx[$i].destDir")
                PackAsset(url = url, sha256 = sha, destDir = destDir)
            }
        }

        private fun validateRecipeId(raw: String, ctx: String): String {
            val s = raw.trim()
            if (!RECIPE_ID_REGEX.matches(s)) {
                throw RecipeValidationException("$ctx must match ${RECIPE_ID_REGEX.pattern} (got '$raw')")
            }
            return s
        }

        private fun validateText(raw: String, ctx: String): String {
            if (raw.isBlank() || raw.contains('\u0000')) {
                throw RecipeValidationException("$ctx must be non-blank and contain no NUL bytes")
            }
            return raw
        }

        private fun validateRootfsRelativePath(raw: String, ctx: String): String {
            if (raw.isBlank() || raw.startsWith('/') || raw.endsWith('/')) {
                throw RecipeValidationException("$ctx must be a rootfs-relative file path without leading/trailing '/'")
            }
            if (raw.any { it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }) {
                throw RecipeValidationException("$ctx contains forbidden path characters")
            }
            val segments = raw.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                throw RecipeValidationException("$ctx contains invalid or traversal path segment: '$raw'")
            }
            return raw
        }

        private fun validateAbsoluteGuestFilePath(raw: String, ctx: String): String {
            if (!raw.startsWith('/') || raw.length < 2 || raw.endsWith('/')) {
                throw RecipeValidationException("$ctx must be an absolute guest file path starting with '/'")
            }
            if (raw.any { it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }) {
                throw RecipeValidationException("$ctx contains forbidden path characters")
            }
            val segments = raw.substring(1).split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                throw RecipeValidationException("$ctx contains invalid or traversal path segment: '$raw'")
            }
            return raw
        }

        private fun validateAbsoluteGuestDirPath(raw: String, ctx: String): String {
            val trimmed = if (raw.length > 1 && raw.endsWith('/')) raw.dropLast(1) else raw
            if (!trimmed.startsWith('/') || trimmed.length < 2) {
                throw RecipeValidationException("$ctx must be a non-root absolute guest directory starting with '/'")
            }
            if (trimmed.any { it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }) {
                throw RecipeValidationException("$ctx contains forbidden path characters")
            }
            val segments = trimmed.substring(1).split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                throw RecipeValidationException("$ctx contains invalid or traversal path segment: '$raw'")
            }
            return trimmed
        }

        private fun validateHttpsRepoUrl(raw: String, ctx: String): String {
            val trimmed = raw.trim()
            val uri = try {
                URI(trimmed)
            } catch (e: Exception) {
                throw RecipeValidationException("$ctx is not a valid URI: ${e.message}")
            }
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
                throw RecipeValidationException("$ctx must be an https:// URL with a host")
            }
            return trimmed
        }

        private fun validateAssetUrl(raw: String, ctx: String): String {
            if (raw.any { it.isWhitespace() || it.code < 0x20 }) {
                throw RecipeValidationException("$ctx contains whitespace or control characters")
            }
            if ('?' in raw || '#' in raw) {
                throw RecipeValidationException("$ctx must not contain query or fragment parameters")
            }
            val uri = try {
                URI(raw)
            } catch (e: Exception) {
                throw RecipeValidationException("$ctx is not a valid URI: ${e.message}")
            }
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
                throw RecipeValidationException("$ctx must use https://")
            }
            val path = uri.rawPath ?: throw RecipeValidationException("$ctx missing path")
            val fileName = path.substringAfterLast('/')
            if (fileName.isEmpty() || fileName == "." || fileName == ".." || '\\' in fileName) {
                throw RecipeValidationException("$ctx must end with a valid file name")
            }
            return raw
        }

        private fun JsonWriter.ObjScope.writePack(pack: GuestAppPack) {
            field("id", pack.id)
            field("label", pack.label)
            field("description", pack.description)
            fieldArray("supportedArches") {
                for (arch in Arch.entries.filter { it in pack.supportedArches }) {
                    element(arch.slug)
                }
            }
            if (pack.distroRules.isNotEmpty()) {
                writeDistroRules(pack.distroRules)
            }
            fieldObj("packages") {
                for ((family, pkgs) in pack.packages) {
                    fieldArray(family.name) {
                        pkgs.forEach { element(it) }
                    }
                }
            }
            field("verifyBinary", pack.verifyBinary)
            if (pack.configWrites.isNotEmpty()) {
                fieldArray("configWrites") {
                    for (cw in pack.configWrites) {
                        elementObj {
                            field("path", cw.path)
                            field("stanza", cw.stanza)
                        }
                    }
                }
            }
            field("needsAudioBridge", pack.needsAudioBridge)
            field("appLabel", pack.appLabel)
            field("appCommand", pack.appCommand)
            field("fullscreen", pack.fullscreen)
            if (pack.multiWindow) {
                field("multiWindow", true)
            }
            if (pack.swayRules.isNotEmpty()) {
                fieldArray("swayRules") {
                    pack.swayRules.forEach { element(it) }
                }
            }
            if (pack.resolution != null) {
                field("resolution", pack.resolution)
            }
            if (pack.scale != null) {
                field("scale", pack.scale)
            }
            if (pack.assets.isNotEmpty()) {
                fieldArray("assets") {
                    for (a in pack.assets) {
                        elementObj {
                            field("url", a.url)
                            field("sha256", a.sha256)
                            field("destDir", a.destDir)
                        }
                    }
                }
            }
            if (pack.postInstallScript.isNotEmpty()) {
                field("postInstallScript", pack.postInstallScript)
            }
            if (pack.verifiedFamilies.isNotEmpty()) {
                fieldArray("verifiedFamilies") {
                    pack.verifiedFamilies.forEach { element(it.name) }
                }
            }
        }

        private fun JsonWriter.ObjScope.writeService(svc: GuestServicePreset) {
            field("id", svc.id)
            field("label", svc.label)
            field("description", svc.description)
            field("port", svc.port)
            fieldArray("supportedArches") {
                for (arch in Arch.entries.filter { it in svc.supportedArches }) {
                    element(arch.slug)
                }
            }
            if (svc.distroRules.isNotEmpty()) {
                writeDistroRules(svc.distroRules)
            }
            fieldObj("packages") {
                for ((family, pkgs) in svc.packages) {
                    fieldArray(family.name) {
                        pkgs.forEach { element(it) }
                    }
                }
            }
            fieldArray("verifyBinaries") {
                svc.verifyBinaries.forEach { element(it) }
            }
            if (svc.configWrites.isNotEmpty()) {
                fieldArray("configWrites") {
                    for (cw in svc.configWrites) {
                        elementObj {
                            field("path", cw.path)
                            field("stanza", cw.stanza)
                        }
                    }
                }
            }
            if (svc.assets.isNotEmpty()) {
                fieldArray("assets") {
                    for (a in svc.assets) {
                        elementObj {
                            field("url", a.url)
                            field("sha256", a.sha256)
                            field("destDir", a.destDir)
                        }
                    }
                }
            }
            if (svc.postInstallScript.isNotEmpty()) {
                field("postInstallScript", svc.postInstallScript)
            }
            field("serviceCommand", svc.serviceCommand)
            field("autostart", svc.autostart)
        }

        private fun JsonWriter.ObjScope.writeDistroRules(rules: List<DistroRule>) {
            fieldArray("distroRules") {
                for (rule in rules) {
                    elementObj {
                        field("osId", rule.osId)
                        if (rule.minVersion != null) {
                            field("minVersion", rule.minVersion)
                        }
                        if (rule.extraPackages.isNotEmpty()) {
                            fieldArray("extraPackages") {
                                rule.extraPackages.forEach { element(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface JsonVal {
    fun asObject(ctx: String): JsonObj =
        this as? JsonObj ?: throw RecipeValidationException("$ctx: expected JSON object")

    fun asArray(ctx: String): List<JsonVal> =
        (this as? JsonArr)?.items ?: throw RecipeValidationException("$ctx: expected JSON array")

    fun asString(ctx: String): String =
        (this as? JsonStr)?.value ?: throw RecipeValidationException("$ctx: expected JSON string")
}

private class JsonObj(val entries: Map<String, JsonVal>) : JsonVal {
    fun reqString(key: String, ctx: String): String =
        (entries[key] ?: throw RecipeValidationException("$ctx: missing '$key'")).asString("$ctx.$key")

    fun optNullableString(key: String, ctx: String): String? = when (val v = entries[key]) {
        null, JsonNull -> null
        is JsonStr -> v.value
        else -> throw RecipeValidationException("$ctx.$key: expected string or null")
    }

    fun reqInt(key: String, ctx: String): Int {
        val num = (entries[key] ?: throw RecipeValidationException("$ctx: missing '$key'")) as? JsonNum
            ?: throw RecipeValidationException("$ctx.$key: expected integer")
        return num.toExactInt("$ctx.$key")
    }

    fun optNullableDouble(key: String, ctx: String): Double? = when (val v = entries[key]) {
        null, JsonNull -> null
        is JsonNum -> v.toDouble("$ctx.$key")
        else -> throw RecipeValidationException("$ctx.$key: expected number or null")
    }

    fun optBoolean(key: String, ctx: String): Boolean? = when (val v = entries[key]) {
        null, JsonNull -> null
        is JsonBool -> v.value
        else -> throw RecipeValidationException("$ctx.$key: expected boolean")
    }

    fun reqObject(key: String, ctx: String): JsonObj =
        (entries[key] ?: throw RecipeValidationException("$ctx: missing '$key'")).asObject("$ctx.$key")

    fun reqArray(key: String, ctx: String): List<JsonVal> =
        (entries[key] ?: throw RecipeValidationException("$ctx: missing '$key'")).asArray("$ctx.$key")

    fun optArray(key: String, ctx: String): List<JsonVal>? = when (val v = entries[key]) {
        null, JsonNull -> null
        is JsonArr -> v.items
        else -> throw RecipeValidationException("$ctx.$key: expected array")
    }
}

private class JsonArr(val items: List<JsonVal>) : JsonVal
private class JsonStr(val value: String) : JsonVal
private class JsonBool(val value: Boolean) : JsonVal
private data object JsonNull : JsonVal

private class JsonNum(val raw: String) : JsonVal {
    fun toExactInt(ctx: String): Int {
        if ('.' in raw || 'e' in raw || 'E' in raw) {
            throw RecipeValidationException("$ctx: expected integer, got '$raw'")
        }
        return raw.toIntOrNull() ?: throw RecipeValidationException("$ctx: integer out of range '$raw'")
    }

    fun toDouble(ctx: String): Double =
        raw.toDoubleOrNull() ?: throw RecipeValidationException("$ctx: invalid number '$raw'")
}

private class StrictJsonParser(private val src: String) {
    private var pos = 0

    fun parseDocument(): JsonVal {
        skipWs()
        val root = parseValue()
        skipWs()
        if (pos != src.length) {
            throw RecipeValidationException("Unexpected trailing JSON content at offset $pos")
        }
        return root
    }

    private fun parseValue(): JsonVal {
        skipWs()
        if (pos >= src.length) throw RecipeValidationException("Unexpected end of JSON at offset $pos")
        return when (val ch = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonStr(parseString())
            't' -> {
                expectLiteral("true")
                JsonBool(true)
            }
            'f' -> {
                expectLiteral("false")
                JsonBool(false)
            }
            'n' -> {
                expectLiteral("null")
                JsonNull
            }
            '-', in '0'..'9' -> parseNumber()
            else -> throw RecipeValidationException("Unexpected character '$ch' at offset $pos")
        }
    }

    private fun parseObject(): JsonObj {
        pos++ // '{'
        skipWs()
        val map = LinkedHashMap<String, JsonVal>()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return JsonObj(map)
        }
        while (true) {
            skipWs()
            if (pos >= src.length || src[pos] != '"') {
                throw RecipeValidationException("Expected string object key at offset $pos")
            }
            val key = parseString()
            if (map.containsKey(key)) {
                throw RecipeValidationException("Duplicate JSON key '$key' at offset $pos")
            }
            skipWs()
            if (pos >= src.length || src[pos] != ':') {
                throw RecipeValidationException("Expected ':' after key '$key' at offset $pos")
            }
            pos++
            map[key] = parseValue()
            skipWs()
            if (pos >= src.length) throw RecipeValidationException("Unterminated JSON object at offset $pos")
            when (src[pos]) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonObj(map)
                }
                else -> throw RecipeValidationException("Expected ',' or '}' in object at offset $pos")
            }
        }
    }

    private fun parseArray(): JsonArr {
        pos++ // '['
        skipWs()
        val list = ArrayList<JsonVal>()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return JsonArr(list)
        }
        while (true) {
            list.add(parseValue())
            skipWs()
            if (pos >= src.length) throw RecipeValidationException("Unterminated JSON array at offset $pos")
            when (src[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JsonArr(list)
                }
                else -> throw RecipeValidationException("Expected ',' or ']' in array at offset $pos")
            }
        }
    }

    private fun parseString(): String {
        pos++ // '"'
        val sb = StringBuilder()
        while (pos < src.length) {
            val ch = src[pos++]
            if (ch == '"') return sb.toString()
            if (ch.code < 0x20) {
                throw RecipeValidationException("Unescaped control character in JSON string at offset ${pos - 1}")
            }
            if (ch != '\\') {
                sb.append(ch)
                continue
            }
            if (pos >= src.length) throw RecipeValidationException("Unterminated escape in JSON string")
            when (val esc = src[pos++]) {
                '"', '\\', '/' -> sb.append(esc)
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (pos + 4 > src.length) {
                        throw RecipeValidationException("Short \\uXXXX escape at offset $pos")
                    }
                    val hex = src.substring(pos, pos + 4)
                    val codeUnit = hex.toIntOrNull(16)
                        ?: throw RecipeValidationException("Invalid \\u$hex escape at offset $pos")
                    pos += 4
                    sb.append(codeUnit.toChar())
                }
                else -> throw RecipeValidationException("Invalid JSON escape '\\$esc' at offset ${pos - 1}")
            }
        }
        throw RecipeValidationException("Unterminated JSON string")
    }

    private fun parseNumber(): JsonNum {
        val start = pos
        if (src[pos] == '-') pos++
        if (pos >= src.length || src[pos] !in '0'..'9') {
            throw RecipeValidationException("Malformed JSON number at offset $start")
        }
        if (src[pos] == '0') {
            pos++
            if (pos < src.length && src[pos] in '0'..'9') {
                throw RecipeValidationException("Leading zero in JSON number at offset $start")
            }
        } else {
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        if (pos < src.length && src[pos] == '.') {
            pos++
            if (pos >= src.length || src[pos] !in '0'..'9') {
                throw RecipeValidationException("Malformed fraction in JSON number at offset $start")
            }
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            if (pos >= src.length || src[pos] !in '0'..'9') {
                throw RecipeValidationException("Malformed exponent in JSON number at offset $start")
            }
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        return JsonNum(src.substring(start, pos))
    }

    private fun expectLiteral(lit: String) {
        if (!src.startsWith(lit, pos)) {
            throw RecipeValidationException("Expected '$lit' at offset $pos")
        }
        pos += lit.length
    }

    private fun skipWs() {
        while (pos < src.length) {
            when (src[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }
}

private class JsonWriter(private val sb: StringBuilder, private val indent: Int = 0) {
    fun obj(block: ObjScope.() -> Unit) {
        sb.append('{')
        val scope = ObjScope(indent + 2)
        scope.block()
        if (scope.count > 0) {
            sb.append('\n').append(" ".repeat(indent))
        }
        sb.append('}')
    }

    fun arr(block: ArrScope.() -> Unit) {
        sb.append('[')
        val scope = ArrScope(indent + 2)
        scope.block()
        if (scope.count > 0) {
            sb.append('\n').append(" ".repeat(indent))
        }
        sb.append(']')
    }

    inner class ObjScope(private val childIndent: Int) {
        var count = 0
            private set

        private fun prefix(key: String) {
            if (count++ > 0) sb.append(',')
            sb.append('\n').append(" ".repeat(childIndent))
            writeJsonString(sb, key)
            sb.append(": ")
        }

        fun field(key: String, value: String) {
            prefix(key)
            writeJsonString(sb, value)
        }

        fun field(key: String, value: Int) {
            prefix(key)
            sb.append(value)
        }

        fun field(key: String, value: Float) {
            prefix(key)
            sb.append(value.toString())
        }

        fun field(key: String, value: Boolean) {
            prefix(key)
            sb.append(value)
        }

        fun fieldObj(key: String, block: ObjScope.() -> Unit) {
            prefix(key)
            JsonWriter(sb, childIndent).obj(block)
        }

        fun fieldArray(key: String, block: ArrScope.() -> Unit) {
            prefix(key)
            JsonWriter(sb, childIndent).arr(block)
        }
    }

    inner class ArrScope(private val childIndent: Int) {
        var count = 0
            private set

        private fun prefix() {
            if (count++ > 0) sb.append(',')
            sb.append('\n').append(" ".repeat(childIndent))
        }

        fun element(value: String) {
            prefix()
            writeJsonString(sb, value)
        }

        fun elementObj(block: ObjScope.() -> Unit) {
            prefix()
            JsonWriter(sb, childIndent).obj(block)
        }
    }

    companion object {
        private fun writeJsonString(sb: StringBuilder, s: String) {
            sb.append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch.code < 0x20) {
                        sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
            sb.append('"')
        }
    }
}
