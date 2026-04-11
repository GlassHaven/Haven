package sh.haven.core.ffmpeg

/**
 * One-tap filter presets for common operations.
 * Each preset maps to a list of VideoFilter and/or AudioFilter instances.
 */
object FilterPresets {

    data class Preset(
        val key: String,
        val label: String,
        val videoFilters: List<VideoFilter> = emptyList(),
        val audioFilters: List<AudioFilter> = emptyList(),
    )

    val STABILIZE = Preset(
        key = "stabilize",
        label = "Stabilize",
        videoFilters = listOf(VideoFilter.Stabilize()),
    )

    val FIX_COLORS = Preset(
        key = "fix_colors",
        label = "Fix Colors",
        videoFilters = listOf(VideoFilter.AutoColor),
    )

    val ENHANCE = Preset(
        key = "enhance",
        label = "Enhance",
        videoFilters = listOf(VideoFilter.AutoColor, VideoFilter.Sharpen(0.5f)),
    )

    val NORMALIZE_AUDIO = Preset(
        key = "normalize_audio",
        label = "Normalize Audio",
        audioFilters = listOf(AudioFilter.Normalize),
    )

    val all = listOf(STABILIZE, FIX_COLORS, ENHANCE, NORMALIZE_AUDIO)
}
