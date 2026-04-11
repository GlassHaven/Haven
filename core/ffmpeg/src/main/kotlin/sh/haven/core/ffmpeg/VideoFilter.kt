package sh.haven.core.ffmpeg

import java.util.Locale

/**
 * Video filters that can be chained in an FFmpeg `-vf` argument.
 *
 * Each subclass maps to a single ffmpeg filter expression. Multiple
 * filters are comma-joined: `-vf "deshake,eq=brightness=0.1,unsharp=5:5:1.0"`
 *
 * Parameters use ffmpeg's native ranges and defaults so the UI can
 * expose sliders with meaningful bounds.
 */
sealed class VideoFilter {
    abstract fun toFfmpeg(): String

    // --- Color / histogram ---

    /** Adjust brightness. Range: -1.0 (black) to 1.0 (white). Default: 0.0 */
    data class Brightness(val value: Float = 0f) : VideoFilter() {
        override fun toFfmpeg() = "eq=brightness=${fmt(value)}"
    }

    /** Adjust contrast. Range: 0.0 to 3.0. Default: 1.0 (unchanged) */
    data class Contrast(val value: Float = 1f) : VideoFilter() {
        override fun toFfmpeg() = "eq=contrast=${fmt(value)}"
    }

    /** Adjust saturation. Range: 0.0 (grayscale) to 3.0. Default: 1.0 */
    data class Saturation(val value: Float = 1f) : VideoFilter() {
        override fun toFfmpeg() = "eq=saturation=${fmt(value)}"
    }

    /** Adjust gamma. Range: 0.1 to 10.0. Default: 1.0. Values <1 brighten shadows, >1 darken. */
    data class Gamma(val value: Float = 1f) : VideoFilter() {
        override fun toFfmpeg() = "eq=gamma=${fmt(value)}"
    }

    /** Auto-normalize histogram (stretch levels to full range). */
    data object AutoColor : VideoFilter() {
        override fun toFfmpeg() = "normalize"
    }

    /**
     * RGB color balance for shadows/midtones/highlights.
     * Each value ranges from -1.0 to 1.0. Default: 0.0 (no shift).
     */
    data class ColorBalance(
        val rs: Float = 0f, val gs: Float = 0f, val bs: Float = 0f,
        val rm: Float = 0f, val gm: Float = 0f, val bm: Float = 0f,
        val rh: Float = 0f, val gh: Float = 0f, val bh: Float = 0f,
    ) : VideoFilter() {
        override fun toFfmpeg() = buildString {
            append("colorbalance=")
            append("rs=${fmt(rs)}:gs=${fmt(gs)}:bs=${fmt(bs)}")
            append(":rm=${fmt(rm)}:gm=${fmt(gm)}:bm=${fmt(bm)}")
            append(":rh=${fmt(rh)}:gh=${fmt(gh)}:bh=${fmt(bh)}")
        }
    }

    // --- Stabilization / sharpness ---

    /** Stabilize shaky video. Built-in deshake filter (no external lib). */
    data class Stabilize(val rx: Int = 16, val ry: Int = 16) : VideoFilter() {
        override fun toFfmpeg() = "deshake=rx=$rx:ry=$ry"
    }

    /** Sharpen (positive) or blur (negative). Range: -1.5 to 3.0. Default: 1.0 */
    data class Sharpen(val amount: Float = 1f) : VideoFilter() {
        override fun toFfmpeg() = "unsharp=5:5:${fmt(amount)}:5:5:${fmt(amount)}"
    }

    /** Denoise using non-local means. Strength 1-20. Default: 5 */
    data class Denoise(val strength: Int = 5) : VideoFilter() {
        override fun toFfmpeg() = "nlmeans=$strength"
    }

    // --- Geometry ---

    /** Scale to width x height. Use -1 or -2 to preserve aspect ratio. */
    data class Scale(val w: Int, val h: Int = -2) : VideoFilter() {
        override fun toFfmpeg() = "scale=$w:$h"
    }

    /** Crop to w x h starting at (x, y). */
    data class Crop(val w: Int, val h: Int, val x: Int = 0, val y: Int = 0) : VideoFilter() {
        override fun toFfmpeg() = "crop=$w:$h:$x:$y"
    }

    /** Rotate by arbitrary angle in degrees. */
    data class Rotate(val degrees: Float) : VideoFilter() {
        override fun toFfmpeg() = "rotate=${fmt(degrees)}*PI/180"
    }

    /** 90/180/270 rotation and flip. dir: 0=90CCW, 1=90CW, 2=90CCW+flip, 3=90CW+flip */
    data class Transpose(val dir: Int) : VideoFilter() {
        override fun toFfmpeg() = "transpose=$dir"
    }

    // --- Time / speed ---

    /** Fade in from black. Duration in seconds. */
    data class FadeIn(val duration: Float = 1f) : VideoFilter() {
        override fun toFfmpeg() = "fade=in:d=${fmt(duration)}"
    }

    /** Fade out to black. Requires start time or uses end of video. */
    data class FadeOut(val duration: Float = 1f) : VideoFilter() {
        override fun toFfmpeg() = "fade=out:d=${fmt(duration)}:st=0"
    }

    /** Change playback speed. 2.0 = 2x faster, 0.5 = half speed. */
    data class Speed(val factor: Float = 1f) : VideoFilter() {
        override fun toFfmpeg() = "setpts=PTS/${fmt(factor)}"
    }

    /** Change frame rate. */
    data class Fps(val rate: Int) : VideoFilter() {
        override fun toFfmpeg() = "fps=$rate"
    }

    companion object {
        /** Build the `-vf` argument string from a list of filters. */
        fun chain(filters: List<VideoFilter>): String =
            filters.joinToString(",") { it.toFfmpeg() }
    }
}

/**
 * Audio filters for the FFmpeg `-af` argument.
 */
sealed class AudioFilter {
    abstract fun toFfmpeg(): String

    /** Adjust volume in dB. Positive = louder, negative = quieter. */
    data class Volume(val db: Float = 0f) : AudioFilter() {
        override fun toFfmpeg() = "volume=${fmt(db)}dB"
    }

    /** EBU R128 loudness normalization. */
    data object Normalize : AudioFilter() {
        override fun toFfmpeg() = "loudnorm"
    }

    /** Audio fade in. Duration in seconds. */
    data class FadeIn(val duration: Float = 1f) : AudioFilter() {
        override fun toFfmpeg() = "afade=t=in:d=${fmt(duration)}"
    }

    /** Audio fade out. Duration in seconds. */
    data class FadeOut(val duration: Float = 1f) : AudioFilter() {
        override fun toFfmpeg() = "afade=t=out:d=${fmt(duration)}"
    }

    /** Change audio speed. Range: 0.5 to 100.0 (atempo limitation). */
    data class Speed(val factor: Float = 1f) : AudioFilter() {
        override fun toFfmpeg() = "atempo=${fmt(factor)}"
    }

    companion object {
        fun chain(filters: List<AudioFilter>): String =
            filters.joinToString(",") { it.toFfmpeg() }
    }
}

/** Format a float without trailing zeros, always using '.' as decimal separator. */
private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
