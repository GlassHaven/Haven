package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProotRoutingTest {

    @Test
    fun `encode and decode round-trip preserves all fields and defaults gracefully on blank`() {
        assertEquals(ProotRoutingConfig.DEFAULT, ProotRoutingConfig.decode(null))
        assertEquals(ProotRoutingConfig.DEFAULT, ProotRoutingConfig.decode(""))
        assertEquals(ProotRoutingConfig.DEFAULT, ProotRoutingConfig.decode("garbage_without_equals"))
        assertTrue(ProotRoutingConfig.DEFAULT.isDefault)

        val custom = ProotRoutingConfig(
            mode = ProotRoutingMode.SOCKS5,
            tunnelConfigId = "",
            proxyUrl = "socks5h://alice:secret@10.0.0.2:1080",
            noProxy = "localhost,127.0.0.1,.corp.internal",
            useProxychains = true,
        )
        assertFalse(custom.isDefault)
        assertEquals(custom, ProotRoutingConfig.decode(custom.encode()))
    }

    @Test
    fun `validate checks tunnel selection, scheme mismatch, host, and port bounds`() {
        assertNull(ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.FOLLOW_TUNNEL)))
        assertNull(ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.DIRECT)))

        assertEquals(
            ProotRouting.ValidationError.MISSING_TUNNEL,
            ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.HAVEN_TUNNEL, tunnelConfigId = "  ")),
        )
        assertNull(
            ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.HAVEN_TUNNEL, tunnelConfigId = "wg-home")),
        )

        assertEquals(
            ProotRouting.ValidationError.MISSING_PROXY_URL,
            ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.SOCKS5, proxyUrl = "")),
        )
        assertEquals(
            ProotRouting.ValidationError.SCHEME_MISMATCH_SOCKS5,
            ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.SOCKS5, proxyUrl = "http://127.0.0.1:8080")),
        )
        assertEquals(
            ProotRouting.ValidationError.SCHEME_MISMATCH_HTTP,
            ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.HTTP, proxyUrl = "socks5://127.0.0.1:1080")),
        )
        assertEquals(
            ProotRouting.ValidationError.INVALID_PORT,
            ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.SOCKS5, proxyUrl = "127.0.0.1")),
        )
        assertEquals(
            ProotRouting.ValidationError.INVALID_PORT,
            ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.SOCKS5, proxyUrl = "127.0.0.1:70000")),
        )
        assertEquals(
            ProotRouting.ValidationError.INVALID_HOST,
            ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.HTTP, proxyUrl = "http://bad host:8080")),
        )

        assertNull(
            ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.SOCKS5, proxyUrl = "127.0.0.1:1080")),
        )
        assertNull(
            ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.SOCKS5, proxyUrl = "socks5://[::1]:1080")),
        )
        assertNull(
            ProotRouting.validate(ProotRoutingConfig(mode = ProotRoutingMode.HTTP, proxyUrl = "http://user:pw@proxy.example.com:3128")),
        )
    }

    @Test
    fun `direct mode suppresses ALL_PROXY even when global auto-proxy marker is set`() {
        val cacheDir = Files.createTempDirectory("proot-routing-direct").toFile()
        try {
            java.io.File(cacheDir, ".haven_tunnel_socks5").writeText("127.0.0.1:41080\n")
            java.io.File(cacheDir, ".haven_tunnel_auto_proxy").writeText("1\n")

            val followEnv = ProotRouting.resolveEnv(
                config = ProotRoutingConfig(mode = ProotRoutingMode.FOLLOW_TUNNEL),
                cacheDir = cacheDir,
            )
            assertEquals("socks5h://127.0.0.1:41080", followEnv["ALL_PROXY"])

            val directEnv = ProotRouting.resolveEnv(
                config = ProotRoutingConfig(mode = ProotRoutingMode.DIRECT),
                cacheDir = cacheDir,
            )
            assertEquals("direct", directEnv["HAVEN_PROOT_ROUTING_MODE"])
            assertEquals("127.0.0.1:41080", directEnv["HAVEN_TUNNEL_SOCKS5"])
            assertFalse(directEnv.containsKey("ALL_PROXY"))
            assertFalse(directEnv.containsKey("HTTP_PROXY"))
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `haven_tunnel mode reads per-tunnel socks map or invokes starter on demand`() {
        val cacheDir = Files.createTempDirectory("proot-routing-tunnel").toFile()
        try {
            java.io.File(cacheDir, ProotRouting.TUNNEL_SOCKS_MAP_FILE).writeText(
                "wg-office=42222\n",
            )
            val fromMap = ProotRouting.resolveEnv(
                config = ProotRoutingConfig(
                    mode = ProotRoutingMode.HAVEN_TUNNEL,
                    tunnelConfigId = "wg-office",
                ),
                cacheDir = cacheDir,
            )
            assertEquals("socks5h://127.0.0.1:42222", fromMap["ALL_PROXY"])
            assertEquals("127.0.0.1:42222", fromMap["HAVEN_TUNNEL_SOCKS5"])
            assertEquals("localhost,127.0.0.1,::1", fromMap["NO_PROXY"])

            var startedId: String? = null
            val fromStarter = ProotRouting.resolveEnv(
                config = ProotRoutingConfig(
                    mode = ProotRoutingMode.HAVEN_TUNNEL,
                    tunnelConfigId = "wg-on-demand",
                ),
                cacheDir = cacheDir,
                tunnelStarter = { id ->
                    startedId = id
                    43333
                },
            )
            assertEquals("wg-on-demand", startedId)
            assertEquals("socks5h://127.0.0.1:43333", fromStarter["ALL_PROXY"])
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `socks5 and http modes write proxychains config and wrap command when installed`() {
        val cacheDir = Files.createTempDirectory("proot-routing-pc-cache").toFile()
        val rootfsDir = Files.createTempDirectory("proot-routing-pc-rootfs").toFile()
        try {
            val pcBin = java.io.File(rootfsDir, "usr/bin/proxychains4").apply {
                parentFile?.mkdirs()
                writeText("#!/bin/sh\n")
            }
            assertTrue(pcBin.isFile)

            val config = ProotRoutingConfig(
                mode = ProotRoutingMode.SOCKS5,
                proxyUrl = "alice:pw@localhost:9050",
                noProxy = "localhost,127.0.0.1",
                useProxychains = true,
            )
            val env = ProotRouting.resolveEnv(
                config = config,
                cacheDir = cacheDir,
                rootfsDir = rootfsDir,
            )
            assertEquals("socks5h://alice:pw@localhost:9050", env["ALL_PROXY"])
            assertEquals(ProotRouting.GUEST_PROXYCHAINS_CONF_ABS, env["PROXYCHAINS_CONF_FILE"])

            val conf = java.io.File(rootfsDir, ProotRouting.GUEST_PROXYCHAINS_CONF_REL).readText()
            assertTrue(conf.contains("proxy_dns"))
            assertTrue(conf.contains("localnet 127.0.0.0/255.0.0.0"))
            assertTrue(conf.contains("socks5 127.0.0.1 9050 alice pw"))

            val wrapped = ProotRouting.wrapShellCommandIfNeeded(rootfsDir, config, env, "curl https://example.com")
            assertEquals(
                "proxychains4 -q -f /etc/proxychains-haven.conf /bin/sh -lc 'curl https://example.com'",
                wrapped,
            )
        } finally {
            cacheDir.deleteRecursively()
            rootfsDir.deleteRecursively()
        }
    }
}
