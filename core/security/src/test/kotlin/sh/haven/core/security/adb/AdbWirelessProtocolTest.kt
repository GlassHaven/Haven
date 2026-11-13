package sh.haven.core.security.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.haven.core.security.McpLocalTlsManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

class AdbWirelessProtocolTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val xorProtector = object : McpLocalTlsManager.ByteProtector {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { i -> (plain[i].toInt() xor 0x5A).toByte() }

        override fun decrypt(cipher: ByteArray): ByteArray =
            ByteArray(cipher.size) { i -> (cipher[i].toInt() xor 0x5A).toByte() }
    }

    @Test
    fun `spake2 client and server derive identical key and cipher seals PeerInfo`() {
        val password = "482917".toByteArray(Charsets.US_ASCII) + ByteArray(64) { (it * 3 + 7).toByte() }
        val alice = AdbSpake2(AdbSpake2.Role.CLIENT)
        val bob = AdbSpake2(AdbSpake2.Role.SERVER)

        val aliceMsg = alice.generateMessage(password)
        val bobMsg = bob.generateMessage(password)
        assertEquals(32, aliceMsg.size)
        assertEquals(32, bobMsg.size)

        val aliceKey = alice.processMessage(bobMsg)
        val bobKey = bob.processMessage(aliceMsg)
        assertEquals(64, aliceKey.size)
        assertArrayEquals(aliceKey, bobKey)

        val aliceCipher = AdbPairingCipher(aliceKey)
        val bobCipher = AdbPairingCipher(bobKey)
        val peerPayload = AdbPairingFraming.encodePeerInfo(
            AdbPairingFraming.PEER_TYPE_RSA_PUB_KEY,
            "QAAAATestKey haven@android",
        )
        val sealed = aliceCipher.encrypt(peerPayload)
        val opened = bobCipher.decrypt(sealed)
        val (type, decoded) = AdbPairingFraming.decodePeerInfo(opened)
        assertEquals(AdbPairingFraming.PEER_TYPE_RSA_PUB_KEY, type)
        assertEquals("QAAAATestKey haven@android", decoded)
    }

    @Test
    fun `spake2 wrong code produces distinct key and fails AES-GCM authentication`() {
        val rightPass = "123456".toByteArray() + ByteArray(64) { 0x11 }
        val wrongPass = "654321".toByteArray() + ByteArray(64) { 0x11 }

        val alice = AdbSpake2(AdbSpake2.Role.CLIENT)
        val bob = AdbSpake2(AdbSpake2.Role.SERVER)
        val aliceMsg = alice.generateMessage(rightPass)
        val bobMsg = bob.generateMessage(wrongPass)

        val aliceKey = alice.processMessage(bobMsg)
        val bobKey = bob.processMessage(aliceMsg)

        val aliceCipher = AdbPairingCipher(aliceKey)
        val bobCipher = AdbPairingCipher(bobKey)
        val ct = aliceCipher.encrypt("hello".toByteArray())
        try {
            bobCipher.decrypt(ct)
            fail("Expected AEADBadTagException on wrong pairing code")
        } catch (_: AEADBadTagException) {
            // Expected.
        }
    }

    @Test
    fun `keyPairStore persists RSA key and encodes 524-byte Android RSAPublicKey struct`() {
        val dir = tempFolder.newFolder("adb-keys")
        val store1 = AdbKeyPairStore(dir, xorProtector)
        val kp1 = store1.getOrCreateKeyPair()
        val line1 = store1.adbPublicKeyLine()

        val store2 = AdbKeyPairStore(dir, xorProtector)
        val kp2 = store2.getOrCreateKeyPair()
        assertEquals(kp1.public, kp2.public)
        assertEquals(line1, store2.adbPublicKeyLine())

        val parts = line1.split(' ')
        assertEquals(2, parts.size)
        assertEquals("haven@android", parts[1])

        val raw = Base64.getDecoder().decode(parts[0])
        assertEquals(524, raw.size)

        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, bb.int) // 2048 / 32 words
        val n0inv = Integer.toUnsignedLong(bb.int)

        val modLe = ByteArray(256).also(bb::get)
        val rrLe = ByteArray(256).also(bb::get)
        val exp = bb.int
        assertEquals(65537, exp)

        val rsaPub = kp1.public as RSAPublicKey
        val n = rsaPub.modulus
        assertEquals(n, fromLittleEndianUnsigned(modLe))

        // Verify Montgomery invariant: n * n0inv ≡ -1 (mod 2^32) -> (n * n0inv + 1) mod 2^32 == 0
        val r32 = BigInteger.ONE.shiftLeft(32)
        val check = n.mod(r32).multiply(BigInteger.valueOf(n0inv)).add(BigInteger.ONE).mod(r32)
        assertEquals(BigInteger.ZERO, check)

        // Verify R^2 mod n == (2^2048)^2 mod n
        val expectedRr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.TWO, n)
        assertEquals(expectedRr, fromLittleEndianUnsigned(rrLe))
    }

    @Test
    fun `pairingClient completes TLS 1_3 SPAKE2 exchange and rejects wrong code`() {
        val dir = tempFolder.newFolder("adb-pair")
        val keyStore = AdbKeyPairStore(dir, xorProtector)
        val expectedPubKey = keyStore.adbPublicKeyLine()

        // Deterministic session-bound test exporter derived from both endpoints' TLS leaf certs
        val testExporter = AdbPairingClient.TlsKeyMaterialExporter { sock, label, len ->
            assertEquals(AdbSpake2.EXPORT_KEY_LABEL, label)
            val localCert = sock.session.localCertificates!!.first().encoded
            val peerCert = sock.session.peerCertificates.first().encoded
            // Order-independent symmetrical binding (clientCert || serverCert):
            val (clientBytes, serverBytes) = if (sock.useClientMode) {
                localCert to peerCert
            } else {
                peerCert to localCert
            }
            val md = MessageDigest.getInstance("SHA-512")
            md.update(label.toByteArray(Charsets.US_ASCII))
            md.update(clientBytes)
            md.update(serverBytes)
            md.digest().copyOf(len)
        }

        val serverSsl = AdbKeyPairStore.createEphemeralPairingSslContext()
        val serverSock = (serverSsl.serverSocketFactory.createServerSocket(
            0,
            4,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3")
            needClientAuth = true
        }

        val receivedKey = AtomicReference<String>()
        val serverThread = Thread {
            repeat(2) { attempt ->
                serverSock.accept().use { raw ->
                    val tls = raw as SSLSocket
                    tls.soTimeout = 5_000
                    tls.startHandshake()
                    val exported = testExporter.export(tls, AdbSpake2.EXPORT_KEY_LABEL, 64)!!
                    val serverCode = "814209".toByteArray(Charsets.US_ASCII)
                    val password = serverCode + exported

                    val spake2 = AdbSpake2(AdbSpake2.Role.SERVER)
                    val srvMsg = spake2.generateMessage(password)
                    val input = DataInputStream(tls.inputStream)
                    val output = DataOutputStream(tls.outputStream)

                    val cliMsg = AdbPairingFraming.readPacket(input, AdbPairingFraming.PACKET_TYPE_SPAKE2_MSG)
                    AdbPairingFraming.writePacket(output, AdbPairingFraming.PACKET_TYPE_SPAKE2_MSG, srvMsg)

                    val shared = spake2.processMessage(cliMsg)
                    val cipher = AdbPairingCipher(shared)
                    val encPeer = AdbPairingFraming.readPacket(input, AdbPairingFraming.PACKET_TYPE_PEER_INFO)
                    try {
                        val dec = cipher.decrypt(encPeer)
                        val (t, pub) = AdbPairingFraming.decodePeerInfo(dec)
                        assertEquals(AdbPairingFraming.PEER_TYPE_RSA_PUB_KEY, t)
                        receivedKey.set(pub)
                        val reply = AdbPairingFraming.encodePeerInfo(
                            AdbPairingFraming.PEER_TYPE_DEVICE_GUID,
                            "adb-device-guid-$attempt",
                        )
                        AdbPairingFraming.writePacket(output, AdbPairingFraming.PACKET_TYPE_PEER_INFO, cipher.encrypt(reply))
                    } catch (_: Exception) {
                        // Wrong code on attempt 1: close connection just like adbd.
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        serverSock.use {
            val client = AdbPairingClient(
                keyPairStore = keyStore,
                exporter = testExporter,
            )
            val ok = client.pair("127.0.0.1", serverSock.localPort, "814209")
            assertEquals("adb-device-guid-0", ok.deviceGuid)
            assertEquals(expectedPubKey, receivedKey.get())

            try {
                client.pair("127.0.0.1", serverSock.localPort, "000000")
                fail("Expected IOException when pairing code is wrong")
            } catch (_: IOException) {
                // Expected.
            }
            serverThread.join(5_000)
        }
    }

    @Test
    fun `adbTlsBridge upgrades A_STLS with RSA client cert even with synthetic AdbKey CA issuer and survives port rotation`() {
        val dir = tempFolder.newFolder("adb-bridge")
        val keyStore = AdbKeyPairStore(dir, xorProtector)
        val expectedRsaPub = keyStore.getOrCreateKeyPair().public

        val presentedClientKeys = mutableListOf<ByteArray>()

        fun startFakeAdbd(deviceBanner: String): Pair<ServerSocket, Thread> {
            val srv = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            val serverSsl = createFakeAdbdSslContext(presentedClientKeys)
            val t = Thread {
                srv.accept().use { raw ->
                    val rawIn = DataInputStream(raw.getInputStream())
                    val rawOut = DataOutputStream(raw.getOutputStream())

                    // Expect client A_CNXN
                    val cnxnHdr = ByteArray(24).also(rawIn::readFully)
                    val bb = ByteBuffer.wrap(cnxnHdr).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals(AdbTlsBridge.CMD_CNXN, bb.getInt(0))
                    val cnxnPayload = ByteArray(bb.getInt(12)).also(rawIn::readFully)
                    assertTrue(String(cnxnPayload).startsWith("host::"))

                    // Reply A_STLS
                    val stlsHdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(AdbTlsBridge.CMD_STLS)
                        .putInt(AdbTlsBridge.STLS_VERSION_1_0)
                        .putInt(0)
                        .putInt(0)
                        .putInt(0)
                        .putInt(AdbTlsBridge.CMD_STLS xor -1)
                        .array()
                    rawOut.write(stlsHdr)
                    rawOut.flush()

                    // Read client A_STLS reply
                    val cliStls = ByteArray(24).also(rawIn::readFully)
                    assertEquals(
                        AdbTlsBridge.CMD_STLS,
                        ByteBuffer.wrap(cliStls).order(ByteOrder.LITTLE_ENDIAN).getInt(0),
                    )

                    // Upgrade to TLS 1.3 server requiring client cert with synthetic O=AdbKey-0 CA DN!
                    val tls = (serverSsl.socketFactory.createSocket(
                        raw,
                        "127.0.0.1",
                        raw.port,
                        true,
                    ) as SSLSocket).apply {
                        useClientMode = false
                        enabledProtocols = arrayOf("TLSv1.3")
                        needClientAuth = true
                    }
                    tls.startHandshake()

                    // Immediately send A_CNXN (device::...) over TLS without waiting for a second client A_CNXN!
                    val bannerBytes = deviceBanner.toByteArray(Charsets.UTF_8)
                    val replyHdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(AdbTlsBridge.CMD_CNXN)
                        .putInt(0x01000001)
                        .putInt(1048576)
                        .putInt(bannerBytes.size)
                        .putInt(bannerBytes.sumOf { it.toInt() and 0xff })
                        .putInt(AdbTlsBridge.CMD_CNXN xor -1)
                        .array()
                    tls.outputStream.write(replyHdr)
                    tls.outputStream.write(bannerBytes)
                    tls.outputStream.flush()
                    // Wait for client close
                    runCatching { tls.inputStream.read() }
                }
            }.apply { isDaemon = true; start() }
            return srv to t
        }

        val (adbd1, t1) = startFakeAdbd("device::ro.product.name=haven1")
        val (adbd2, t2) = startFakeAdbd("device::ro.product.name=haven2")
        val currentUpstreamPort = AtomicInteger(adbd1.localPort)

        val bridge = AdbTlsBridge(
            keyPairStore = keyStore,
            resolveConnectEndpoint = {
                AdbTlsBridge.ConnectEndpoint("127.0.0.1", currentUpstreamPort.get())
            },
        )
        try {
            val bridgePort = bridge.start(0)

            // Connection 1 -> hits adbd1
            assertEquals("device::ro.product.name=haven1", performPlainAdbConnect(bridgePort))
            t1.join(5_000)
            adbd1.close()

            // Simulate Wireless Debugging port rotation -> Connection 2 hits adbd2 on the same bridgePort!
            currentUpstreamPort.set(adbd2.localPort)
            assertEquals("device::ro.product.name=haven2", performPlainAdbConnect(bridgePort))
            t2.join(5_000)
            adbd2.close()

            assertEquals(2, presentedClientKeys.size)
            assertArrayEquals(expectedRsaPub.encoded, presentedClientKeys[0])
            assertArrayEquals(expectedRsaPub.encoded, presentedClientKeys[1])
        } finally {
            bridge.stop()
            runCatching { adbd1.close() }
            runCatching { adbd2.close() }
        }
    }

    private fun performPlainAdbConnect(port: Int): String {
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5_000
            val payload = "host::features=shell_v2,cmd".toByteArray(Charsets.UTF_8)
            val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(AdbTlsBridge.CMD_CNXN)
                .putInt(0x01000001)
                .putInt(1048576)
                .putInt(payload.size)
                .putInt(payload.sumOf { it.toInt() and 0xff })
                .putInt(AdbTlsBridge.CMD_CNXN xor -1)
                .array()
            sock.getOutputStream().write(hdr)
            sock.getOutputStream().write(payload)
            sock.getOutputStream().flush()

            val din = DataInputStream(sock.getInputStream())
            val replyHdr = ByteArray(24).also(din::readFully)
            val bb = ByteBuffer.wrap(replyHdr).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(AdbTlsBridge.CMD_CNXN, bb.getInt(0))
            val replyPayload = ByteArray(bb.getInt(12)).also(din::readFully)
            return String(replyPayload, Charsets.UTF_8)
        }
    }

    private fun createFakeAdbdSslContext(presentedClientKeys: MutableList<ByteArray>): SSLContext {
        val base = AdbKeyPairStore.createEphemeralPairingSslContext()
        // Build a synthetic X509Certificate issuer (`O=AdbKey-0,CN=deadbeef`) so TLS 1.3
        // CertificateRequest advertises non-matching certificate_authorities DNs like real adbd!
        val syntheticCaCert = object : X509Certificate() {
            private val dn = X500Principal("O=AdbKey-0,CN=0123456789abcdef")
            override fun getSubjectX500Principal(): X500Principal = dn
            override fun getIssuerX500Principal(): X500Principal = dn
            override fun getSubjectDN(): Principal = dn
            override fun getIssuerDN(): Principal = dn
            override fun checkValidity() = Unit
            override fun checkValidity(date: java.util.Date?) = Unit
            override fun getVersion(): Int = 3
            override fun getSerialNumber(): BigInteger = BigInteger.ONE
            override fun getNotBefore(): java.util.Date = java.util.Date(0)
            override fun getNotAfter(): java.util.Date = java.util.Date(Long.MAX_VALUE)
            override fun getTBSCertificate(): ByteArray = ByteArray(0)
            override fun getSignature(): ByteArray = ByteArray(0)
            override fun getSigAlgName(): String = "SHA256withECDSA"
            override fun getSigAlgOID(): String = "1.2.840.10045.4.3.2"
            override fun getSigAlgParams(): ByteArray? = null
            override fun getIssuerUniqueID(): BooleanArray? = null
            override fun getSubjectUniqueID(): BooleanArray? = null
            override fun getKeyUsage(): BooleanArray? = null
            override fun getBasicConstraints(): Int = -1
            override fun getEncoded(): ByteArray = ByteArray(0)
            override fun verify(key: java.security.PublicKey?) = Unit
            override fun verify(key: java.security.PublicKey?, sigProvider: String?) = Unit
            override fun toString(): String = "SyntheticAdbCa"
            override fun getPublicKey(): java.security.PublicKey = throw UnsupportedOperationException()
            override fun hasUnsupportedCriticalExtension(): Boolean = false
            override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
            override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
            override fun getExtensionValue(oid: String?): ByteArray? = null
        }

        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = requireNotNull(chain?.firstOrNull()) { "Client did not present certificate" }
                synchronized(presentedClientKeys) {
                    presentedClientKeys.add(leaf.publicKey.encoded)
                }
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(syntheticCaCert)
        }

        // Extract the server key manager from an ephemeral store
        val serverStore = AdbKeyPairStore(tempFolder.newFolder(), xorProtector)
        val kmField = serverStore.createConnectSslContext()
        // Simpler: create an SSLContext with our custom TrustManager and an RSA KeyPair from serverStore
        return SSLContext.getInstance("TLSv1.3").apply {
            // Reuse FixedClientKeyManager via serverStore's createConnectSslContext? No, init needs KeyManager[]:
            val kp = serverStore.getOrCreateKeyPair()
            val cert = generateTestCert(kp)
            val km = object : javax.net.ssl.X509ExtendedKeyManager() {
                override fun chooseClientAlias(k: Array<out String>?, i: Array<out Principal>?, s: Socket?) = "srv"
                override fun chooseEngineClientAlias(k: Array<out String>?, i: Array<out Principal>?, e: javax.net.ssl.SSLEngine?) = "srv"
                override fun chooseServerAlias(k: String?, i: Array<out Principal>?, s: Socket?) = "srv"
                override fun chooseEngineServerAlias(k: String?, i: Array<out Principal>?, e: javax.net.ssl.SSLEngine?) = "srv"
                override fun getCertificateChain(a: String?) = arrayOf(cert)
                override fun getPrivateKey(a: String?) = kp.private
                override fun getClientAliases(k: String?, i: Array<out Principal>?) = arrayOf("srv")
                override fun getServerAliases(k: String?, i: Array<out Principal>?) = arrayOf("srv")
            }
            init(arrayOf(km), arrayOf(tm), SecureRandom())
        }
    }

    private fun generateTestCert(kp: java.security.KeyPair): X509Certificate {
        // Obtain the self-signed cert from a temporary TLS handshake or BouncyCastle V3TBSCertificateGenerator:
        val now = java.util.Date()
        val sigAlg = org.bouncycastle.asn1.x509.AlgorithmIdentifier(
            org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.sha256WithRSAEncryption,
            org.bouncycastle.asn1.DERNull.INSTANCE,
        )
        val dn = org.bouncycastle.asn1.x500.X500Name("CN=adbd")
        val tbs = org.bouncycastle.asn1.x509.V3TBSCertificateGenerator().apply {
            setSerialNumber(org.bouncycastle.asn1.ASN1Integer(1L))
            setSignature(sigAlg)
            setIssuer(dn)
            setStartDate(org.bouncycastle.asn1.x509.Time(now))
            setEndDate(org.bouncycastle.asn1.x509.Time(java.util.Date(now.time + 86400_000L)))
            setSubject(dn)
            setSubjectPublicKeyInfo(org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(kp.public.encoded))
        }.generateTBSCertificate()
        val sig = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(kp.private)
            update(tbs.encoded)
            sign()
        }
        val der = org.bouncycastle.asn1.DERSequence(
            arrayOf(tbs, sigAlg, org.bouncycastle.asn1.DERBitString(sig)),
        ).encoded
        return java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(java.io.ByteArrayInputStream(der)) as X509Certificate
    }

    private fun fromLittleEndianUnsigned(le: ByteArray): BigInteger {
        val be = ByteArray(le.size + 1)
        for (i in le.indices) be[le.size - i] = le[i]
        return BigInteger(be)
    }
}
