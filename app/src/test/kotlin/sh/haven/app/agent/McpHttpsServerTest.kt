package sh.haven.app.agent

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.McpStatusHolder
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.security.McpLocalTlsManager
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class McpHttpsServerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testProtector = object : McpLocalTlsManager.ByteProtector {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { i -> (plain[i].toInt() xor 0x3C).toByte() }

        override fun decrypt(cipher: ByteArray): ByteArray =
            ByteArray(cipher.size) { i -> (cipher[i].toInt() xor 0x3C).toByte() }
    }

    private fun createServer(
        tlsManager: McpLocalTlsManager,
        statusHolder: McpStatusHolder,
        httpsEnabled: Boolean = true,
    ): McpServer {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.mcpAllowedClients } returns flowOf(emptySet())
        every { prefs.mcpClientTokenHashes } returns flowOf(emptyMap())
        every { prefs.trustLoopbackMcpClients } returns flowOf(true)
        every { prefs.mcpWireguardEnabled } returns flowOf(false)
        every { prefs.mcpLanBindEnabled } returns flowOf(false)
        every { prefs.mcpHttpsEnabled } returns flowOf(httpsEnabled)

        return McpServer(
            context = mockk<Context>(relaxed = true),
            connectionRepository = mockk<ConnectionRepository>(relaxed = true),
            portForwardRepository = mockk<PortForwardRepository>(relaxed = true),
            sshSessionManager = mockk<SshSessionManager>(relaxed = true),
            sessionManagerRegistry = mockk<SessionManagerRegistry>(relaxed = true),
            reticulumSessionManager = mockk(relaxed = true),
            reticulumForwardServer = mockk(relaxed = true),
            rcloneClient = mockk<RcloneClient>(relaxed = true),
            mailSessionManager = mockk(relaxed = true),
            sftpStreamServer = mockk<SftpStreamServer>(relaxed = true),
            hlsStreamServer = mockk<HlsStreamServer>(relaxed = true),
            ffmpegExecutor = mockk<FfmpegExecutor>(relaxed = true),
            preferencesRepository = prefs,
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
            auditRecorder = mockk<AgentAuditRecorder>(relaxed = true),
            consentManager = AgentConsentManager(),
            agentUiCommandBus = sh.haven.core.data.agent.AgentUiCommandBus(),
            transportSelector = mockk(relaxed = true),
            workspaceRepository = mockk(relaxed = true),
            workspaceLauncher = mockk(relaxed = true),
            tunnelConfigRepository = mockk(relaxed = true),
            tunnelManager = mockk(relaxed = true),
            terminalSessionRegistry = sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
            portKnocker = mockk(relaxed = true),
            spaSender = mockk(relaxed = true),
            connectionLogRepository = mockk(relaxed = true),
            servedFileTracker = mockk(relaxed = true),
            syncProfileRepository = mockk(relaxed = true),
            mailRuleRepository = mockk(relaxed = true),
            mailWatchManager = mockk(relaxed = true),
            agentActivityHolder = mockk(relaxed = true),
            terminalInputQueue = mockk(relaxed = true),
            prootInstallLogRepository = mockk(relaxed = true),
            sshKeyRepository = mockk(relaxed = true),
            knownHostDao = mockk(relaxed = true),
            stepCaConfigRepository = mockk(relaxed = true),
            totpSecretRepository = mockk(relaxed = true),
            ageIdentityRepository = mockk(relaxed = true),
            desktopSessionRegistry = mockk(relaxed = true),
            aiRouteRegistry = sh.haven.core.openai.AiRouteRegistry(),
            usbBroker = mockk(relaxed = true),
            usbIpServer = mockk(relaxed = true),
            usbRedirServer = mockk(relaxed = true),
            usbDriveVmManager = mockk(relaxed = true),
            umlRecoveryManager = mockk(relaxed = true),
            presentationManager = sh.haven.core.data.agent.AgentPresentationManager(),
            mcpStatusHolder = statusHolder,
            mcpTunnelManager = mockk(relaxed = true),
            havenUiBridge = mockk(relaxed = true),
            standingPolicyEnforcer = StandingPolicyEnforcer(mockk(relaxed = true)),
            standingPolicyRepository = mockk(relaxed = true),
            mcpLocalTlsManager = tlsManager,
        )
    }

    private fun sslContextForCaPem(caPem: ByteArray): SSLContext {
        val caCert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(caPem)) as X509Certificate
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("haven-ca", caCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, null)
        }
    }

    @Test
    fun `HTTPS loopback listener serves MCP initialize and tools_list verified against exported local CA`() {
        val tlsManager = McpLocalTlsManager(tempFolder.newFolder("mcp-tls"), testProtector)
        val statusHolder = McpStatusHolder()
        val server = createServer(tlsManager, statusHolder, httpsEnabled = true)

        server.use {
            server.start()
            assertTrue("Expected HTTPS port > 0, got ${server.httpsPort}", server.httpsPort > 0)
            val httpsUrl = "https://127.0.0.1:${server.httpsPort}/mcp"
            assertEquals(httpsUrl, server.httpsEndpointUrl.value)
            assertEquals(httpsUrl, statusHolder.httpsEndpointUrl.value)

            val clientSsl = sslContextForCaPem(tlsManager.exportLocalCaPem())

            // 1. initialize over HTTPS
            val initBody = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": { "name": "XiaoBu Next", "version": "1.0.0" }
                  }
                }
            """.trimIndent()

            val initConn = (URL(httpsUrl).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = clientSsl.socketFactory
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            initConn.outputStream.use { it.write(initBody.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, initConn.responseCode)
            val sessionId = initConn.getHeaderField("Mcp-Session-Id")
            assertNotNull("initialize must return Mcp-Session-Id over HTTPS", sessionId)
            val initJson = JSONObject(initConn.inputStream.bufferedReader().readText())
            assertTrue(initJson.getJSONObject("result").has("serverInfo"))
            initConn.disconnect()

            // 2. tools/list over HTTPS using Mcp-Session-Id
            val listBody = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
            val listConn = (URL(httpsUrl).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = clientSsl.socketFactory
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Mcp-Session-Id", sessionId)
            }
            listConn.outputStream.use { it.write(listBody.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, listConn.responseCode)
            val listJson = JSONObject(listConn.inputStream.bufferedReader().readText())
            assertTrue(listJson.getJSONObject("result").getJSONArray("tools").length() > 0)
            listConn.disconnect()

            // 3. Hot-reload after rotating local CA + disabling HTTPS at runtime
            val oldCaPem = tlsManager.exportLocalCaPem()
            tlsManager.regenerateLocalCa()
            server.reloadHttpsCredentials()
            val newSsl = sslContextForCaPem(tlsManager.exportLocalCaPem())
            assertTrue(!oldCaPem.contentEquals(tlsManager.exportLocalCaPem()))

            val postRotateConn = (URL("https://127.0.0.1:${server.httpsPort}/mcp").openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = newSsl.socketFactory
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Mcp-Session-Id", sessionId)
            }
            postRotateConn.outputStream.use { it.write(listBody.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, postRotateConn.responseCode)
            postRotateConn.disconnect()

            server.setHttpsEnabled(false)
            assertEquals(0, server.httpsPort)
            assertNull(server.httpsEndpointUrl.value)
            assertNull(statusHolder.httpsEndpointUrl.value)
            assertTrue("Plain HTTP listener must remain running", server.isRunning && server.port > 0)
        }
    }
}
