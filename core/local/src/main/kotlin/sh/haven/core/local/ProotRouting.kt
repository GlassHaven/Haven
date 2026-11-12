package sh.haven.core.local

import java.io.File
import java.net.InetAddress

/**
 * Per-distro network routing mode for PRoot environments (#835).
 *
 * PRoot runs as an unprivileged child process without `CAP_NET_ADMIN` or
 * `CLONE_NEWNET`, so per-distro routing operates in userland via proxy
 * environment variables (`ALL_PROXY`, `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`),
 * Haven's loopback tunnel SOCKS5 listeners (`TunnelPortForwardManager`), and
 * optional `proxychains4` wrapping when installed inside the guest.
 */
enum class ProotRoutingMode(val id: String) {
    /** Follow global `Settings -> Tunnels` guest auto-proxy (#792). */
    FOLLOW_TUNNEL("follow_tunnel"),

    /** Bypass proxy env vars for this distro even when global auto-proxy is on. */
    DIRECT("direct"),

    /** Route through a specific saved Haven tunnel's loopback SOCKS5 listener. */
    HAVEN_TUNNEL("haven_tunnel"),

    /** Route through a custom SOCKS5 proxy (`socks5h://[user:pass@]host:port`). */
    SOCKS5("socks5"),

    /** Route through a custom HTTP(S) proxy (`http://[user:pass@]host:port`). */
    HTTP("http"),
    ;

