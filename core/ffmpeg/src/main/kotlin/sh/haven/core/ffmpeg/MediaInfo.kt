package sh.haven.core.ffmpeg

/**
 * Parsed ffprobe output. Only the fields Haven's UI actually uses are
 * represented — raw JSON stays in [rawJson] for callers that need more.
 */
data class MediaInfo(
    val formatName: String?,
    val durationSeconds: Double?,
    val bitrateBps: Long?,
    val sizeBytes: Long?,
    val videoStreams: List<VideoStream>,
    val audioStreams: List<AudioStream>,
    val subtitleStreams: List<SubtitleStream>,
    val chapters: List<Chapter>,
    val rawJson: String,
) {
    val hasVideo: Boolean get() = videoStreams.any { !it.isAttachedPic }
    val hasAudio: Boolean get() = audioStreams.isNotEmpty()

    data class VideoStream(
        val index: Int,
        val codec: String?,
        val width: Int?,
        val height: Int?,
        val pixelFormat: String?,
        val frameRate: Double?,
        val bitrateBps: Long?,
        val isAttachedPic: Boolean,
    )

    data class AudioStream(
        val index: Int,
        val codec: String?,
        val channels: Int?,
        val channelLayout: String?,
        val sampleRate: Int?,
        val bitrateBps: Long?,
        val language: String?,
    )

    data class SubtitleStream(
        val index: Int,
        val codec: String?,
        val language: String?,
        val title: String?,
    )

    data class Chapter(
        val startSeconds: Double,
        val endSeconds: Double,
        val title: String?,
    )
}
