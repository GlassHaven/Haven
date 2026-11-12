package sh.haven.core.security

import org.bouncycastle.asn1.ASN1Sequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

class McpLocalTlsManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val xorProtector = object : McpLocalTlsManager.ByteProtector {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { i -> (plain[i].toInt() xor 0x5A).toByte() }

        override fun decrypt(cipher: ByteArray): ByteArray =
            ByteArray(cipher.size) { i -> (cipher[i].toInt() xor 0x5A).toByte() }
    }

    @Test
    fun `generated CA and leaf pass PKIX validation, SAN host check, and RFC 5758 AlgorithmIdentifier check`() {
        var now = 1_790_000_000_000L
        val manager = McpLocalTlsManager(tempFolder.newFolder("tls"), xorProtector) { now }

        val caPem = manager.exportLocalCaPem()
        val caPemStr = caPem.toString(Charsets.US_ASCII)
        assertTrue(caPemStr.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(caPemStr.endsWith("-----END CERTIFICATE-----\n"))

        val cf = CertificateFactory.getInstance("X.509")
        val caCert = cf.generateCertificate(ByteArrayInputStream(caPem)) as X509Certificate
        assertTrue("Root CA must have CA:TRUE", caCert.basicConstraints >= 0)
        assertTrue("Root CA must allow keyCertSign", caCert.keyUsage[5])

        // RFC 5758 §3.2: AlgorithmIdentifier for ecdsa-with-SHA256 MUST have size 1 (OID only, no NULL).
        val caSeq = ASN1Sequence.getInstance(caCert.encoded)
        val outerSigAlg = ASN1Sequence.getInstance(caSeq.getObjectAt(1))
        assertEquals(1, outerSigAlg.size())

        // Perform a real loopback TLS handshake verifying endpoint identification against 127.0.0.1.
        val ssf = manager.buildServerSocketFactory()
        val serverSocket = ssf.createServerSocket(0, 4, InetAddress.getByName("127.0.0.1")) as SSLServerSocket

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("haven-local-ca", caCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        val clientContext = SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, null)
        }

        var serverError: Throwable? = null
        val acceptor = thread {
            try {
                serverSocket.use { ss ->
                    (ss.accept() as SSLSocket).use { client ->
                        val line = client.inputStream.bufferedReader().readLine()
                        assertEquals("PING", line)
                        client.outputStream.write("PONG\n".toByteArray())
                        client.outputStream.flush()
                    }
                }
            } catch (t: Throwable) {
                serverError = t
            }
        }

        var peerChain: List<X509Certificate> = emptyList()
        (clientContext.socketFactory.createSocket("127.0.0.1", serverSocket.localPort) as SSLSocket).use { sock ->
            sock.sslParameters = SSLParameters().apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            sock.startHandshake()
            peerChain = sock.session.peerCertificates.map { it as X509Certificate }
            sock.outputStream.write("PING\n".toByteArray())
            sock.outputStream.flush()
            assertEquals("PONG", sock.inputStream.bufferedReader().readLine())
        }
        acceptor.join(5_000)
        serverError?.let { throw it }

        val leaf = peerChain.first()
        assertEquals(-1, leaf.basicConstraints)
        val validityDays = (leaf.notAfter.time - leaf.notBefore.time) / (24L * 60L * 60L * 1000L)
        assertTrue("Leaf validity ($validityDays days) must stay <= 398 days", validityDays in 360..370)

        // Explicit PKIX CertPathValidator check anchored solely on the exported CA cert.
        val certPath = cf.generateCertPath(listOf(leaf))
        val params = PKIXParameters(setOf(TrustAnchor(caCert, null))).apply {
            isRevocationEnabled = false
            date = java.util.Date(now)
        }
        CertPathValidator.getInstance("PKIX").validate(certPath, params)
    }

    @Test
    fun `leaf certificate renews automatically before expiry while preserving Root CA`() {
        var now = 1_790_000_000_000L
        val dir = tempFolder.newFolder("tls-renew")
        val manager = McpLocalTlsManager(dir, xorProtector) { now }

        val firstSummary = manager.currentSummary()
        assertEquals(McpLocalTlsManager.Mode.LOCAL_CA, firstSummary.mode)

        // Advance 350 days (within the 30-day renewal window before 364-day leaf expiry).
        now += 350L * 24L * 60L * 60L * 1000L
        val renewedSummary = manager.currentSummary()

        assertEquals(
            "Root CA fingerprint must remain unchanged so user does not reinstall CA",
            firstSummary.caSha256Fingerprint,
            renewedSummary.caSha256Fingerprint,
        )
        assertNotEquals(
            "Expiring leaf must be replaced automatically",
            firstSummary.leafSha256Fingerprint,
            renewedSummary.leafSha256Fingerprint,
        )
        assertTrue(renewedSummary.leafNotAfterMillis > firstSummary.leafNotAfterMillis)
    }

    @Test
    fun `custom PKCS12 import overrides local CA and clearCustomPkcs12 restores it`() {
        val now = 1_790_000_000_000L
        // Mint a separate PKCS#12 bundle using a second manager directory to simulate an external CA's .p12.
        val externalDir = tempFolder.newFolder("external")
        val externalMgr = McpLocalTlsManager(externalDir, xorProtector) { now }
        externalMgr.buildServerSocketFactory()
        val externalLeafEnc = java.io.File(externalDir, "local_leaf.p12.enc").readBytes()
        val externalRawP12 = xorProtector.decrypt(externalLeafEnc)
        // Re-save with a user-chosen password.
        val externalKs = KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(externalRawP12), "haven-mcp-internal".toCharArray())
        }
        val key = externalKs.getKey("mcp-tls", "haven-mcp-internal".toCharArray())
        val chain = externalKs.getCertificateChain("mcp-tls")
        val userP12 = ByteArrayOutputStream().also { out ->
            KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("my-server", key, "secret-pass".toCharArray(), chain)
                store(out, "secret-pass".toCharArray())
            }
        }.toByteArray()

        val targetMgr = McpLocalTlsManager(tempFolder.newFolder("target"), xorProtector) { now }
        val localSummary = targetMgr.currentSummary()
        assertFalse(targetMgr.hasCustomPkcs12())

        val imported = targetMgr.importCustomPkcs12(userP12, "secret-pass".toCharArray())
        assertTrue(targetMgr.hasCustomPkcs12())
        assertEquals(McpLocalTlsManager.Mode.CUSTOM_PKCS12, imported.mode)
        assertNotEquals(localSummary.leafSha256Fingerprint, imported.leafSha256Fingerprint)

        val restored = targetMgr.clearCustomPkcs12()
        assertFalse(targetMgr.hasCustomPkcs12())
        assertEquals(McpLocalTlsManager.Mode.LOCAL_CA, restored.mode)
        assertEquals(localSummary.caSha256Fingerprint, restored.caSha256Fingerprint)
    }
}
