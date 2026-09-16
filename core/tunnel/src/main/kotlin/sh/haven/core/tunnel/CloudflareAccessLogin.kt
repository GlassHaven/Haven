package sh.haven.core.tunnel

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves the URL the Access sign-in WebView should load (#643).
 *
 * Cloudflare's edge builds the login redirect per request: the `kid` and
 * `meta` query parameters that select *which* Access application protects a
 * hostname are generated for the specific request and appear only in the
 * `Location` header of the 302 it gets back. Loading the bare
 * `/cdn-cgi/access/login/<hostname>` path we construct ourselves omits them,
 * which is what makes the IdP page answer "Unable to find your Access
 * application" for self-hosted applications — the browser never hits that
 * path because it follows the 302 instead.
 *
 * So sign-in does the same: request the protected hostname with
 * redirect-following disabled, take the 302's `Location`, and hand that to
 * the WebView. [resolveLoginUrl] is the pure half — what to do with a header
 * we already have — and is unit-testable without a network.
 */
object CloudflareAccessLogin {

    /** cloudflared's signal that a route is Access-protected (carrier.go:120). */
    const val ACCESS_LOGIN_PATH = "/cdn-cgi/access/login"

    /**
     * Turn a 302 `Location` into the URL to load, or null when it is unusable
     * and the caller should fall back to the constructed login path.
     *
     * Accepts only an absolute http(s) URL that still points at the Access
     * login path — a `Location` that has already bounced off to an IdP, or a
     * `javascript:`/`file:` scheme, must not be handed to a WebView that has
     * the user's cookies in its jar.
     */
    fun resolveLoginUrl(location: String?, hostname: String): String? {
        val raw = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val absolute = when {
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("http://", ignoreCase = true) -> raw
            // A path-only Location is relative to the protected origin.
            raw.startsWith("/") -> "https://$hostname$raw"
            else -> return null
        }
        return absolute.takeIf { it.contains(ACCESS_LOGIN_PATH) }
    }

    /**
     * Fetch the protected hostname with redirects disabled and return the
     * `Location` of the resulting 302, or null if the edge did not answer with
     * an Access login redirect (already-authenticated, not protected, offline).
     *
     * Never throws: a failure here means "fall back to the constructed URL",
     * which is the behaviour before this existed, so a broken probe must not
     * take sign-in down with it.
     */
    fun fetchLoginLocation(client: OkHttpClient, hostname: String): String? {
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url("https://$hostname/")
            .header("Accept", "text/html")
            .build()
        return runCatching {
            noRedirect.newCall(request).execute().use { response ->
                loginLocationFromResponse(response.code, response.header("Location"))
            }
        }.getOrNull()
    }

    /**
     * The decision [fetchLoginLocation] makes about a response, split out so it
     * is testable: only a redirect carrying a `Location` is useful. A 200 means
     * the route is not Access-protected (or we are already signed in) and a 302
     * with no header is malformed — both mean "fall back".
     */
    fun loginLocationFromResponse(code: Int, location: String?): String? =
        if (code in 300..399) location else null
}