    companion object {
        val DEFAULT = FOLLOW_TUNNEL

        fun fromId(id: String?): ProotRoutingMode =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Per-distro routing configuration persisted in `ProotManager` preferences (#835).
 */
data class ProotRoutingConfig(
    val mode: ProotRoutingMode = ProotRoutingMode.DEFAULT,
    val tunnelConfigId: String = "",
    val proxyUrl: String = "",
    val noProxy: String = ProotRouting.DEFAULT_NO_PROXY,
    val useProxychains: Boolean = false,
    val blockDirectTraffic: Boolean = true,
) {
    val isDefault: Boolean
        get() = mode == ProotRoutingMode.DEFAULT &&
            tunnelConfigId.isBlank() &&
            proxyUrl.isBlank() &&
            noProxy.trim() == ProotRouting.DEFAULT_NO_PROXY &&
            !useProxychains &&
            blockDirectTraffic

    fun encode(): String = buildString {
        appendLine("mode=${mode.id}")
        appendLine("tunnelConfigId=${tunnelConfigId.trim()}")
        appendLine("proxyUrl=${proxyUrl.trim()}")
        appendLine("noProxy=${noProxy.trim()}")
        appendLine("useProxychains=$useProxychains")
        appendLine("blockDirectTraffic=$blockDirectTraffic")
    }

    companion object {
        val DEFAULT = ProotRoutingConfig()

        fun decode(raw: String?): ProotRoutingConfig {
            if (raw.isNullOrBlank()) return DEFAULT
            val map = linkedMapOf<String, String>()
            for (line in raw.lineSequence()) {
                val idx = line.indexOf('=')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                map[key] = value
            }
            if (map.isEmpty()) return DEFAULT
            return ProotRoutingConfig(
                mode = ProotRoutingMode.fromId(map["mode"]),
                tunnelConfigId = map["tunnelConfigId"].orEmpty(),
                proxyUrl = map["proxyUrl"].orEmpty(),
                noProxy = map["noProxy"] ?: ProotRouting.DEFAULT_NO_PROXY,
                useProxychains = map["useProxychains"]?.equals("true", ignoreCase = true) == true,
                // Default to true when absent so existing proxy profiles fail-close on upgrade (#838).
                blockDirectTraffic = map["blockDirectTraffic"]?.let { !it.equals("false", ignoreCase = true) } ?: true,
            )
        }
    }
}

/**
 * Validation, URL normalization, environment export, and `proxychains4` helper
 * generation for per-distro PRoot routing (#835).
 */
object ProotRouting {

    const val DEFAULT_NO_PROXY = "localhost,127.0.0.1,::1"
    const val TUNNEL_SOCKS_MAP_FILE = ".haven_tunnel_socks5_by_id"
    const val GUEST_PROXYCHAINS_CONF_REL = "etc/proxychains-haven.conf"
    const val GUEST_PROXYCHAINS_CONF_ABS = "/etc/proxychains-haven.conf"

    enum class ValidationError {
        MISSING_TUNNEL,
        MISSING_PROXY_URL,
        SCHEME_MISMATCH_SOCKS5,
        SCHEME_MISMATCH_HTTP,
        INVALID_HOST,
        INVALID_PORT,
    }

    data class ParsedProxyEndpoint(
        val scheme: String,
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    ) {
        val normalizedUrl: String
            get() {
                val auth = when {
                    !username.isNullOrEmpty() && !password.isNullOrEmpty() -> "$username:$password@"
                    !username.isNullOrEmpty() -> "$username@"
                    else -> ""
                }
                val formattedHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
                return "$scheme://$auth$formattedHost:$port"
            }

        val isSocks: Boolean
            get() = scheme == "socks5" || scheme == "socks5h"

        val resolveDnsRemotely: Boolean
            get() = scheme == "socks5h"
    }

    sealed class ParseResult {
        data class Valid(val endpoint: ParsedProxyEndpoint) : ParseResult()
        data class Invalid(val error: ValidationError) : ParseResult()
    }

    /**
     * Validate [config]. Returns `null` when valid, or a specific [ValidationError]
     * suitable for inline UI display and MCP error reporting.
     */
    fun validate(config: ProotRoutingConfig): ValidationError? = when (config.mode) {
        ProotRoutingMode.FOLLOW_TUNNEL, ProotRoutingMode.DIRECT -> null
        ProotRoutingMode.HAVEN_TUNNEL -> {
            if (config.tunnelConfigId.isBlank()) ValidationError.MISSING_TUNNEL else null
        }
        ProotRoutingMode.SOCKS5, ProotRoutingMode.HTTP -> {
            when (val parsed = parseProxyEndpoint(config.mode, config.proxyUrl)) {
                is ParseResult.Valid -> null
                is ParseResult.Invalid -> parsed.error
            }
        }
    }

    /**
     * Parse `[scheme://][user[:pass]@]host:port` for [ProotRoutingMode.SOCKS5] or
     * [ProotRoutingMode.HTTP]. Bare `host:port` defaults to `socks5h://` for SOCKS5
     * (so guest DNS queries also traverse the proxy) and `http://` for HTTP.
     */
    fun parseProxyEndpoint(mode: ProotRoutingMode, rawUrl: String): ParseResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ParseResult.Invalid(ValidationError.MISSING_PROXY_URL)
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) {
            return ParseResult.Invalid(ValidationError.INVALID_HOST)
        }

        val defaultScheme = when (mode) {
            ProotRoutingMode.SOCKS5 -> "socks5h"
            ProotRoutingMode.HTTP -> "http"
            else -> "socks5h"
        }

        val scheme: String
        val rest: String
        val schemeIdx = trimmed.indexOf("://")
        if (schemeIdx >= 0) {
            scheme = trimmed.substring(0, schemeIdx).lowercase()
            rest = trimmed.substring(schemeIdx + 3)
            when (mode) {
                ProotRoutingMode.SOCKS5 -> if (scheme != "socks5" && scheme != "socks5h") {
                    return ParseResult.Invalid(ValidationError.SCHEME_MISMATCH_SOCKS5)
                }
                ProotRoutingMode.HTTP -> if (scheme != "http" && scheme != "https") {
                    return ParseResult.Invalid(ValidationError.SCHEME_MISMATCH_HTTP)
                }
                else -> {}
            }
        } else {
            scheme = defaultScheme
            rest = trimmed
        }

        // Reject trailing path segments beyond optional single trailing slash.
        val withoutTrailingSlash = if (rest.endsWith("/")) rest.dropLast(1) else rest
        if ('/' in withoutTrailingSlash || '?' in withoutTrailingSlash || '#' in withoutTrailingSlash) {
            return ParseResult.Invalid(ValidationError.INVALID_HOST)
        }

        val atIdx = withoutTrailingSlash.lastIndexOf('@')
        val userInfo = if (atIdx >= 0) withoutTrailingSlash.substring(0, atIdx) else null
        val hostPort = if (atIdx >= 0) withoutTrailingSlash.substring(atIdx + 1) else withoutTrailingSlash
        if (userInfo != null && userInfo.isEmpty()) {
            return ParseResult.Invalid(ValidationError.INVALID_HOST)
        }
        val username: String?
        val password: String?
        if (userInfo != null) {
            val colonIdx = userInfo.indexOf(':')
            if (colonIdx >= 0) {
                username = userInfo.substring(0, colonIdx)
                password = userInfo.substring(colonIdx + 1)
                if (username.isEmpty()) return ParseResult.Invalid(ValidationError.INVALID_HOST)
            } else {
                username = userInfo
                password = null
            }
        } else {
            username = null
            password = null
        }

        val host: String
        val portStr: String
        if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket <= 1) return ParseResult.Invalid(ValidationError.INVALID_HOST)
            val innerHost = hostPort.substring(1, closeBracket)
            if (!ProotDns.isIpLiteral(innerHost) && innerHost != "::1") {
                return ParseResult.Invalid(ValidationError.INVALID_HOST)
            }
            val remainder = hostPort.substring(closeBracket + 1)
            if (!remainder.startsWith(":")) return ParseResult.Invalid(ValidationError.INVALID_PORT)
            host = innerHost
            portStr = remainder.substring(1)
        } else {
            val colonCount = hostPort.count { it == ':' }
            if (colonCount == 0) return ParseResult.Invalid(ValidationError.INVALID_PORT)
            if (colonCount > 1) {
                // Unbracketed IPv6 without port or ambiguous colons
                return ParseResult.Invalid(ValidationError.INVALID_HOST)
            }
            val colonIdx = hostPort.lastIndexOf(':')
            host = hostPort.substring(0, colonIdx)
            portStr = hostPort.substring(colonIdx + 1)
        }

