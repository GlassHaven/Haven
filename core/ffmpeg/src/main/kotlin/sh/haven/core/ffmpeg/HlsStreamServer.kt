package sh.haven.core.ffmpeg

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val TAG = "HlsStreamServer"

/**
 * Streams media via HLS (HTTP Live Streaming).
 *
 * Pipeline: input → ffmpeg (HLS muxer) → .m3u8 + .ts segments → HTTP server
 *
 * Any device on the local network can play the stream by opening
 * `http://<phone-ip>:<port>/` in a browser (serves an HTML5 player)
 * or pointing a media player at `http://<phone-ip>:<port>/stream.m3u8`.
 */
@Singleton
class HlsStreamServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ffmpegExecutor: FfmpegExecutor,
) : Closeable {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var ffmpegJob: FfmpegJob? = null
    private var hlsDir: File? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * Start streaming a media file.
     *
     * @param inputPath Absolute path to the input file
     * @param preferredPort Port to listen on (0 = auto)
     * @return The port the server is listening on
     */
    fun startFile(inputPath: String, preferredPort: Int = 8080): Int {
        stop()

        val dir = File(context.cacheDir, "hls_stream").apply { mkdirs() }
        // Clean old segments
        dir.listFiles()?.forEach { it.delete() }
        hlsDir = dir

        val playlistPath = File(dir, "stream.m3u8").absolutePath

        // Probe input to determine if it has a real video stream
        // (exclude attached pictures like album art in FLAC/MP3)
        val probeResult = ffmpegExecutor.probe(listOf(
            "-v", "error",
            "-select_streams", "v",
            "-show_entries", "stream=codec_type:stream_disposition=attached_pic",
            "-of", "flat", inputPath,
        ))
        // flat format: streams.stream.0.disposition.attached_pic=1 for album art
        val probeOut = probeResult.stdout
        val hasVideoStream = probeOut.contains("codec_type=\"video\"")
        val isAttachedPic = probeOut.contains("attached_pic=1")
        val hasRealVideo = hasVideoStream && !isAttachedPic
        Log.w(TAG, "Probe: hasRealVideo=$hasRealVideo (video=$hasVideoStream attached=$isAttachedPic) stdout=${probeOut.take(300)}")

        // Start ffmpeg: transcode to HLS segments
        val args = buildList {
            add("-y")
            add("-i"); add(inputPath)
            if (hasRealVideo) {
                add("-c:v"); add("libx264")
                add("-preset"); add("ultrafast")
                add("-tune"); add("zerolatency")
                add("-c:a"); add("aac"); add("-b:a"); add("128k")
            } else {
                // Audio-only: map only audio stream to avoid decoding
                // attached pictures (album art) that lack a decoder
                add("-map"); add("0:a")
                add("-c:a"); add("aac"); add("-b:a"); add("128k")
            }
            add("-f"); add("hls")
            add("-hls_time"); add("2")
            add("-hls_list_size"); add("10")
            add("-hls_flags"); add("delete_segments+append_list")
            add("-hls_segment_filename"); add(File(dir, "seg%03d.ts").absolutePath)
            add(playlistPath)
        }

        Log.w(TAG, "Starting ffmpeg HLS: ${args.joinToString(" ")}")
        ffmpegJob = ffmpegExecutor.startJob(args) { line ->
            Log.w(TAG, "ffmpeg: $line")
        }

        // Monitor ffmpeg in a background thread — log if it exits early
        Thread({
            val result = ffmpegJob?.await()
            Log.w(TAG, "ffmpeg exited: code=${result?.exitCode} stderr=${result?.stderr?.take(500)}")
        }, "hls-ffmpeg-monitor").apply { isDaemon = true }.start()

        // Start HTTP server
        val ss = ServerSocket(preferredPort, 10, InetAddress.getByName("0.0.0.0"))
        serverSocket = ss
        port = ss.localPort
        isRunning = true

        serverThread = thread(name = "hls-http", isDaemon = true) {
            Log.i(TAG, "HLS server listening on port $port")
            while (isRunning) {
                try {
                    val client = ss.accept()
                    thread(name = "hls-client", isDaemon = true) {
                        handleClient(client, dir)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        return port
    }

    /**
     * Start streaming from a camera or microphone pipe.
     *
     * @param ffmpegArgs Full ffmpeg args (caller provides -i pipe: or device input)
     * @param preferredPort Port to listen on
     * @return The port the server is listening on
     */
    fun startCustom(ffmpegArgs: List<String>, preferredPort: Int = 8080): Int {
        stop()

        val dir = File(context.cacheDir, "hls_stream").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        hlsDir = dir

        ffmpegJob = ffmpegExecutor.startJob(ffmpegArgs) { line ->
            Log.d(TAG, "ffmpeg: $line")
        }

        val ss = ServerSocket(preferredPort, 10, InetAddress.getByName("0.0.0.0"))
        serverSocket = ss
        port = ss.localPort
        isRunning = true

        serverThread = thread(name = "hls-http", isDaemon = true) {
            Log.i(TAG, "HLS server listening on port $port")
            while (isRunning) {
                try {
                    val client = ss.accept()
                    thread(name = "hls-client", isDaemon = true) {
                        handleClient(client, dir)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        return port
    }

    override fun close() = stop()

    fun stop() {
        isRunning = false
        ffmpegJob?.cancel()
        ffmpegJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        port = 0
        hlsDir?.listFiles()?.forEach { it.delete() }
        hlsDir = null
    }

    private fun handleClient(socket: Socket, hlsDir: File) {
        try {
            socket.use { s ->
                val reader = s.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                // Consume headers
                while (reader.readLine().let { it != null && it.isNotEmpty() }) { /* skip */ }

                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val path = parts[1]

                val out = s.getOutputStream()

                when {
                    path == "/" || path == "/index.html" -> {
                        servePlayerPage(out)
                    }
                    path.endsWith(".m3u8") -> {
                        serveFile(out, File(hlsDir, "stream.m3u8"), "application/vnd.apple.mpegurl")
                    }
                    path.endsWith(".ts") -> {
                        val name = path.substringAfterLast('/')
                        serveFile(out, File(hlsDir, name), "video/MP2T")
                    }
                    else -> {
                        val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                        out.write(response.toByteArray())
                    }
                }
                out.flush()
            }
        } catch (_: Exception) {
            // Client disconnected
        }
    }

    private fun serveFile(out: java.io.OutputStream, file: File, contentType: String) {
        if (!file.exists()) {
            val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
            out.write(response.toByteArray())
            return
        }
        val bytes = file.readBytes()
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-cache\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray())
        out.write(bytes)
    }

    private fun servePlayerPage(out: java.io.OutputStream) {
        val html = """
<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Haven Stream</title>
<style>
  body { margin: 0; background: #000; display: flex; align-items: center;
         justify-content: center; height: 100vh; font-family: sans-serif; }
  video { max-width: 100%; max-height: 100vh; }
  .info { position: fixed; top: 8px; left: 8px; color: #888; font-size: 12px; }
</style>
</head><body>
<div class="info">Haven Stream</div>
<video id="v" controls autoplay playsinline></video>
<script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
<script>
  const video = document.getElementById('v');
  const src = '/stream.m3u8';
  if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = src;
  } else if (Hls.isSupported()) {
    const hls = new Hls({ liveDurationInfinity: true });
    hls.loadSource(src);
    hls.attachMedia(video);
  }
</script>
</body></html>
        """.trimIndent()
        val bytes = html.toByteArray()
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
    }
}
