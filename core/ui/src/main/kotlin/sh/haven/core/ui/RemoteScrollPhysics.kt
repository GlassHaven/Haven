package sh.haven.core.ui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign

/**
 * Two-finger remote scrolling that feels like a laptop trackpad: the page
 * follows the fingers while they move, and a flick keeps it gliding to a stop.
 *
 * VNC, SPICE and GLKVM can only send whole wheel notches, so smoothness comes
 * from sending them often and evenly and letting the remote apply its own
 * smooth-scroll animation (Safari and Chrome on macOS do), which is how a Mac
 * trackpad reaches a VNC server as well.
 *
 * Distances are in remote framebuffer pixels, not screen pixels: one notch is
 * worth roughly the same distance on the remote page however the desktop is
 * zoomed on the phone, so the content tracks the fingers at any zoom. The old
 * fixed 40 screen-pixel step sent a notch per 40 *physical* pixels, which on a
 * dense phone is a long way for one wheel line — "scrolls a line per swipe".
 */
data class RemoteScrollConfig(
    /** Remote pixels of finger travel per wheel notch at speed 1. */
    val pixelsPerNotch: Float = DEFAULT_PIXELS_PER_NOTCH,
    /** User multiplier from settings; higher scrolls further per swipe. */
    val speed: Float = 1f,
    /** Keep scrolling after a flick, decelerating like a trackpad. */
    val inertia: Boolean = true,
) {
    /** Remote pixels per notch after the speed multiplier. */
    val effectivePixelsPerNotch: Float
        get() = pixelsPerNotch / speed.coerceIn(MIN_SPEED, MAX_SPEED)

    companion object {
        /**
         * A wheel notch in a macOS browser moves the page on the order of tens
         * of points; on a typical Retina-scaled remote framebuffer this keeps
         * the content roughly under the fingers. Tuned on device against
         * macOS-style smooth scrolling; the speed setting covers the rest.
         */
        const val DEFAULT_PIXELS_PER_NOTCH = 48f
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f
        val DEFAULT = RemoteScrollConfig()
    }
}

/**
 * Turns two-finger drag movement into wheel notches, and a release velocity
 * into a decelerating fling. Pure state machine — no Compose, no clock of its
 * own — so the feel is unit-testable.
 *
 * Sign convention matches the existing callbacks: a positive return is
 * wheel-up (page content moves down, as when the fingers move down), a
 * negative one wheel-down.
 */
class RemoteScrollTracker(private val config: RemoteScrollConfig = RemoteScrollConfig.DEFAULT) {
    private var residual = 0f
    private val samples = ArrayDeque<Pair<Long, Float>>()

    /** Start of a new two-finger gesture: forget the previous one's remainder. */
    fun reset() {
        residual = 0f
        samples.clear()
    }

    /**
     * Feed a finger movement of [remoteDy] remote pixels (positive = fingers
     * moved down) at [uptimeMs]. Returns whole notches to send now.
     */
    fun drag(remoteDy: Float, uptimeMs: Long): Int {
        if (remoteDy.isNaN()) return 0
        samples.addLast(uptimeMs to remoteDy)
        while (samples.size > 1 && uptimeMs - samples.first().first > VELOCITY_WINDOW_MS) {
            samples.removeFirst()
        }
        return takeNotches(remoteDy)
    }

    /**
     * Release velocity in remote pixels per second over the movement in the
     * last [VELOCITY_WINDOW_MS] before the lift. Zero when the fingers had
     * stopped before lifting (no fling after a deliberate stop).
     *
     * Each sample is the distance moved since the previous event, so the
     * velocity is that distance over the time since the oldest sample's
     * predecessor — robust to touch hardware that batches events coarsely.
     */
    fun releaseVelocity(uptimeMs: Long): Float {
        val last = samples.lastOrNull() ?: return 0f
        if (uptimeMs - last.first > STOP_BEFORE_LIFT_MS) return 0f
        val first = samples.indexOfFirst { uptimeMs - it.first <= VELOCITY_WINDOW_MS }
        if (first < 0) return 0f
        // A sample's distance was covered since the event before it, so the
        // window's distance spans from that earlier event to the last one.
        val (startMs, distance) = if (first > 0) {
            samples[first - 1].first to samples.drop(first).sumOf { it.second.toDouble() }.toFloat()
        } else {
            if (samples.size < 2) return 0f
            samples[0].first to samples.drop(1).sumOf { it.second.toDouble() }.toFloat()
        }
        val span = (last.first - startMs).coerceAtLeast(1L)
        return distance * 1000f / span
    }

