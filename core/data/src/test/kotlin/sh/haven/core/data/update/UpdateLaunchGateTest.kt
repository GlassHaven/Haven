package sh.haven.core.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The four gates on the launch-time update check (#578), tested in isolation.
 *
 * These exist because the device cannot show most of them. "No notification
 * appeared" is compatible with the preference being off, the wrong signing key,
 * the throttle, the dedup, a network failure and a plain bug — so a quiet phone
 * is nearly worthless as evidence for any one of them. Each test here pins the
 * gate by name AND changes one input to show the gate is load-bearing: if the
 * assertion could not fail, it would not be a test.
 */
class UpdateLaunchGateTest {

    private val github = { UpdateChecker.Channel.GITHUB_RELEASE }
    private val other = { UpdateChecker.Channel.OTHER }
    private val now = 1_700_000_000_000L
    private val available = UpdateChecker.Result.Available("5.87.53", "https://example/tag")

    // --- Gate 1: the preference ------------------------------------------

    @Test
    fun `off means no query at all`() {
        assertEquals(
            UpdateChecker.LaunchSkip.DISABLED,
            UpdateChecker.shouldQuery(enabled = false, channelOf = github, lastRunMs = 0L, nowMs = now),
        )
    }

    @Test
    fun `a disabled check never reads the signing certificate`() {
        // The lambda throwing is the assertion: if shouldQuery evaluated the
        // channel before the preference, this test would fail with that error
        // instead of passing. That ordering is what keeps a switched-off copy
        // from touching PackageManager or the network.
        val exploding = { error("channel must not be consulted when the check is off") }
        assertEquals(
            UpdateChecker.LaunchSkip.DISABLED,
            UpdateChecker.shouldQuery(enabled = false, channelOf = exploding, lastRunMs = 0L, nowMs = now),
        )
    }

    // --- Gate 2: the signing channel -------------------------------------

    @Test
    fun `an F-Droid or self-built copy never queries`() {
        assertEquals(
            UpdateChecker.LaunchSkip.WRONG_CHANNEL,
            UpdateChecker.shouldQuery(enabled = true, channelOf = other, lastRunMs = 0L, nowMs = now),
        )
        // Same inputs, right channel — proves the channel is what decided it.
        assertNull(UpdateChecker.shouldQuery(enabled = true, channelOf = github, lastRunMs = 0L, nowMs = now))
    }

    // --- Gate 3: the throttle --------------------------------------------

    @Test
    fun `a check within the last day is throttled`() {
        val anHourAgo = now - 60 * 60 * 1000
        assertEquals(
            UpdateChecker.LaunchSkip.THROTTLED,
            UpdateChecker.shouldQuery(enabled = true, channelOf = github, lastRunMs = anHourAgo, nowMs = now),
        )
    }

    @Test
    fun `a check older than a day is allowed`() {
        val justOver = now - LAUNCH_CHECK_INTERVAL_MS - 1
        assertNull(UpdateChecker.shouldQuery(enabled = true, channelOf = github, lastRunMs = justOver, nowMs = now))
    }

    @Test
    fun `the boundary is exactly the interval`() {
        val exactly = now - LAUNCH_CHECK_INTERVAL_MS
        assertNull(UpdateChecker.shouldQuery(enabled = true, channelOf = github, lastRunMs = exactly, nowMs = now))
        assertEquals(
            UpdateChecker.LaunchSkip.THROTTLED,
            UpdateChecker.shouldQuery(enabled = true, channelOf = github, lastRunMs = exactly + 1, nowMs = now),
        )
    }

    @Test
    fun `never run is never throttled`() {
        assertNull(UpdateChecker.shouldQuery(enabled = true, channelOf = github, lastRunMs = 0L, nowMs = now))
    }

    @Test
    fun `a clock moved backwards does not wedge the throttle shut`() {
        // lastRun in the "future" — a timezone change, an NTP correction, or a
        // restored backup. Without the elapsed >= 0 guard this would throttle
        // every launch until real time caught up, which for a large jump means
        // the check silently never runs again.
        val future = now + 30L * 24 * 60 * 60 * 1000
        assertNull(UpdateChecker.shouldQuery(enabled = true, channelOf = github, lastRunMs = future, nowMs = now))
    }

    // --- Gate 4: the dedup, with the throttle cleared ---------------------

    @Test
    fun `an already-notified version is not announced again`() {
        assertEquals(
            UpdateChecker.LaunchSkip.ALREADY_NOTIFIED,
            UpdateChecker.shouldNotify(available, lastNotifiedVersion = "5.87.53"),
        )
    }

    @Test
    fun `a version not yet notified is announced`() {
        assertNull(UpdateChecker.shouldNotify(available, lastNotifiedVersion = "5.87.52"))
        assertNull(UpdateChecker.shouldNotify(available, lastNotifiedVersion = ""))
    }

    @Test
    fun `with the throttle cleared the dedup is the only thing keeping it quiet`() {
        // This is the case the device cannot show. Clear the throttle — set the
        // last run to two days ago — so gate 3 provably passes, and the phone
        // would go on to query. What stops the notification now can only be the
        // dedup, and swapping ONE value flips the outcome.
        val twoDaysAgo = now - 2 * LAUNCH_CHECK_INTERVAL_MS
        val query = UpdateChecker.shouldQuery(
            enabled = true,
            channelOf = github,
            lastRunMs = twoDaysAgo,
            nowMs = now,
        )
        assertNull("throttle must be clear, or this proves nothing", query)

        assertEquals(
            "already told about 5.87.53 — must stay quiet",
            UpdateChecker.LaunchSkip.ALREADY_NOTIFIED,
            UpdateChecker.shouldNotify(available, lastNotifiedVersion = "5.87.53"),
        )
        assertNull(
            "same throttle, same version, only the notified-marker differs — must notify",
            UpdateChecker.shouldNotify(available, lastNotifiedVersion = "5.87.52"),
        )
    }

    @Test
    fun `a newer version after one was already notified is still announced`() {
        // The dedup keys on the version, not on "has ever notified" — otherwise
        // declining one update would silence every update after it.
        val newer = UpdateChecker.Result.Available("5.87.54", "https://example/tag")
        assertNull(UpdateChecker.shouldNotify(newer, lastNotifiedVersion = "5.87.53"))
    }

    // --- Gate 4, the non-Available outcomes ------------------------------

    @Test
    fun `up to date says nothing`() {
        assertEquals(
            UpdateChecker.LaunchSkip.NOT_NEWER,
            UpdateChecker.shouldNotify(UpdateChecker.Result.UpToDate("5.87.53"), ""),
        )
    }

    @Test
    fun `a failed check says nothing at launch`() {
        // Deliberate: "check failed" is worth showing in Settings where the user
        // asked, but not worth a notification they did not ask for.
        assertEquals(
            UpdateChecker.LaunchSkip.NOT_NEWER,
            UpdateChecker.shouldNotify(UpdateChecker.Result.Failed("HTTP 403"), ""),
        )
    }

    @Test
    fun `wrong channel says nothing`() {
        assertEquals(
            UpdateChecker.LaunchSkip.NOT_NEWER,
            UpdateChecker.shouldNotify(UpdateChecker.Result.WrongChannel, ""),
        )
    }
}
