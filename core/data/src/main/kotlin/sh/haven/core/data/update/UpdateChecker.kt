package sh.haven.core.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.haven.core.data.preferences.UserPreferencesRepository
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Certificate of the key that signs the APKs attached to GitHub releases,
 * lower-case hex SHA-256 of the X.509 DER — the same digest
 * `apksigner verify --print-certs` prints. Read off the published
 * `haven-5.87.49-armv7-terminal-release.apk`, not just off the keystore,
 * so it is the digest a user's installed copy will actually carry.
 */
private const val GITHUB_RELEASE_SIGNER_SHA256 =
    "ea03a3a70e1c11d0a78932f959b21f20d8735d9cd750997657cb7f7d7c2b90b3"

private const val LATEST_RELEASE_API =
    "https://api.github.com/repos/GlassHaven/Haven/releases/latest"

/** Enough for the release JSON; a redirected or hostile reply cannot fill memory. */
private const val MAX_RESPONSE_BYTES = 512 * 1024

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000

/**
 * Launch checks no oftener than this, however many times Haven is opened.
 *
 * #597: the trigger is every foreground (UpdateNotifier), so this is the only
 * thing keeping "check on launch" from making a request on every open. One
 * small GET per hour is a fraction of GitHub's unauthenticated 60/hour per-IP
 * limit, and the per-version dedup already stops repeat notifications, so the
 * interval only paces the query. 24h here meant an update released just after
 * a check could stay invisible for a full day on a phone that opens Haven
 * daily.
 */
internal const val LAUNCH_CHECK_INTERVAL_MS = 60L * 60 * 1000

private const val TAG = "UpdateChecker"