    /**
     * A fling from [velocity] (remote px/s) that the caller steps with
     * [Fling.step] each frame, or null when inertia is off or the flick is
     * too slow to glide.
     */
    fun fling(velocity: Float): Fling? {
        if (!config.inertia || abs(velocity) < MIN_FLING_VELOCITY) return null
        val capped = velocity.coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
        return Fling(capped)
    }

    private fun takeNotches(remoteDy: Float): Int {
        residual += remoteDy
        val per = config.effectivePixelsPerNotch
        val whole = (residual / per).toInt()
        residual -= whole * per
        return whole.coerceIn(-MAX_NOTCHES_PER_EVENT, MAX_NOTCHES_PER_EVENT)
    }

    /**
     * Exponential decay, the shape of iOS / macOS momentum scrolling: velocity
     * falls by [DECAY_PER_SECOND] each second, so a hard flick travels far and
     * eases out instead of stopping dead.
     */
    inner class Fling internal constructor(private var velocity: Float) {
        /** Whether there is still enough speed to keep stepping. */
        val isRunning: Boolean get() = abs(velocity) >= STOP_VELOCITY

        /** Advance by [dtMs] milliseconds; returns whole notches to send. */
        fun step(dtMs: Long): Int {
            if (!isRunning || dtMs <= 0) return 0
            val dt = dtMs / 1000f
            // Distance under v(t) = v0·e^(−kt) over dt, so the total glide is
            // independent of the frame rate.
            val k = -ln(DECAY_PER_SECOND)
            val decay = exp(-k * dt)
            val distance = velocity * (1f - decay) / k
            velocity *= decay
            if (!isRunning) velocity = 0f
            return takeNotches(distance)
        }

        /** Total remote pixels this fling would still travel. */
        fun remainingDistance(): Float = velocity / -ln(DECAY_PER_SECOND)

        /** Direction of travel: +1 down, −1 up, 0 stopped. */
        val direction: Int get() = sign(velocity).toInt()
    }

    companion object {
        /**
         * Samples older than this don't count towards the release velocity.
         * Long enough to hold a few events even from touch hardware that
         * batches them (some report every ~60 ms), short enough to reflect
         * the speed at the lift rather than the whole gesture.
         */
        const val VELOCITY_WINDOW_MS = 150L

        /**
         * Fingers resting this long before lifting means "stop here", not a
         * flick. Above the slowest event batching seen, so a moving lift is
         * never mistaken for a rest.
         */
        const val STOP_BEFORE_LIFT_MS = 100L

        /** Below this (remote px/s) a lift is a plain release, no glide. */
        const val MIN_FLING_VELOCITY = 400f

        /** Ceiling so a wild flick can't flood the connection with notches. */
        const val MAX_FLING_VELOCITY = 12000f

        /** A fling ends once it slows below this (remote px/s). */
        const val STOP_VELOCITY = 60f

        /** Fraction of velocity kept after one second of gliding. */
        const val DECAY_PER_SECOND = 0.04f

        /** Upper bound on notches from a single pointer event or frame. */
        const val MAX_NOTCHES_PER_EVENT = 8
    }
}

/**
 * The desktop toolbar's two-finger drag mode, remembered across sessions
 * (true = scroll the remote, false = pan the zoomed view). The host app
 * provides one backed by preferences; without it the toggle is per-screen
 * state, as before.
 */
class RemoteTwoFingerScrollPreference(
    val initial: Boolean,
    val onChange: (Boolean) -> Unit,
)

val LocalRemoteTwoFingerScroll = androidx.compose.runtime.staticCompositionLocalOf<RemoteTwoFingerScrollPreference?> { null }
