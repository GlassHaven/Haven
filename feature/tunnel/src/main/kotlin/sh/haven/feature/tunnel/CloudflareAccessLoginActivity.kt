package sh.haven.feature.tunnel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import sh.haven.core.security.JwtPayload
import sh.haven.core.tunnel.CloudflareAccessLogin
import javax.inject.Inject

/**
 * Hosts a [WebView] that drives the Cloudflare Access IdP login flow
 * for a given Access hostname, then captures the `CF_Authorization`
 * cookie once auth completes.
 *
 * Cloudflare Access flow:
 *   1. We request the protected hostname with redirects disabled and load
 *      the 302's `Location` — the login URL Cloudflare built for *this*
 *      request, carrying the `kid`/`meta` pair that names the Access
 *      application (#643). If the probe yields nothing usable we fall back
 *      to constructing `https://<hostname>/cdn-cgi/access/login/<hostname>`
 *      ourselves, which self-hosted applications reject with "Unable to find
 *      your Access application".
 *   2. CF redirects to the team's configured IdP (Okta / GitHub /
 *      Google / etc.) and back through `*.cloudflareaccess.com`.
 *   3. After successful auth, CF sets `CF_Authorization=<jwt>` as a
 *      cookie on the Access application origin (`<hostname>`) and
 *      redirects to the protected URL.
 *   4. On every page navigation we poll [CookieManager] for the cookie;
 *      the first time it appears we extract it, parse the JWT exp
 *      claim, and finish with the result.
 *
 * The WebView is created programmatically rather than via XML so the
 * `feature/tunnel` module doesn't need an `:android-resources`
 * declaration just for this one activity layout.
 *
 * The activity is declared in the *app* manifest (not this module's
 * manifest) because the WebView interacts with [CookieManager] on the
 * app's process — keeping the registration in `app/.../AndroidManifest.xml`
 * matches how other feature activities are exposed.
 */