/**
 * Opt-in update check (#578).
 *
 * Haven ships under two signing keys. F-Droid does not distribute our APK; it
 * builds from source and signs with its own key, and Android will not replace
 * an installed package with one signed differently. So an update offer that
 * pointed everybody at the latest GitHub release would hand an F-Droid user a
 * download that cannot install — worse than offering nothing, because it reads
 * as a broken app rather than a mismatch of channels.
 *
 * The running copy therefore has to establish which channel it came from, and
 * it does that from its own signing certificate rather than from
 * `installerPackageName`, which any installer can set to anything. Only a copy
 * signed with [GITHUB_RELEASE_SIGNER_SHA256] is offered a GitHub release.
 * Everything else — F-Droid, a self-built debug APK, a re-signed copy — gets
 * [Channel.OTHER] and this class does nothing at all, makes no network request
 * and shows no UI.
 *
 * The check is also off by default and never runs unless
 * [UserPreferencesRepository.updateCheckEnabled] is on.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository,
) {

    /** Which distribution the running copy was signed for. */
    enum class Channel {
        /** Signed with the GitHub-release key; a release download will install. */
        GITHUB_RELEASE,

        /** Anything else. No update is offered and no request is made. */
        OTHER,
    }

    sealed interface Result {
        /** Installed version is the newest published one. */
        data class UpToDate(val installedVersion: String) : Result

        data class Available(
            val version: String,
            val releaseUrl: String,
        ) : Result

        /** Network, parse or API failure. [message] is fit to show the user. */
        data class Failed(val message: String) : Result

        /** Not a GitHub-release install; nothing was requested. */
        data object WrongChannel : Result
    }

    /**
     * The channel of the running copy. Cheap, local and side-effect free —
     * Settings calls it on every composition to decide whether to offer the
     * toggle at all.
     */
    fun channel(): Channel {
        val digests = ownSignerSha256Digests()
        val match = digests.any { it.equals(GITHUB_RELEASE_SIGNER_SHA256, ignoreCase = true) }
        if (!match) {
            // Not an error: an F-Droid or self-built copy lands here by design.
            Log.i(TAG, "channel=OTHER (signers=$digests)")
            return Channel.OTHER
        }
        Log.i(TAG, "channel=GITHUB_RELEASE")
        return Channel.GITHUB_RELEASE
    }

    /** The running app's own version name, e.g. `5.87.50`. */
    fun installedVersion(): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""

    /**
     * Ask GitHub for the newest release and compare. Runs regardless of the
     * opt-in preference — this is the "check now" path, where the user asked
     * for it in the moment. Still refuses on the wrong channel.
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        if (channel() != Channel.GITHUB_RELEASE) return@withContext Result.WrongChannel

        val installed = installedVersion()
        val body = runCatching { fetchLatestRelease() }.getOrElse { e ->
            Log.w(TAG, "release query failed", e)
            return@withContext Result.Failed(e.message ?: e.javaClass.simpleName)
        }

        val json = runCatching { JSONObject(body) }.getOrElse {
            return@withContext Result.Failed("Unreadable reply from GitHub")
        }
        val tag = json.optString("tag_name").ifBlank {
            return@withContext Result.Failed("Release carried no tag")
        }
        val url = json.optString("html_url").ifBlank {
            "https://github.com/GlassHaven/Haven/releases/tag/$tag"
        }
        val latest = normaliseVersion(tag)

        if (isNewer(latest, installed)) {
            Log.i(TAG, "update available: $installed -> $latest")
            Result.Available(latest, url)
        } else {
            Log.i(TAG, "up to date at $installed (latest $latest)")
            Result.UpToDate(installed)
        }
    }

    /**
     * The launch-time path. Returns null — having done nothing — unless the
     * preference is on, the channel is right, the throttle has expired and the
     * newer version is one the user has not already been told about. A non-null
     * [Result.Available] is the caller's cue to notify.
     */
    suspend fun checkOnLaunch(nowMs: Long): Result.Available? {
        val enabled = preferences.updateCheckEnabled.first()
        val lastRun = preferences.updateCheckLastRunMs.first()
        shouldQuery(enabled, { channel() }, lastRun, nowMs)?.let { skip ->
            Log.i(TAG, "launch check skipped: $skip")
            return null
        }
        preferences.setUpdateCheckLastRunMs(nowMs)

        val result = check()
        val lastNotified = preferences.updateCheckLastNotifiedVersion.first()
        shouldNotify(result, lastNotified)?.let { skip ->
            Log.i(TAG, "launch check found nothing to say: $skip")
            return null
        }
        val available = result as Result.Available
        preferences.setUpdateCheckLastNotifiedVersion(available.version)
        return available
    }

    private fun fetchLatestRelease(): String {
        val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            // GitHub rejects requests with no User-Agent.
            setRequestProperty("User-Agent", "Haven/${installedVersion()}")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 403 here is nearly always the unauthenticated rate limit.
                throw IllegalStateException("GitHub returned HTTP $code")
            }
            return conn.inputStream.use { input ->
                val buf = ByteArray(MAX_RESPONSE_BYTES)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Lower-case hex SHA-256 of every certificate the installed package carries. */
    private fun ownSignerSha256Digests(): List<String> {
        val signatures: Array<Signature> = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES,
                ).signatures
            }
        }.getOrNull() ?: return emptyList()

        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            sha.reset()
            sha.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Why a launch check stayed quiet. Named rather than a bare null so a test
     * can assert WHICH gate stopped it — "no notification appeared" on its own
     * is compatible with the preference being off, the wrong signing key, the
     * throttle, a network failure and an outright bug, which makes it nearly
     * worthless as evidence.
     */
    internal enum class LaunchSkip {
        DISABLED,
        WRONG_CHANNEL,
        THROTTLED,
        NOT_NEWER,
        ALREADY_NOTIFIED,
    }

    internal companion object {
        /**
         * Gate one: may we make the request at all? Returns null to proceed.
         *
         * [channelOf] is a lambda rather than a value because the order matters:
         * a disabled check must not read the signing certificate, and neither
         * case may touch the network. The throttle is evaluated last, so a
         * disabled or wrongly-signed copy is never even throttle-tested.
         */
        fun shouldQuery(
            enabled: Boolean,
            channelOf: () -> Channel,
            lastRunMs: Long,
            nowMs: Long,
        ): LaunchSkip? {
            if (!enabled) return LaunchSkip.DISABLED
            if (channelOf() != Channel.GITHUB_RELEASE) return LaunchSkip.WRONG_CHANNEL
            if (lastRunMs == 0L) return null // never run — always allowed
            val elapsed = nowMs - lastRunMs
            // A clock moved backwards would otherwise wedge the throttle shut
            // until it caught up, so only a forward-and-recent gap throttles.
            if (elapsed in 0 until LAUNCH_CHECK_INTERVAL_MS) return LaunchSkip.THROTTLED
            return null
        }

        /**
         * Gate two: having asked, is there anything to tell the user? Returns
         * null to notify. [LaunchSkip.ALREADY_NOTIFIED] is the one that stops a
         * user who chose not to update being told again on every launch.
         */
        fun shouldNotify(result: Result, lastNotifiedVersion: String): LaunchSkip? {
            if (result !is Result.Available) return LaunchSkip.NOT_NEWER
            if (lastNotifiedVersion == result.version) return LaunchSkip.ALREADY_NOTIFIED
            return null
        }

        /** `v5.87.50` and `5.87.50` are the same version. */
        fun normaliseVersion(tag: String): String = tag.trim().removePrefix("v")

        /**
         * Numeric component-wise comparison. A component that is not a number
         * compares as -1, so `5.87.50` beats `5.87.50-rc1` and neither beats a
         * version it cannot be ordered against. Returns false when equal, so a
         * caller only ever offers a strictly newer build.
         */
        fun isNewer(candidate: String, installed: String): Boolean {
            if (candidate.isBlank() || installed.isBlank()) return false
            val a = components(candidate)
            val b = components(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun components(version: String): List<Int> =
            normaliseVersion(version).split('.').map { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull() ?: -1
            }
    }
}
