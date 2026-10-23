package sh.haven.core.stepca

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.data.repository.StepCaConfigRepository
import sh.haven.core.security.SshKeyGenerator
import sh.haven.core.ssh.OpenSshCertificate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-connect gate that re-signs an SSH cert via step-ca when it's
 * about to expire. Inserted into the auth-method resolution path so
 * that a stale cert never makes it into a JSch connect attempt.
 *
 * Renewal generates a fresh Ed25519 keypair and a new cert against the
 * CA referenced by [SshKey.caConfigId]. The new keypair replaces the
 * old one on the same DB row (key rotation is a feature, not a bug —
 * step-ca's cert auth is decoupled from any specific public key, so
 * the server keeps trusting whichever keypair the CA most recently
 * signed for).
 *
 * Phase 2b of #133.
 */
@Singleton
class CertRenewalGate @Inject constructor(
    private val signFlow: StepCaSignFlow,
    private val stepCaConfigRepository: StepCaConfigRepository,
    private val sshKeyRepository: SshKeyRepository,
) {

    /**
     * `null` when no renewal is in flight. When non-null the
     * Connections screen renders a status banner so the user
     * understands why the OS browser just popped over the connect
     * spinner.
     */
    private val _renewing = MutableStateFlow<Renewing?>(null)
    val renewing: StateFlow<Renewing?> = _renewing.asStateFlow()

    data class Renewing(val keyLabel: String, val caName: String)

    /**
     * Inspect [sshKey]; if it has a CA-minted cert that's within
     * [leewaySeconds] of `validity_before`, run the OIDC + sign-ssh
     * flow to mint a replacement and persist it before returning.
     *
     * Returns:
     *  - [sshKey] unchanged when there's nothing to renew (no cert,
     *    no caConfigId, plenty of validity remaining, or unparseable
     *    cert — the last is a soft failure: the connect path will
     *    just attempt with the existing material and let JSch fail
     *    if the cert is genuinely invalid).
     *  - the freshly-saved [SshKey] (with new keypair + cert) when
     *    renewal succeeded.
     *
     * Throws on hard failures the caller should surface as a connect
     * error: CA referenced by [SshKey.caConfigId] has been deleted,
     * OIDC cancelled by the user, or `/sign-ssh` rejected the request.
     */
    suspend fun ensureFresh(
        sshKey: SshKey,
        leewaySeconds: Long = DEFAULT_LEEWAY_SECONDS,
        nowSecondsProvider: () -> Long = { System.currentTimeMillis() / 1000 },
    ): SshKey {
        // Hardware-resident keys are never renewed: this gate rotates in a
        // fresh SOFTWARE keypair (the CA signs a public key), which would
        // write software bytes into a row whose hardwareAlias says the
        // private half lives in the chip — a row that lies about itself.
        // Chip-key cert renewal needs a CSR signed on-chip; until that
        // exists, the (possibly stale) cert is offered as-is and the server
        // rejects it if it has truly expired.
        if (sshKey.hardwareAlias != null) return sshKey
        val caConfigId = sshKey.caConfigId ?: return sshKey
        val certBytes = sshKey.certificateBytes ?: return sshKey
        val cert = OpenSshCertificate.parseOrNull(certBytes) ?: return sshKey

        val now = nowSecondsProvider()
        if (cert.secondsUntilExpiry(now) > leewaySeconds) return sshKey

        val caConfig = stepCaConfigRepository.getById(caConfigId)
            ?: throw IllegalStateException(
                "Cannot renew the cert for '${sshKey.label}': the step-ca CA " +
                    "used to mint it has been removed from Settings.",
            )

        return try {
            _renewing.value = Renewing(keyLabel = sshKey.label, caName = caConfig.name)

            val generated = withContext(Dispatchers.Default) {
                SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, sshKey.label)
            }
            val signResult = signFlow.run(
                caConfig = caConfig,
                publicKeyOpenSsh = generated.publicKeyOpenSsh,
                keyLabel = sshKey.label,
            )
            when (signResult) {
                is StepCaSignFlow.Result.Failure ->
                    throw IllegalStateException(
                        "step-ca refused to renew '${sshKey.label}': ${signResult.message}",
                    )
                is StepCaSignFlow.Result.Success -> {
                    // Returned in-memory copy keeps the freshly-generated
                    // plaintext private bytes. The `save` call writes the
                    // encrypted-at-rest version; subsequent
                    // getDecryptedKeyBytes(...) calls in this connect
                    // path will route through Keystore and decrypt them
                    // back. We don't want to *return* the encrypted form
                    // here — callers expect a usable SshKey row.
                    val updated = sshKey.copy(
                        privateKeyBytes = generated.privateKeyBytes,
                        publicKeyOpenSsh = generated.publicKeyOpenSsh,
                        fingerprintSha256 = generated.fingerprintSha256,
                        certificateBytes = signResult.certBytes,
                        certIssuedAt = System.currentTimeMillis(),
                    )
                    sshKeyRepository.save(updated)
                    updated
                }
            }
        } finally {
            _renewing.value = null
        }
    }

    companion object {
        /**
         * Default leeway: re-sign if the cert expires within 5 minutes.
         * Short enough that a connect right at expiry kicks renewal
         * (rather than failing then forcing the user to retry); long
         * enough to absorb phone↔CA clock skew. Not configurable
         * per-CA in 2b — promote later if anyone cares.
         */
        const val DEFAULT_LEEWAY_SECONDS: Long = 300L
    }
}