        if (!isValidHost(host)) return ParseResult.Invalid(ValidationError.INVALID_HOST)
        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            return ParseResult.Invalid(ValidationError.INVALID_PORT)
        }

        return ParseResult.Valid(
            ParsedProxyEndpoint(
                scheme = scheme,
                host = host,
                port = port,
                username = username,
                password = password,
            ),
        )
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (ProotDns.isIpLiteral(host)) return true
        if (host.startsWith('.') || host.endsWith('.') || ".." in host) return false
        return host.split('.').all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                !label.startsWith('-') &&
                !label.endsWith('-') &&
                label.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }
        }
    }

    /** Read `tunnelConfigId=port` lines written by `TunnelPortForwardManager`. */
    fun readTunnelSocksPort(cacheDir: File, tunnelConfigId: String): Int? {
        val target = tunnelConfigId.trim()
        if (target.isEmpty()) return null
        val mapFile = File(cacheDir, TUNNEL_SOCKS_MAP_FILE)
        val raw = runCatching { mapFile.takeIf { it.isFile }?.readText() }.getOrNull() ?: return null
        for (line in raw.lineSequence()) {
            val idx = line.lastIndexOf('=')
            if (idx <= 0) continue
            val id = line.substring(0, idx).trim()
            if (id == target) {
                val port = line.substring(idx + 1).trim().toIntOrNull()
                if (port != null && port in 1..65535) return port
            }
        }
        return null
    }

    /**
     * Resolve guest environment variables for [config] and optionally write
     * `/etc/proxychains-haven.conf` under [rootfsDir] when [ProotRoutingConfig.useProxychains]
     * is enabled.
     */
    fun resolveEnv(
        config: ProotRoutingConfig,
        cacheDir: File,
        rootfsDir: File? = null,
        tunnelStarter: ((String) -> Int?)? = null,
        hostIpResolver: (String) -> String? = ::defaultHostIpResolver,
    ): Map<String, String> {
        val globalSocks = runCatching {
            File(cacheDir, ".haven_tunnel_socks5").takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        when (config.mode) {
            ProotRoutingMode.FOLLOW_TUNNEL -> {
                if (globalSocks == null) return emptyMap()
                val result = linkedMapOf(
                    "HAVEN_TUNNEL_SOCKS5" to globalSocks,
                    "HAVEN_TUNNEL_QEMU_SOCKS5" to globalSocks.replace("127.0.0.1:", "10.0.2.2:"),
                )
                if (File(cacheDir, ".haven_tunnel_auto_proxy").exists()) {
                    val endpoint = ParsedProxyEndpoint(
                        scheme = "socks5h",
                        host = globalSocks.substringBeforeLast(':'),
                        port = globalSocks.substringAfterLast(':').toIntOrNull() ?: 1080,
                    )
                    putProxyEnv(result, endpoint.normalizedUrl, config.noProxy)
                    maybeWriteProxychains(result, rootfsDir, config.useProxychains, endpoint, hostIpResolver)
                }
                return result
            }

            ProotRoutingMode.DIRECT -> {
                val result = linkedMapOf("HAVEN_PROOT_ROUTING_MODE" to ProotRoutingMode.DIRECT.id)
                if (globalSocks != null) {
                    result["HAVEN_TUNNEL_SOCKS5"] = globalSocks
                    result["HAVEN_TUNNEL_QEMU_SOCKS5"] = globalSocks.replace("127.0.0.1:", "10.0.2.2:")
                }
                return result
            }

            ProotRoutingMode.HAVEN_TUNNEL -> {
                val result = linkedMapOf("HAVEN_PROOT_ROUTING_MODE" to ProotRoutingMode.HAVEN_TUNNEL.id)
                val id = config.tunnelConfigId.trim()
                if (id.isEmpty()) return result
                val port = readTunnelSocksPort(cacheDir, id) ?: tunnelStarter?.invoke(id)
                if (port == null || port !in 1..65535) return result
                val socks = "127.0.0.1:$port"
                result["HAVEN_TUNNEL_SOCKS5"] = socks
                result["HAVEN_TUNNEL_QEMU_SOCKS5"] = "10.0.2.2:$port"
                val endpoint = ParsedProxyEndpoint(scheme = "socks5h", host = "127.0.0.1", port = port)
                putProxyEnv(result, endpoint.normalizedUrl, config.noProxy)
                maybeWriteProxychains(result, rootfsDir, config.useProxychains, endpoint, hostIpResolver)
                return result
            }

            ProotRoutingMode.SOCKS5, ProotRoutingMode.HTTP -> {
                val result = linkedMapOf("HAVEN_PROOT_ROUTING_MODE" to config.mode.id)
                val parsed = parseProxyEndpoint(config.mode, config.proxyUrl)
                if (parsed !is ParseResult.Valid) return result
                if (globalSocks != null) {
                    result["HAVEN_TUNNEL_SOCKS5"] = globalSocks
                    result["HAVEN_TUNNEL_QEMU_SOCKS5"] = globalSocks.replace("127.0.0.1:", "10.0.2.2:")
                }
                putProxyEnv(result, parsed.endpoint.normalizedUrl, config.noProxy)
                maybeWriteProxychains(result, rootfsDir, config.useProxychains, parsed.endpoint, hostIpResolver)
                return result
            }
        }
    }

    private fun putProxyEnv(
        target: MutableMap<String, String>,
        url: String,
        noProxy: String,
    ) {
        target["ALL_PROXY"] = url
        target["all_proxy"] = url
        target["HTTP_PROXY"] = url
        target["http_proxy"] = url
        target["HTTPS_PROXY"] = url
        target["https_proxy"] = url
        val cleanedNoProxy = noProxy.trim()
        if (cleanedNoProxy.isNotEmpty()) {
            target["NO_PROXY"] = cleanedNoProxy
            target["no_proxy"] = cleanedNoProxy
        }
    }

    private fun maybeWriteProxychains(
        target: MutableMap<String, String>,
        rootfsDir: File?,
        useProxychains: Boolean,
        endpoint: ParsedProxyEndpoint,
        hostIpResolver: (String) -> String?,
    ) {
        if (!useProxychains || rootfsDir == null) return
        val confText = buildProxychainsConf(endpoint, hostIpResolver) ?: return
        runCatching {
            val confFile = File(rootfsDir, GUEST_PROXYCHAINS_CONF_REL)
            confFile.parentFile?.mkdirs()
            confFile.writeText(confText)
            target["PROXYCHAINS_CONF_FILE"] = GUEST_PROXYCHAINS_CONF_ABS
        }
    }

    /**
     * Format a `proxychains-ng` configuration file for [endpoint].
     * `proxychains-ng` requires a numeric IP literal in `[ProxyList]`, so
     * `localhost` maps to `127.0.0.1` and hostnames resolve via [hostIpResolver].
     */
    fun buildProxychainsConf(
        endpoint: ParsedProxyEndpoint,
        hostIpResolver: (String) -> String? = ::defaultHostIpResolver,
    ): String? {
        val ip = when {
            endpoint.host.equals("localhost", ignoreCase = true) -> "127.0.0.1"
            ProotDns.isIpLiteral(endpoint.host) || endpoint.host == "::1" -> endpoint.host
            else -> hostIpResolver(endpoint.host) ?: return null
        }
        val pcType = if (endpoint.isSocks) "socks5" else "http"
        val authSuffix = when {
            !endpoint.username.isNullOrEmpty() && !endpoint.password.isNullOrEmpty() ->
                " ${endpoint.username} ${endpoint.password}"
            else -> ""
        }
        return buildString {
            appendLine("# Generated by Haven for per-distro PRoot routing (#835).")
            appendLine("strict_chain")
            appendLine("quiet_mode")
            if (endpoint.resolveDnsRemotely) {
                appendLine("proxy_dns")
            }
            appendLine("localnet 127.0.0.0/255.0.0.0")
            appendLine("localnet ::1/128")
            appendLine("tcp_read_time_out 15000")
            appendLine("tcp_connect_time_out 8000")
            appendLine("[ProxyList]")
            appendLine("$pcType $ip ${endpoint.port}$authSuffix")
        }
    }

    /** True when `proxychains4` binary is installed inside [rootfsDir]. */
    fun isProxychainsInstalled(rootfsDir: File): Boolean =
        listOf("usr/bin/proxychains4", "bin/proxychains4", "usr/local/bin/proxychains4")
            .any { rel -> File(rootfsDir, rel).isFile }

    /**
     * Wrap a one-shot shell command with `proxychains4 -q -f /etc/proxychains-haven.conf`
     * when [ProotRoutingConfig.useProxychains] is enabled, a proxy was resolved into [env],
     * and `proxychains4` exists inside [rootfsDir].
     */
    fun wrapShellCommandIfNeeded(
        rootfsDir: File,
        config: ProotRoutingConfig,
        env: Map<String, String>,
        command: String,
    ): String {
        if (!config.useProxychains) return command
        if (!env.containsKey("PROXYCHAINS_CONF_FILE")) return command
        if (!isProxychainsInstalled(rootfsDir)) return command
        val escaped = command.replace("'", "'\\''")
        return "proxychains4 -q -f $GUEST_PROXYCHAINS_CONF_ABS /bin/sh -lc '$escaped'"
    }

    /**
     * Resolve the active proxy endpoint for [config] when one is currently usable
     * (`SOCKS5`, `HTTP`, `HAVEN_TUNNEL` with an active loopback port, or `FOLLOW_TUNNEL`
     * while global tunnel auto-proxy is enabled). Returns `null` for `DIRECT`, inactive
     * `FOLLOW_TUNNEL`, or unresolvable/down `HAVEN_TUNNEL`.
     */
    fun resolveActiveProxyEndpoint(
        config: ProotRoutingConfig,
        cacheDir: File,
        tunnelStarter: ((String) -> Int?)? = null,
    ): ParsedProxyEndpoint? = when (config.mode) {
        ProotRoutingMode.DIRECT -> null

        ProotRoutingMode.FOLLOW_TUNNEL -> {
            val globalSocks = runCatching {
                File(cacheDir, ".haven_tunnel_socks5").takeIf { it.isFile }?.readText()?.trim()
            }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (globalSocks != null && File(cacheDir, ".haven_tunnel_auto_proxy").exists()) {
                (parseProxyEndpoint(ProotRoutingMode.SOCKS5, globalSocks) as? ParseResult.Valid)?.endpoint
            } else {
                null
            }
        }

        ProotRoutingMode.HAVEN_TUNNEL -> {
            val id = config.tunnelConfigId.trim()
            if (id.isEmpty()) {
                null
            } else {
                val port = readTunnelSocksPort(cacheDir, id) ?: tunnelStarter?.invoke(id)
                if (port != null && port in 1..65535) {
                    ParsedProxyEndpoint(scheme = "socks5h", host = "127.0.0.1", port = port)
                } else {
                    null
                }
            }
        }

        ProotRoutingMode.SOCKS5, ProotRoutingMode.HTTP -> {
            (parseProxyEndpoint(config.mode, config.proxyUrl) as? ParseResult.Valid)?.endpoint
        }
    }

    /**
     * Build PRoot `--net-policy=<spec>` value (#838) combining:
     * - Fail-closed `lockdown` + `allow=<ip>:<port>` when [ProotRoutingConfig.blockDirectTraffic]
     *   is enabled and this distro is routed through an active proxy (`SOCKS5`, `HTTP`,
     *   `HAVEN_TUNNEL`, or `FOLLOW_TUNNEL` while global tunnel auto-proxy is active).
     *   Explicit proxy modes (`SOCKS5`, `HTTP`, `HAVEN_TUNNEL`) fail closed (`lockdown`
     *   with loopback-only egress) even if the tunnel/proxy endpoint could not be
     *   brought up or resolved, preventing silent clearnet/DNS fallback leaks.
     * - `dns=<ip>:<port>` entries from [dnsRedirectSpecs] so guest resolvers querying
     *   port 53 reach custom-port DNS listeners (e.g. `127.0.0.1:5400`).
     * - `dns_proxy=<v4>:<port>` and `dns_proxy6=[::1]:<port>` when a loopback
     *   [ProotDnsProxyBridge] listener is active so external port-53 DNS queries
     *   (including `dig @1.1.1.1`) traverse the proxy over TCP port 53.
     *
     * Returns `null` when none of `lockdown`, `dns=`, or `dns_proxy=` applies.
     */
    fun buildNetPolicySpec(
        config: ProotRoutingConfig,
        cacheDir: File,
        dnsRedirectSpecs: List<String> = emptyList(),
        dnsProxyIpv4Spec: String? = null,
        dnsProxyIpv6Spec: String? = null,
        tunnelStarter: ((String) -> Int?)? = null,
        hostIpResolver: (String) -> String? = ::defaultHostIpResolver,
    ): String? {
        val tokens = mutableListOf<String>()
        val allowSpecs = mutableListOf<String>()
        var lockdown = false

        when (config.mode) {
            ProotRoutingMode.DIRECT -> Unit

            ProotRoutingMode.FOLLOW_TUNNEL -> {
                val active = resolveActiveProxyEndpoint(config, cacheDir, tunnelStarter)
                if (config.blockDirectTraffic && active != null) {
                    lockdown = true
                    allowSpecs += resolveAllowSpecs(active, hostIpResolver)
                }
            }

            ProotRoutingMode.HAVEN_TUNNEL -> {
                if (config.blockDirectTraffic) {
                    lockdown = true
                    val active = resolveActiveProxyEndpoint(config, cacheDir, tunnelStarter)
                    if (active != null) {
                        allowSpecs += "127.0.0.1:${active.port}"
                    }
                }
            }

            ProotRoutingMode.SOCKS5, ProotRoutingMode.HTTP -> {
                if (config.blockDirectTraffic) {
                    lockdown = true
                    val active = resolveActiveProxyEndpoint(config, cacheDir, tunnelStarter)
                    if (active != null) {
                        allowSpecs += resolveAllowSpecs(active, hostIpResolver)
                    }
                }
            }
        }

        if (lockdown) {
            tokens += "lockdown"
            allowSpecs.distinct().forEach { tokens += "allow=$it" }
        }
        dnsRedirectSpecs
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { tokens += "dns=$it" }
        dnsProxyIpv4Spec?.trim()?.takeIf { it.isNotEmpty() }?.let { tokens += "dns_proxy=$it" }
        dnsProxyIpv6Spec?.trim()?.takeIf { it.isNotEmpty() }?.let { tokens += "dns_proxy6=$it" }

        return tokens.takeIf { it.isNotEmpty() }?.joinToString(";")
    }

    private fun resolveAllowSpecs(
        endpoint: ParsedProxyEndpoint,
        hostIpResolver: (String) -> String?,
    ): List<String> {
        if (endpoint.host.equals("localhost", ignoreCase = true)) {
            return listOf("127.0.0.1:${endpoint.port}", "[::1]:${endpoint.port}")
        }
        val ip = when {
            ProotDns.isIpLiteral(endpoint.host) || endpoint.host == "::1" -> endpoint.host
            else -> hostIpResolver(endpoint.host)?.takeIf { ProotDns.isIpLiteral(it) || it == "::1" }
                ?: return emptyList()
        }
        return listOf(if (':' in ip) "[$ip]:${endpoint.port}" else "$ip:${endpoint.port}")
    }

    private fun defaultHostIpResolver(host: String): String? =
        runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
}
