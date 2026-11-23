package sh.haven.core.ssh

import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.security.Signature

/**
 * Makes the composite ML-DSA signature services available to sshlib (#859).
 *
 * sshlib offers ssh-mldsa44-ed25519 (and ssh-mldsa87-p384) only when the
 * platform JCA can perform the composite signature: it probes the registered
 * providers at connect time and silently drops the algorithm from the offer
 * list when the probe fails. The key then never reaches the server and auth
 * falls back to password. Most current Android builds do not expose the
 * composite service, so an imported hybrid key imports cleanly but cannot
 * authenticate.
 *
 * Haven bundles BouncyCastle 1.84, which registers exactly the service names
 * sshlib probes (MLDSA44-Ed25519-SHA512, MLDSA87-ECDSA-P384-SHA512). Register
 * it when the platform cannot serve the composite, mirroring the
 * [RawKeyProviderOrder] fallback for #451: on a device that already has the
 * service, nothing here touches the provider list at all.
 */
object MldsaProviderAvailability {

    private const val TAG = "MldsaProviderAvailability"

    /** The composite JCA service names sshlib's availability probe resolves. */
    internal val COMPOSITE_ALGORITHMS = listOf(
        "MLDSA44-Ed25519-SHA512",
        "MLDSA87-ECDSA-P384-SHA512",
    )

    /**
     * Pure policy: register a provider only when at least one composite
     * service is missing. Returns whether the provider ended up registered.
     */
    internal fun applyWith(
        probe: (String) -> Boolean,
        registerBouncyCastle: () -> Boolean,
    ): Boolean {
        if (COMPOSITE_ALGORITHMS.all(probe)) return false
        return registerBouncyCastle()
    }

    /** Production entry point — call once during app start, after [RawKeyProviderOrder.apply]. */
    fun apply(): Boolean {
        val registered = applyWith(
            probe = ::serviceResolves,
            registerBouncyCastle = ::registerBouncyCastle,
        )
        if (!registered) return false
        val resolved = COMPOSITE_ALGORITHMS.joinToString(", ") {
            "$it=${if (serviceResolves(it)) "ok" else "unavailable"}"
        }
        Log.i(TAG, "registered bundled BouncyCastle for composite ML-DSA (#859); now: $resolved")
        return true
    }

    private fun serviceResolves(algorithm: String): Boolean =
        runCatching { Signature.getInstance(algorithm) }.isSuccess

    /**
     * Android preinstalls a cut-down provider under the name "BC", so the
     * first add can be refused; replacing it is deliberate and only happens
     * on a device that has already shown it cannot serve the composite.
     * Same shape as [RawKeyProviderOrder]'s fallback.
     */
    private fun registerBouncyCastle(): Boolean = runCatching {
        val bundled = BouncyCastleProvider()
        if (Security.addProvider(bundled) != -1) return@runCatching true
        Security.removeProvider(bundled.name)
        Security.addProvider(bundled) != -1
    }.getOrDefault(false)
}
