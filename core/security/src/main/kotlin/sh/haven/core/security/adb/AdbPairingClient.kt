package sh.haven.core.security.adb

import android.net.ssl.SSLSockets
import android.os.Build
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Executes Android 11+ (`API 30+`) Wireless Debugging pairing in-app (#575 v2)
 * against an `_adb-tls-pairing._tcp` listener using TLS 1.3 key-material export
 * (`SSLSockets.exportKeyingMaterial`) + BoringSSL `spake25519` + AES-128-GCM.
 */
class AdbPairingClient(
    private val keyPairStore: AdbKeyPairStore,
    private val sslContextFactory: () -> SSLContext = AdbKeyPairStore::createEphemeralPairingSslContext,
    private val exporter: TlsKeyMaterialExporter = PlatformTlsKeyMaterialExporter,
) {

    fun interface TlsKeyMaterialExporter {
        @Throws(IOException::class)
        fun export(socket: SSLSocket, label: String, length: Int): ByteArray?
    }

    data class PairingResult(
        val deviceGuid: String,
    )

    object PlatformTlsKeyMaterialExporter : TlsKeyMaterialExporter {
        override fun export(socket: SSLSocket, label: String, length: Int): ByteArray? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return SSLSockets.exportKeyingMaterial(socket, label, null, length)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return try {
                    val conscrypt = Class.forName("com.android.org.conscrypt.Conscrypt")
                    val method = conscrypt.getMethod(
                        "exportKeyingMaterial",
                        SSLSocket::class.java,
                        String::class.java,
                        ByteArray::class.java,
                        Int::class.javaPrimitiveType,
                    )
                    method.invoke(null, socket, label, null, length) as? ByteArray
                } catch (t: Throwable) {
                    throw IOException(
                        "In-app ADB wireless pairing requires Android 12 (API 31) or newer on this device",
                        t,
                    )
                }
            }
            throw IOException("In-app ADB wireless pairing requires Android 11 (API 30) or newer")
        }
    }

    @Throws(IOException::class)
    fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        publicKeyLine: String = keyPairStore.adbPublicKeyLine(),
        timeoutMs: Int = 15_000,
    ): PairingResult {
        val trimmedCode = pairingCode.trim()
        require(trimmedCode.isNotEmpty()) { "Pairing code must not be blank" }

        val sslContext = sslContextFactory()
        val rawSocket = Socket()
        try {
            rawSocket.soTimeout = timeoutMs
            rawSocket.connect(InetSocketAddress(host, port), timeoutMs)
            val sslSocket = (sslContext.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket).apply {
                soTimeout = timeoutMs
                enabledProtocols = arrayOf("TLSv1.3")
            }
            sslSocket.use { tls ->
                tls.startHandshake()

                val exported = exporter.export(tls, AdbSpake2.EXPORT_KEY_LABEL, AdbSpake2.EXPORTED_KEY_SIZE)
                    ?: throw IOException("TLS 1.3 key-material export returned null")
                if (exported.size != AdbSpake2.EXPORTED_KEY_SIZE) {
                    throw IOException("Expected ${AdbSpake2.EXPORTED_KEY_SIZE}-byte exported key material, got ${exported.size}")
                }

                val codeBytes = trimmedCode.toByteArray(Charsets.US_ASCII)
                val password = ByteArray(codeBytes.size + exported.size)
                System.arraycopy(codeBytes, 0, password, 0, codeBytes.size)
                System.arraycopy(exported, 0, password, codeBytes.size, exported.size)

                val spake2 = AdbSpake2(AdbSpake2.Role.CLIENT)
                val clientMsg = spake2.generateMessage(password)

                val input = DataInputStream(tls.inputStream)
                val output = DataOutputStream(tls.outputStream)

                AdbPairingFraming.writePacket(output, AdbPairingFraming.PACKET_TYPE_SPAKE2_MSG, clientMsg)
                val serverMsg = AdbPairingFraming.readPacket(input, AdbPairingFraming.PACKET_TYPE_SPAKE2_MSG)
                val sharedKey = try {
                    spake2.processMessage(serverMsg)
                } catch (e: Exception) {
                    throw IOException("SPAKE2 peer point validation failed: ${e.message}", e)
                }

                val cipher = AdbPairingCipher(sharedKey)
                val myPeerInfo = AdbPairingFraming.encodePeerInfo(
                    AdbPairingFraming.PEER_TYPE_RSA_PUB_KEY,
                    publicKeyLine,
                )
                AdbPairingFraming.writePacket(
                    output,
                    AdbPairingFraming.PACKET_TYPE_PEER_INFO,
                    cipher.encrypt(myPeerInfo),
                )

                val encryptedServerInfo = AdbPairingFraming.readPacket(
                    input,
                    AdbPairingFraming.PACKET_TYPE_PEER_INFO,
                )
                val decryptedServerInfo = try {
                    cipher.decrypt(encryptedServerInfo)
                } catch (e: Exception) {
                    throw IOException("ADB pairing authentication failed (wrong 6-digit code or expired dialog)", e)
                }
                val (peerType, peerValue) = AdbPairingFraming.decodePeerInfo(decryptedServerInfo)
                if (peerType != AdbPairingFraming.PEER_TYPE_DEVICE_GUID) {
                    throw IOException("Unexpected PeerInfo type $peerType from pairing server")
                }
                return PairingResult(deviceGuid = peerValue)
            }
        } catch (e: IOException) {
            runCatching { rawSocket.close() }
            throw e
        } catch (e: Exception) {
            runCatching { rawSocket.close() }
            throw IOException("ADB wireless pairing failed: ${e.message}", e)
        }
    }
}
