package sh.haven.core.security.adb

import android.annotation.SuppressLint
import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import sh.haven.core.security.CredentialEncryption
import sh.haven.core.security.McpLocalTlsManager
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Manages Haven's persistent 2048-bit RSA ADB identity (`adbkey` / `adbkey.pub`)
 * and TLS 1.3 contexts for Wireless Debugging pairing and `A_STLS` transport (#575 v2).
 *
 * - Private key material is Tink/AndroidKeyStore AEAD-encrypted at rest.
 * - [adbPublicKeyLine] formats the 524-byte Android `RSAPublicKey` binary struct
 *   (`modulus_size_words = 64`, `-n^-1 mod 2^32`, `modulus[256]` LE, `2^4096 mod n` LE,
 *   `exponent` LE) matching `adb pubkey` byte-for-byte.
 * - [createConnectSslContext] installs an unconditional [X509ExtendedKeyManager] that
 *   always presents Haven's RSA certificate even when `adbd` sends synthetic
 *   `O=AdbKey-0` `certificate_authorities` DNs in TLS 1.3 `CertificateRequest`.
 */
@Singleton
class AdbKeyPairStore internal constructor(
    private val storageDirProvider: () -> File,
    private val protector: McpLocalTlsManager.ByteProtector,
) {

    constructor(
        storageDir: File,
        protector: McpLocalTlsManager.ByteProtector,
    ) : this(
        storageDirProvider = { storageDir },
        protector = protector,
    )

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        storageDirProvider = { File(context.noBackupFilesDir, "adb-wireless") },
        protector = TinkAdbKeyProtector(context),
    )

    private val lock = Any()

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    @Volatile
    private var cachedCert: X509Certificate? = null

    fun getOrCreateKeyPair(): KeyPair {
        cachedKeyPair?.let { return it }
        synchronized(lock) {
            cachedKeyPair?.let { return it }
            val dir = storageDirProvider().apply { mkdirs() }
            val privFile = File(dir, "adbkey.pkcs8.enc")
            val pubFile = File(dir, "adbkey.spki")

            val loaded = if (privFile.isFile && pubFile.isFile) {
                runCatching {
                    val kf = KeyFactory.getInstance("RSA")
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(protector.decrypt(privFile.readBytes())))
                    val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                    KeyPair(pub, priv)
                }.getOrNull()
            } else {
                null
            }

            val kp = loaded ?: run {
                val gen = KeyPairGenerator.getInstance("RSA")
                gen.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4), SecureRandom())
                gen.generateKeyPair().also { fresh ->
                    privFile.writeBytes(protector.encrypt(fresh.private.encoded))
                    pubFile.writeBytes(fresh.public.encoded)
                }
            }
            cachedKeyPair = kp
            return kp
        }
    }

    fun adbPublicKeyLine(comment: String = DEFAULT_KEY_COMMENT): String {
        val rsaPub = getOrCreateKeyPair().public as RSAPublicKey
        val structBytes = encodeAndroidRsaPublicKey(rsaPub)
        val b64 = Base64.getEncoder().encodeToString(structBytes)
        return if (comment.isBlank()) b64 else "$b64 $comment"
    }

    fun createConnectSslContext(): SSLContext {
        val kp = getOrCreateKeyPair()
        val cert = cachedCert ?: synchronized(lock) {
            cachedCert ?: generateSelfSignedCert(
                keyPair = kp,
                sigAlgName = "SHA256withRSA",
                sigAlgId = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE),
            ).also { cachedCert = it }
        }
        val keyManager = FixedClientKeyManager(kp.private, cert)
        return SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf<KeyManager>(keyManager), TRUST_ALL_MANAGERS, SecureRandom())
        }
    }

    private class FixedClientKeyManager(
        private val privateKey: PrivateKey,
        private val certificate: X509Certificate,
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = ALIAS
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String = ALIAS
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == ALIAS) arrayOf(certificate) else null
        override fun getPrivateKey(alias: String?): PrivateKey? =
            if (alias == ALIAS) privateKey else null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)
    }

    private class TinkAdbKeyProtector(context: Context) : McpLocalTlsManager.ByteProtector {
        private val appContext = context.applicationContext

        @Volatile
        private var cachedAead: Aead? = null

        private fun aead(): Aead {
            cachedAead?.let { return it }
            synchronized(this) {
                cachedAead?.let { return it }
                AeadConfig.register()
                return try {
                    buildAead().also { cachedAead = it }
                } catch (e: Exception) {
                    if (CredentialEncryption.classifyFailure(e) == CredentialEncryption.Failure.PERMANENT) {
                        resetBrokenKeyset()
                        buildAead().also { cachedAead = it }
                    } else {
                        throw e
                    }
                }
            }
        }

        private fun buildAead(): Aead =
            AndroidKeysetManager.Builder()
                .withSharedPref(appContext, KEYSET_NAME, PREFERENCE_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)

        private fun resetBrokenKeyset() {
            runCatching {
                appContext.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEYSET_NAME)
                    .commit()
            }
            runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(MASTER_KEY_ALIAS)) ks.deleteEntry(MASTER_KEY_ALIAS)
            }
        }

        override fun encrypt(plain: ByteArray): ByteArray = aead().encrypt(plain, ASSOCIATED_DATA)
        override fun decrypt(cipher: ByteArray): ByteArray = aead().decrypt(cipher, ASSOCIATED_DATA)

        companion object {
            private const val KEYSET_NAME = "haven_adb_wireless_keyset"
            private const val PREFERENCE_FILE = "haven_adb_wireless_keyset_prefs"
            private const val MASTER_KEY_ALIAS = "haven_adb_wireless_master"
            private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"
            private val ASSOCIATED_DATA = "haven-adb-wireless".toByteArray(Charsets.UTF_8)
        }
    }

    companion object {
        const val DEFAULT_KEY_COMMENT = "haven@android"
        const val ANDROID_RSA_PUBKEY_STRUCT_BYTES = 524
        private const val ALIAS = "adb"
        private const val MODULUS_BYTES = 256
        private const val MODULUS_WORDS = MODULUS_BYTES / 4
        private val R32: BigInteger = BigInteger.ONE.shiftLeft(32)
        private val R2048: BigInteger = BigInteger.ONE.shiftLeft(2048)

        // AOSP adbd's _adb-tls-pairing._tcp and _adb-tls-connect._tcp listeners present
        // unauthenticated ephemeral self-signed certs; mutual authentication comes from
        // SPAKE2+ over exported TLS 1.3 key material (pairing) and the RSA client cert (connect).
        @SuppressLint("CustomX509TrustManager")
        internal val TRUST_ALL_MANAGERS: Array<TrustManager> = arrayOf(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            },
        )

        fun createEphemeralPairingSslContext(): SSLContext {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val kp = kpg.generateKeyPair()
            val cert = generateSelfSignedCert(
                keyPair = kp,
                sigAlgName = "SHA256withECDSA",
                sigAlgId = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256),
            )
            val km = FixedClientKeyManager(kp.private, cert)
            return SSLContext.getInstance("TLSv1.3").apply {
                init(arrayOf<KeyManager>(km), TRUST_ALL_MANAGERS, SecureRandom())
            }
        }

        fun encodeAndroidRsaPublicKey(pub: RSAPublicKey): ByteArray {
            val n = pub.modulus
            require(n.bitLength() == 2048) { "ADB requires a 2048-bit RSA modulus, got ${n.bitLength()}" }
            val n0inv = R32.subtract(n.mod(R32).modInverse(R32)).mod(R32).toLong()
            val rr = R2048.modPow(BigInteger.valueOf(2L), n)

            val buf = ByteBuffer.allocate(ANDROID_RSA_PUBKEY_STRUCT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(MODULUS_WORDS)
            buf.putInt(n0inv.toInt())
            buf.put(toLittleEndianUnsigned(n, MODULUS_BYTES))
            buf.put(toLittleEndianUnsigned(rr, MODULUS_BYTES))
            buf.putInt(pub.publicExponent.toInt())
            return buf.array()
        }

        private fun toLittleEndianUnsigned(value: BigInteger, len: Int): ByteArray {
            val out = ByteArray(len)
            var cur = value
            for (i in 0 until len) {
                out[i] = cur.toByte()
                cur = cur.shiftRight(8)
            }
            return out
        }

        private fun generateSelfSignedCert(
            keyPair: KeyPair,
            sigAlgName: String,
            sigAlgId: AlgorithmIdentifier,
        ): X509Certificate {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 24L * 3600_000L)
            val notAfter = Date(now + 3650L * 24L * 3600_000L)
            val dn = X500Name("CN=adb,O=Haven")

            val tbsGen = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)))
                setSignature(sigAlgId)
                setIssuer(dn)
                setStartDate(Time(notBefore))
                setEndDate(Time(notAfter))
                setSubject(dn)
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keyPair.public.encoded))
            }
            val tbs = tbsGen.generateTBSCertificate()
            val sigBytes = Signature.getInstance(sigAlgName).run {
                initSign(keyPair.private)
                update(tbs.encoded)
                sign()
            }
            val certDer = DERSequence(arrayOf(tbs, sigAlgId, DERBitString(sigBytes))).encoded
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        }
    }
}