@AndroidEntryPoint
class CloudflareAccessLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_HOSTNAME = "sh.haven.feature.tunnel.HOSTNAME"
        const val EXTRA_TEAM_DOMAIN = "sh.haven.feature.tunnel.TEAM_DOMAIN"
        const val EXTRA_JWT = "sh.haven.feature.tunnel.JWT"
        const val EXTRA_EXPIRES_AT = "sh.haven.feature.tunnel.EXPIRES_AT"
        const val EXTRA_COOKIE_SOURCE = "sh.haven.feature.tunnel.COOKIE_SOURCE"
        const val EXTRA_ERROR = "sh.haven.feature.tunnel.ERROR"

        private const val TAG = "CFAccessLogin"
        private const val COOKIE_NAME = "CF_Authorization"

        const val COOKIE_SOURCE_APP = "app"
        const val COOKIE_SOURCE_TEAM = "team"
    }

    @Inject lateinit var httpClient: OkHttpClient

    private lateinit var webView: WebView
    private lateinit var hostname: String
    private var teamDomain: String = ""
    @Volatile private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hostname = intent.getStringExtra(EXTRA_HOSTNAME)
            ?.trim()
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.substringBefore('/')
            .orEmpty()
        teamDomain = intent.getStringExtra(EXTRA_TEAM_DOMAIN)
            ?.trim()
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.substringBefore('/')
            .orEmpty()

        if (hostname.isEmpty()) {
            finishWithError("No Cloudflare Access hostname provided")
            return
        }

        // Reset any leftover state from a prior attempt so the IdP starts
        // a clean session. Without this, stale team-domain session cookies
        // from an earlier attempt surface Cloudflare's "Invalid login
        // session" interstitial and the user has to tap through a recovery
        // link (#643).
        clearAccessCookies()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val header = TextView(this).apply {
            text = "Cloudflare Access · $hostname"
            setPadding(32, 32, 32, 16)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F2937"))
        }
        container.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        webView = WebView(this).apply {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    tryCaptureJwt()
                }
            }
        }
        container.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val frame = FrameLayout(this).apply { addView(container) }
        setContentView(frame)

        // Ask the edge for its own login URL rather than guessing it (#643):
        // the kid/meta pair that selects the Access application only exists in
        // the 302 this request provokes. Off the main thread, and a probe that
        // fails or answers with something other than an Access redirect falls
        // back to the constructed path — the pre-#643 behaviour.
        lifecycleScope.launch {
            val location = CloudflareAccessLogin.fetchLoginLocation(httpClient, hostname)
            val fallback = "https://$hostname${CloudflareAccessLogin.ACCESS_LOGIN_PATH}/$hostname"
            val url = CloudflareAccessLogin.resolveLoginUrl(location, hostname) ?: fallback
            if (url == fallback && location != null) {
                // Length only — the Location carries the kid/meta pair, so it
                // is identity, not something to put in a log line (#518).
                Log.d(TAG, "login Location unusable (${location.length} chars); using constructed URL")
            } else if (url == fallback) {
                // Probe found nothing at all (offline, timeout, not
                // Access-protected). Log it — this is exactly the silent
                // failure that made the 5.89.2 fix look like it didn't work.
                Log.d(TAG, "probe found no Access login redirect; using constructed URL")
            }
            if (!resolved) webView.loadUrl(url)
        }
    }

    private fun tryCaptureJwt() {
        if (resolved) return
        val cm = CookieManager.getInstance()

        // Cloudflare Access sets CF_Authorization on TWO domains: the
        // protected application (`<hostname>`) and the team domain
        // (`<team>.cloudflareaccess.com`). Per cloudflared's behaviour
        // the per-app cookie is the one the gateway expects on the WS
        // upgrade, but we capture both so the failure mode is visible
        // if the per-app cookie is missing.
        val appCookies = cm.getCookie("https://$hostname")
        val teamCookies = if (teamDomain.isNotEmpty()) cm.getCookie("https://$teamDomain") else null

        val appJwt = appCookies?.let { parseAccessCookie(it) }
        val teamJwt = teamCookies?.let { parseAccessCookie(it) }

        // Diagnostics: log what we saw on each domain. Length only,
        // never the JWT itself.
        Log.d(
            TAG,
            "cookie scan: app=${describeJwt(appJwt)} team=${describeJwt(teamJwt)}",
        )

        val (jwt, source) = when {
            appJwt != null -> appJwt to COOKIE_SOURCE_APP
            teamJwt != null -> teamJwt to COOKIE_SOURCE_TEAM
            else -> return  // neither cookie present yet; keep waiting
        }
        resolved = true

        val expiresAt = JwtPayload.parse(jwt)?.expiresAtSeconds ?: 0L
        Log.d(TAG, "captured CF_Authorization JWT from $source (exp=$expiresAt)")

        val data = Intent().apply {
            putExtra(EXTRA_JWT, jwt)
            putExtra(EXTRA_EXPIRES_AT, expiresAt)
            putExtra(EXTRA_COOKIE_SOURCE, source)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun describeJwt(jwt: String?): String =
        if (jwt == null) "absent" else "${jwt.length} chars"

    /**
     * Extract the `CF_Authorization` value from a `Cookie:` header string
     * like `cf_clearance=…; CF_Authorization=eyJ…; other=…`. Returns
     * null if not present or empty.
     */
    private fun parseAccessCookie(header: String): String? {
        for (raw in header.split(';')) {
            val pair = raw.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0] == COOKIE_NAME) {
                val value = pair[1].trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    /**
     * Clear every cookie the WebView holds for the app hostname and the
     * team domain, so each sign-in starts from a clean session. CookieManager
     * has no delete-one API — the documented way is to overwrite each cookie
     * with an expired Max-Age — and the stale state that breaks a repeat
     * sign-in lives on the TEAM domain too (its session cookie, not the app
     * domain's [COOKIE_NAME]), so both origins get wiped and every cookie
     * name each origin currently exposes gets cleared by name.
     */
    private fun clearAccessCookies() {
        val cm = CookieManager.getInstance()
        for (url in buildList {
            add("https://$hostname")
            if (teamDomain.isNotBlank() && !teamDomain.equals(hostname, ignoreCase = true)) {
                add("https://$teamDomain")
            }
        }) {
            cm.getCookie(url)?.let { header ->
                for (raw in header.split(';')) {
                    val name = raw.trim().substringBefore('=')
                    if (name.isNotEmpty()) cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                }
            }
            // Belt and braces for the token itself in case its attributes
            // kept it out of the getCookie listing above.
            cm.setCookie(url, "$COOKIE_NAME=; Max-Age=0; Path=/")
        }
        cm.flush()
    }

    private fun finishWithError(reason: String) {
        val data = Intent().apply { putExtra(EXTRA_ERROR, reason) }
        setResult(RESULT_CANCELED, data)
        finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
