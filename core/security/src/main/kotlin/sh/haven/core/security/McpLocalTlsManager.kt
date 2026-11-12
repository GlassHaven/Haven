package sh.haven.core.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtensionsGenerator
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * Manages TLS credentials for Haven's local HTTPS MCP listener (#735).
 *
 * Supports two credential modes:
 * 1. [Mode.LOCAL_CA] (default): generates a persistent 10-year ECDSA P-256 local
 *    root CA (`CN=Haven Local MCP Root CA, O=Haven`, `CA:TRUE`) and mints a
 *    365-day server leaf certificate (`SAN = DNS:localhost, IP:127.0.0.1, IP:::1`,
 *    `ExtendedKeyUsage = serverAuth`) that renews automatically from the same root
 *    CA before expiry. Users export the root CA `.crt` once and install it into
 *    Android's user CA store so on-device HTTPS-only MCP clients (e.g. OPPO
 *    XiaoBu Next) validate `https://127.0.0.1:8750/mcp` cleanly.
 * 2. [Mode.CUSTOM_PKCS12]: imports a user-supplied `.p12` / `.pfx` server bundle
 *    (for users who already maintain a local CA installed on the device).
 *
 * Private key material is AEAD-encrypted at rest via [ByteProtector].
 */
@Singleton
class McpLocalTlsManager internal constructor(
    storageDirProvider: () -> File,
    private val protector: ByteProtector,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {

    constructor(
        storageDir: File,
        protector: ByteProtector,
        clockMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        storageDirProvider = { storageDir },
        protector = protector,
        clockMillis = clockMillis,
    )

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        storageDirProvider = { File(context.filesDir, "mcp-tls") },
        protector = TinkMcpTlsProtector(context),
    )

    private val storageDir: File by lazy(storageDirProvider)

    interface ByteProtector {
        fun encrypt(plain: ByteArray): ByteArray
        fun decrypt(cipher: ByteArray): ByteArray
    }

    enum class Mode {
        LOCAL_CA,
        CUSTOM_PKCS12,
    }

    data class CertSummary(
        val mode: Mode,
        val subjectDn: String,
        val issuerDn: String,
        val caSha256Fingerprint: String,
        val leafSha256Fingerprint: String,
        val leafNotAfterMillis: Long,
    )

    private val lock = Any()
    private val random = SecureRandom()

    private val caFile: File get() = File(storageDir, "local_ca.p12.enc")
    private val leafFile: File get() = File(storageDir, "local_leaf.p12.enc")
    private val customFile: File get() = File(storageDir, "custom_server.p12.enc")

    /** Build an [SSLServerSocketFactory] backed by the active custom bundle or local CA leaf. */
    fun buildServerSocketFactory(): SSLServerSocketFactory = synchronized(lock) {
        val ks = loadActiveServerKeyStoreLocked()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, INTERNAL_STORE_PASSWORD)
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, random)
        }
        sslContext.serverSocketFactory
    }

    /** Export the auto-generated Haven Local MCP Root CA as PEM (`.crt`) bytes. */
    fun exportLocalCaPem(): ByteArray = synchronized(lock) {
        val (_, caCert) = loadOrCreateLocalCaLocked()
        encodePemCertificate(caCert).toByteArray(Charsets.US_ASCII)
    }

    /** Return metadata describing the currently active server TLS certificate. */
    fun currentSummary(): CertSummary = synchronized(lock) {
        val customKs = tryLoadCustomKeyStoreLocked()
        if (customKs != null) {
            return@synchronized summaryFromKeyStore(customKs, Mode.CUSTOM_PKCS12)
        }
        val leafKs = loadOrCreateLocalLeafKeyStoreLocked()
        summaryFromKeyStore(leafKs, Mode.LOCAL_CA)
    }

    /** True when a user-imported PKCS#12 server bundle is currently active. */
    fun hasCustomPkcs12(): Boolean = synchronized(lock) {
        customFile.isFile
    }

    /**
     * Validate and store a user-supplied `.p12` / `.pfx` server certificate + private key.
     * Re-packs the key and chain into a canonical encrypted PKCS#12 bundle so the caller's
     * import passphrase does not need to be stored separately.
     */
    fun importCustomPkcs12(pkcs12Bytes: ByteArray, password: CharArray): CertSummary = synchronized(lock) {
        require(pkcs12Bytes.isNotEmpty()) { "PKCS#12 file is empty" }
        val sourceKs = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(pkcs12Bytes).use { input ->
            sourceKs.load(input, password)
        }
        val alias = sourceKs.aliases().asSequence().firstOrNull { sourceKs.isKeyEntry(it) }
            ?: throw IllegalArgumentException("PKCS#12 bundle contains no private key entry")
        val privateKey = sourceKs.getKey(alias, password) as? PrivateKey
            ?: throw IllegalArgumentException("PKCS#12 entry '$alias' does not hold a PrivateKey")
        val rawChain = sourceKs.getCertificateChain(alias)
            ?.mapNotNull { it as? X509Certificate }
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("PKCS#12 entry '$alias' has no X.509 certificate chain")

        val canonicalKs = packKeyStore(privateKey, rawChain)
        // Verify KeyManagerFactory + SSLContext accept the key/cert pair before persisting.
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(canonicalKs, INTERNAL_STORE_PASSWORD)
        }
        SSLContext.getInstance("TLS").init(kmf.keyManagers, null, random)

        writeEncryptedKeyStoreLocked(customFile, canonicalKs)
        summaryFromKeyStore(canonicalKs, Mode.CUSTOM_PKCS12)
    }

    /** Remove any user-imported PKCS#12 bundle and revert to the auto-generated local CA. */
    fun clearCustomPkcs12(): CertSummary = synchronized(lock) {
        customFile.delete()
        val leafKs = loadOrCreateLocalLeafKeyStoreLocked()
        summaryFromKeyStore(leafKs, Mode.LOCAL_CA)
    }

    /** Rotate the auto-generated local Root CA and mint a fresh server leaf certificate. */
    fun regenerateLocalCa(): CertSummary = synchronized(lock) {
        caFile.delete()
        leafFile.delete()
        val leafKs = loadOrCreateLocalLeafKeyStoreLocked()
        summaryFromKeyStore(leafKs, Mode.LOCAL_CA)
    }

    private fun loadActiveServerKeyStoreLocked(): KeyStore {
        tryLoadCustomKeyStoreLocked()?.let { return it }
        return loadOrCreateLocalLeafKeyStoreLocked()
    }

    private fun tryLoadCustomKeyStoreLocked(): KeyStore? {
        if (!customFile.isFile) return null
        return runCatching { readEncryptedKeyStoreLocked(customFile) }.getOrNull()
    }

    private fun loadOrCreateLocalLeafKeyStoreLocked(): KeyStore {
        val (caKey, caCert) = loadOrCreateLocalCaLocked()
        val now = clockMillis()
        if (leafFile.isFile) {
            val existing = runCatching { readEncryptedKeyStoreLocked(leafFile) }.getOrNull()
            if (existing != null) {
                val chain = existing.getCertificateChain(ENTRY_ALIAS)?.mapNotNull { it as? X509Certificate }
                val leaf = chain?.firstOrNull()
                val caInChain = chain?.lastOrNull()
                if (leaf != null &&
                    caInChain != null &&
                    caInChain.encoded.contentEquals(caCert.encoded) &&
                    leaf.notAfter.time - now > LEAF_RENEW_BEFORE_EXPIRY_MS &&
                    leaf.notBefore.time <= now + CLOCK_SKEW_TOLERANCE_MS
                ) {
                    return existing
                }
            }
        }
        val leafKeyPair = generateEcP256KeyPair()
        val leafCert = mintServerLeafCertificate(
            caPrivateKey = caKey,
            caCert = caCert,
            leafPublicKey = leafKeyPair.public,
            nowMillis = now,
        )
        val ks = packKeyStore(leafKeyPair.private, listOf(leafCert, caCert))
        writeEncryptedKeyStoreLocked(leafFile, ks)
        return ks
    }

    private fun loadOrCreateLocalCaLocked(): Pair<PrivateKey, X509Certificate> {
        if (caFile.isFile) {
            val existing = runCatching { readEncryptedKeyStoreLocked(caFile) }.getOrNull()
            if (existing != null) {
                val key = runCatching { existing.getKey(ENTRY_ALIAS, INTERNAL_STORE_PASSWORD) as? PrivateKey }.getOrNull()
                val cert = runCatching { existing.getCertificate(ENTRY_ALIAS) as? X509Certificate }.getOrNull()
                if (key != null && cert != null && cert.notAfter.time > clockMillis()) {
                    return key to cert
                }
            }
        }
        val caKeyPair = generateEcP256KeyPair()
        val caCert = mintRootCaCertificate(caKeyPair, clockMillis())
        val ks = packKeyStore(caKeyPair.private, listOf(caCert))
        writeEncryptedKeyStoreLocked(caFile, ks)
        // Any cached leaf signed by a previous CA is now stale.
        leafFile.delete()
        return caKeyPair.private to caCert
    }

    private fun generateEcP256KeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()

    private fun mintRootCaCertificate(keyPair: KeyPair, nowMillis: Long): X509Certificate {
        val dn = X500Name(CA_SUBJECT_DN)
        val spki = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        val skiBytes = sha1(spki.publicKeyData.bytes)

        val extGen = ExtensionsGenerator().apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign),
            )
            addExtension(Extension.subjectKeyIdentifier, false, SubjectKeyIdentifier(skiBytes))
        }

        return signX509v3Certificate(
            issuer = dn,
            subject = dn,
            subjectSpki = spki,
            notBeforeMillis = nowMillis - BACKDATE_MS,
            notAfterMillis = nowMillis + CA_VALIDITY_MS,
            extensions = extGen,
            signingKey = keyPair.private,
        )
    }

    private fun mintServerLeafCertificate(
        caPrivateKey: PrivateKey,
        caCert: X509Certificate,
        leafPublicKey: java.security.PublicKey,
        nowMillis: Long,
    ): X509Certificate {
        val issuerDn = X500Name.getInstance(caCert.subjectX500Principal.encoded)
        val subjectDn = X500Name(LEAF_SUBJECT_DN)
        val leafSpki = SubjectPublicKeyInfo.getInstance(leafPublicKey.encoded)
        val caSpki = SubjectPublicKeyInfo.getInstance(caCert.publicKey.encoded)

        val sanNames = GeneralNames(
            arrayOf(
                GeneralName(GeneralName.dNSName, "localhost"),
                GeneralName(GeneralName.iPAddress, DEROctetString(byteArrayOf(127, 0, 0, 1))),
                GeneralName(GeneralName.iPAddress, DEROctetString(InetAddress.getByName("::1").address)),
            ),
        )

        val extGen = ExtensionsGenerator().apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
            )
            addExtension(Extension.subjectAlternativeName, false, sanNames)
            addExtension(
                Extension.subjectKeyIdentifier,
                false,
                SubjectKeyIdentifier(sha1(leafSpki.publicKeyData.bytes)),
            )
            addExtension(
                Extension.authorityKeyIdentifier,
                false,
                AuthorityKeyIdentifier(sha1(caSpki.publicKeyData.bytes)),
            )
        }

        return signX509v3Certificate(
            issuer = issuerDn,
            subject = subjectDn,
            subjectSpki = leafSpki,
            notBeforeMillis = nowMillis - BACKDATE_MS,
            notAfterMillis = nowMillis + LEAF_VALIDITY_MS,
            extensions = extGen,
            signingKey = caPrivateKey,
        )
    }

    private fun signX509v3Certificate(
        issuer: X500Name,
        subject: X500Name,
        subjectSpki: SubjectPublicKeyInfo,
        notBeforeMillis: Long,
        notAfterMillis: Long,
        extensions: ExtensionsGenerator,
        signingKey: PrivateKey,
    ): X509Certificate {
        // RFC 5758 §3.2: ecdsa-with-SHA256 AlgorithmIdentifier MUST omit parameters (no NULL).
        val sigAlgId = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val serial = BigInteger(128, random).max(BigInteger.ONE)

        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(serial))
            setSignature(sigAlgId)
            setIssuer(issuer)
            setStartDate(Time(Date(notBeforeMillis)))
            setEndDate(Time(Date(notAfterMillis)))
            setSubject(subject)
            setSubjectPublicKeyInfo(subjectSpki)
            setExtensions(extensions.generate())
        }.generateTBSCertificate()

        val tbsDer = tbs.getEncoded(ASN1Encoding.DER)
        val signatureBytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(signingKey, random)
            update(tbsDer)
            sign()
        }

        val certDer = DERSequence(
            ASN1EncodableVector().apply {
                add(tbs)
                add(sigAlgId)
                add(DERBitString(signatureBytes))
            },
        ).getEncoded(ASN1Encoding.DER)

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun packKeyStore(privateKey: PrivateKey, chain: List<X509Certificate>): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ENTRY_ALIAS, privateKey, INTERNAL_STORE_PASSWORD, chain.toTypedArray())
        }

    private fun writeEncryptedKeyStoreLocked(target: File, ks: KeyStore) {
        storageDir.mkdirs()
        val rawP12 = ByteArrayOutputStream().also { out ->
            ks.store(out, INTERNAL_STORE_PASSWORD)
        }.toByteArray()
        val encrypted = protector.encrypt(rawP12)
        val tmp = File(storageDir, "${target.name}.tmp")
        tmp.writeBytes(encrypted)
        if (!tmp.renameTo(target)) {
            target.writeBytes(encrypted)
            tmp.delete()
        }
    }

    private fun readEncryptedKeyStoreLocked(source: File): KeyStore {
        val decrypted = protector.decrypt(source.readBytes())
        return KeyStore.getInstance("PKCS12").apply {
            ByteArrayInputStream(decrypted).use { load(it, INTERNAL_STORE_PASSWORD) }
        }
    }

    private fun summaryFromKeyStore(ks: KeyStore, mode: Mode): CertSummary {
        val chain = ks.getCertificateChain(ENTRY_ALIAS)
            ?.mapNotNull { it as? X509Certificate }
            ?.takeIf { it.isNotEmpty() }
            ?: error("KeyStore missing certificate chain for '$ENTRY_ALIAS'")
        val leaf = chain.first()
        val rootOrIssuer = chain.last()
        return CertSummary(
            mode = mode,
            subjectDn = leaf.subjectX500Principal.name,
            issuerDn = leaf.issuerX500Principal.name,
            caSha256Fingerprint = sha256ColonHex(rootOrIssuer.encoded),
            leafSha256Fingerprint = sha256ColonHex(leaf.encoded),
            leafNotAfterMillis = leaf.notAfter.time,
        )
    }

    private class TinkMcpTlsProtector(context: Context) : ByteProtector {
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

        override fun encrypt(plain: ByteArray): ByteArray =
            aead().encrypt(plain, ASSOCIATED_DATA)

        override fun decrypt(cipher: ByteArray): ByteArray =
            aead().decrypt(cipher, ASSOCIATED_DATA)

        companion object {
            private const val KEYSET_NAME = "haven_mcp_tls_keyset"
            private const val PREFERENCE_FILE = "haven_mcp_tls_keyset_prefs"
            private const val MASTER_KEY_ALIAS = "haven_mcp_tls_master"
            private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"
            private val ASSOCIATED_DATA = "haven-mcp-tls".toByteArray(Charsets.UTF_8)
        }
    }

    companion object {
        private const val CA_SUBJECT_DN = "CN=Haven Local MCP Root CA,O=Haven"
        private const val LEAF_SUBJECT_DN = "CN=127.0.0.1,O=Haven"
        private const val ENTRY_ALIAS = "mcp-tls"
        private val INTERNAL_STORE_PASSWORD = "haven-mcp-internal".toCharArray()

        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val BACKDATE_MS = DAY_MS
        private const val CLOCK_SKEW_TOLERANCE_MS = 2 * DAY_MS
        // 10-year root CA; 365-day server leaf (<398-day BoringSSL/Conscrypt cap), renewed at <30d remaining.
        private const val CA_VALIDITY_MS = 3650L * DAY_MS
        private const val LEAF_VALIDITY_MS = 364L * DAY_MS
        private const val LEAF_RENEW_BEFORE_EXPIRY_MS = 30L * DAY_MS

        internal fun encodePemCertificate(cert: X509Certificate): String {
            val base64 = Base64.getMimeEncoder(64, "\n".toByteArray(Charsets.US_ASCII))
                .encodeToString(cert.encoded)
            return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n"
        }

        internal fun sha256ColonHex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(":") { "%02X".format(it) }

        private fun sha1(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-1").digest(bytes)
    }
}
