package sh.haven.core.ffmpeg

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds ffprobe argument lists and parses the resulting JSON into a
 * [MediaInfo]. Uses Android's built-in `org.json`, no extra dependencies.
 */
object ProbeCommand {

    /** Full probe: format + streams + chapters as JSON on stdout. */
    fun fullInfo(input: String): List<String> = listOf(
        "-v", "quiet",
        "-print_format", "json",
        "-show_format",
        "-show_streams",
        "-show_chapters",
        input,
    )

    /** Duration-only probe — cheap and used by preview logic. */
    fun durationOnly(input: String): List<String> = listOf(
        "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        input,
    )

    fun parse(json: String): MediaInfo {
        val root = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
        val format = root.optJSONObject("format")
        val streams = root.optJSONArray("streams") ?: JSONArray()
        val chaptersJson = root.optJSONArray("chapters") ?: JSONArray()

        val videos = mutableListOf<MediaInfo.VideoStream>()
        val audios = mutableListOf<MediaInfo.AudioStream>()
        val subs = mutableListOf<MediaInfo.SubtitleStream>()

        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            when (s.optString("codec_type")) {
                "video" -> videos += parseVideo(s)
                "audio" -> audios += parseAudio(s)
                "subtitle" -> subs += parseSubtitle(s)
            }
        }

        val chapters = mutableListOf<MediaInfo.Chapter>()
        for (i in 0 until chaptersJson.length()) {
            val c = chaptersJson.optJSONObject(i) ?: continue
            chapters += MediaInfo.Chapter(
                startSeconds = c.optString("start_time").toDoubleOrNull() ?: 0.0,
                endSeconds = c.optString("end_time").toDoubleOrNull() ?: 0.0,
                title = c.optJSONObject("tags")?.optString("title")?.takeIf { it.isNotEmpty() },
            )
        }

        return MediaInfo(
            formatName = format?.optString("format_long_name")?.takeIf { it.isNotEmpty() }
                ?: format?.optString("format_name")?.takeIf { it.isNotEmpty() },
            durationSeconds = format?.optString("duration")?.toDoubleOrNull(),
            bitrateBps = format?.optString("bit_rate")?.toLongOrNull(),
            sizeBytes = format?.optString("size")?.toLongOrNull(),
            videoStreams = videos,
            audioStreams = audios,
            subtitleStreams = subs,
            chapters = chapters,
            rawJson = json,
        )
    }

    private fun parseVideo(s: JSONObject): MediaInfo.VideoStream {
        val disp = s.optJSONObject("disposition")
        return MediaInfo.VideoStream(
            index = s.optInt("index"),
            codec = s.optString("codec_name").takeIf { it.isNotEmpty() },
            width = s.optInt("width").takeIf { it > 0 },
            height = s.optInt("height").takeIf { it > 0 },
            pixelFormat = s.optString("pix_fmt").takeIf { it.isNotEmpty() },
            frameRate = parseRational(s.optString("avg_frame_rate"))
                ?: parseRational(s.optString("r_frame_rate")),
            bitrateBps = s.optString("bit_rate").toLongOrNull(),
            isAttachedPic = disp?.optInt("attached_pic") == 1,
        )
    }

    private fun parseAudio(s: JSONObject): MediaInfo.AudioStream {
        val tags = s.optJSONObject("tags")
        return MediaInfo.AudioStream(
            index = s.optInt("index"),
            codec = s.optString("codec_name").takeIf { it.isNotEmpty() },
            channels = s.optInt("channels").takeIf { it > 0 },
            channelLayout = s.optString("channel_layout").takeIf { it.isNotEmpty() },
            sampleRate = s.optString("sample_rate").toIntOrNull(),
            bitrateBps = s.optString("bit_rate").toLongOrNull(),
            language = tags?.optString("language")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseSubtitle(s: JSONObject): MediaInfo.SubtitleStream {
        val tags = s.optJSONObject("tags")
        return MediaInfo.SubtitleStream(
            index = s.optInt("index"),
            codec = s.optString("codec_name").takeIf { it.isNotEmpty() },
            language = tags?.optString("language")?.takeIf { it.isNotEmpty() },
            title = tags?.optString("title")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseRational(v: String?): Double? {
        if (v.isNullOrEmpty() || v == "0/0") return null
        val parts = v.split("/")
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val num = parts[0].toDoubleOrNull() ?: return null
                val den = parts[1].toDoubleOrNull() ?: return null
                if (den == 0.0) null else num / den
            }
            else -> null
        }
    }
}
