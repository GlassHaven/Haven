package sh.haven.core.stepca

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.haven.core.data.db.entities.StepCaConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Drives an OIDC Authorization Code + PKCE flow against the IdP
 * configured in a [StepCaConfig], returning the final ID token. That
 * token is then handed to step-ca to mint a signed SSH cert.
 *
 * No persistent state — the access/refresh tokens are not retained;
 * we only ever need the one ID token and only for one HTTP round-trip.
 */
@Singleton
class OidcAuthClient(
    private val appContext: Context,
    private val foregroundWatcher: ForegroundWatcher,
    private val cancelGraceMs: Long = CANCEL_GRACE_MS,
) {

    @Inject constructor(@ApplicationContext appContext: Context) :
        this(appContext, DefaultForegroundWatcher())


    /**
     * Launch a Custom Tab pointing at the IdP's authorize URL, await the
     * redirect, exchange the auth code for an ID token. Throws on any
     * step that fails (browser launch, redirect error, token exchange).
     *
     * If the user dismisses the Custom Tab without completing the flow
     * (back-press, close button, no IdP redirect ever fired), the process
     * returns to the foreground without `OidcRedirectActivity` ever
     * resolving the bus entry. We catch that case with a foreground
     * watcher: after the app returns to STARTED, a [CANCEL_GRACE_MS]
     * window allows a legitimately-firing redirect Activity to still
     * win; if the deferred is still pending when the grace elapses, we
     * fail it so the caller's `try/finally` (in `KeysViewModel`) can
     * clear its loading state instead of spinning forever.
     *
     * The caller is responsible for cancellation timeouts — wrap with
     * [kotlinx.coroutines.withTimeout] if needed.
     *
     * @param launcher abstracts CustomTabsIntent.launchUrl so unit tests
     *   can substitute a fake. The real call site passes [DefaultLauncher].
     */
    suspend fun authorize(
        caConfig: StepCaConfig,
        launcher: CustomTabLauncher = DefaultLauncher(appContext),
    ): String = coroutineScope {
        val (verifier, challenge) = Pkce.generate()
        val state = UUID.randomUUID().toString()

        val authUri = Uri.parse(caConfig.oidcAuthUrl).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", caConfig.oidcClientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "openid email profile")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        val deferred = OidcRedirectBus.register(state)
        val cancellationJob = launch {
            foregroundWatcher.awaitReturnToForeground()
            delay(cancelGraceMs)
            OidcRedirectBus.fail(state, "OIDC sign-in cancelled")
        }

        try {
            launcher.launch(authUri)
        } catch (e: Throwable) {
            cancellationJob.cancel()
            OidcRedirectBus.forget(state)
            throw IllegalStateException("Failed to launch browser for OIDC: ${e.message}", e)
        }

        val result = try {
            deferred.await()
        } finally {
            cancellationJob.cancel()
        }
        when (result) {
            is OidcRedirectBus.Result.Error -> error("OIDC error: ${result.message}")
            is OidcRedirectBus.Result.Code -> exchangeCode(caConfig, result.code, verifier)
        }
    }

    private suspend fun exchangeCode(
        caConfig: StepCaConfig,
        code: String,
        verifier: String,
    ): String = withContext(Dispatchers.IO) {
        val body = buildString {
            append("grant_type=authorization_code")
            append("&code=").append(URLEncoder.encode(code, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            append("&code_verifier=").append(URLEncoder.encode(verifier, "UTF-8"))
            append("&client_id=").append(URLEncoder.encode(caConfig.oidcClientId, "UTF-8"))
            // Confidential-client IdPs (Authentik default, Keycloak, Okta) require
            // the secret on the token-exchange POST. step-ca itself publishes the
            // secret via `/provisioners`, so the bootstrap flow pre-fills it; the
            // field is also editable in manual entry. Public PKCE-only clients
            // leave [oidcClientSecret] null/empty and this branch is skipped.
            caConfig.oidcClientSecret?.takeIf { it.isNotEmpty() }?.let { secret ->
                append("&client_secret=").append(URLEncoder.encode(secret, "UTF-8"))
            }
        }
        val conn = (URL(caConfig.oidcTokenUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                error("OIDC token endpoint returned $responseCode: $err")
            }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val idToken = JSONObject(resp).optString("id_token", "")
            if (idToken.isEmpty()) error("OIDC token response missing id_token")
            idToken
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Abstraction around `CustomTabsIntent.launchUrl(Context, Uri)` so the
     * authorize flow is testable without an Android view system. The
     * production binding is [DefaultLauncher].
     */
    fun interface CustomTabLauncher {
        fun launch(uri: Uri)
    }

    /** Real Custom Tab launcher backed by AndroidX Browser. */
    class DefaultLauncher(private val context: Context) : CustomTabLauncher {
        override fun launch(uri: Uri) {
            val intent = CustomTabsIntent.Builder().build().intent.apply {
                data = uri
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Suspends until the app process transitions from background back to
     * the foreground exactly once. Implemented over `ProcessLifecycleOwner`
     * in production; the test seam lets `OidcAuthClientCancellationTest`
     * drive the transition deterministically.
     */
    fun interface ForegroundWatcher {
        suspend fun awaitReturnToForeground()
    }

    /** Real `ProcessLifecycleOwner`-backed implementation. */
    class DefaultForegroundWatcher : ForegroundWatcher {
        override suspend fun awaitReturnToForeground(): Unit =
            suspendCancellableCoroutine { cont ->
                val mainHandler = Handler(Looper.getMainLooper())
                val observer = object : DefaultLifecycleObserver {
                    @Volatile var sawBackground = false

                    override fun onStop(owner: LifecycleOwner) {
                        sawBackground = true
                    }

                    override fun onStart(owner: LifecycleOwner) {
                        if (sawBackground && cont.isActive) {
                            mainHandler.post {
                                ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
                            }
                            cont.resume(Unit)
                        }
                    }
                }
                mainHandler.post {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
                }
                cont.invokeOnCancellation {
                    mainHandler.post {
                        ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
                    }
                }
            }
    }

    companion object {
        const val REDIRECT_URI = "haven://stepca-callback"

        /**
         * Grace window after the app returns to foreground before we treat a
         * pending OIDC flow as cancelled. The redirect Activity normally wins
         * the race (it's singleTask and dispatches the deep-link before the
         * host Activity is resumed), but on slow devices it can lag a bit.
         */
        const val CANCEL_GRACE_MS = 1500L
    }
}
