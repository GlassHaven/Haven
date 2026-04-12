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

        // Probe input for video + audio streams (excluding attached pictures like album art)
        val probeResult = ffmpegExecutor.probe(listOf(
            "-v", "error",
            "-show_entries", "stream=codec_type:stream_disposition=attached_pic",
            "-of", "flat", inputPath,
        ))
        val probeOut = probeResult.stdout
        val hasVideoStream = probeOut.contains("codec_type=\"video\"")
        val isAttachedPic = probeOut.contains("attached_pic=1")
        val hasRealVideo = hasVideoStream && !isAttachedPic
        val hasAudio = probeOut.contains("codec_type=\"audio\"")
        Log.w(TAG, "Probe: hasRealVideo=$hasRealVideo hasAudio=$hasAudio stdout=${probeOut.take(300)}")

        // Start ffmpeg: transcode to HLS segments.
        // For video inputs, force H.264 Main profile, level 4.0, max 30fps
        // and max 1920x1080 dimensions so the output works with Android
        // Chrome's MediaCodec (it rejects non-standard resolutions and
        // framerates — e.g. portrait 1344x2992 at 55fps from screen
        // recordings would fail with MEDIA_ERR_SRC_NOT_SUPPORTED).
        val args = buildList {
            add("-y")
            add("-i"); add(inputPath)
            if (hasRealVideo) {
                add("-map"); add("0:v:0")
                if (hasAudio) {
                    add("-map"); add("0:a:0?")
                    add("-c:a"); add("aac"); add("-b:a"); add("128k")
                } else {
                    add("-an")
                }
                add("-c:v"); add("libx264")
                add("-preset"); add("ultrafast")
                add("-tune"); add("zerolatency")
                add("-profile:v"); add("main")
                add("-level"); add("4.0")
                add("-pix_fmt"); add("yuv420p")
                // Scale to fit within 1920x1080 (or the other way for portrait),
                // preserving aspect ratio. Even dimensions required by libx264.
                add("-vf"); add("scale='min(1920,iw)':'min(1080,ih)':force_original_aspect_ratio=decrease,scale=trunc(iw/2)*2:trunc(ih/2)*2")
                // Cap framerate at 30fps — screen recordings at 54.80fps
                // trip MediaCodec on some Android devices
                add("-r"); add("30")
            } else {
                // Audio-only: map only audio stream to avoid decoding
                // attached pictures (album art) that lack a decoder
                add("-map"); add("0:a:0?")
                add("-c:a"); add("aac"); add("-b:a"); add("128k")
            }
            add("-f"); add("hls")
            add("-hls_time"); add("2")
            add("-hls_list_size"); add("0")              // 0 = keep all segments (VOD mode)
            add("-hls_playlist_type"); add("vod")        // writes #EXT-X-PLAYLIST-TYPE:VOD
            add("-hls_flags"); add("independent_segments")
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
         justify-content: center; min-height: 100vh; font-family: sans-serif; color: #fff; }
  video { max-width: 100%; max-height: 100vh; }
  .info { position: fixed; top: 8px; left: 8px; color: #888; font-size: 12px; pointer-events: none; }
  /* Overlay shown while the video is muted — tap to unmute and keep playing. */
  #unmute {
    position: fixed; top: 16px; right: 16px;
    background: rgba(0, 0, 0, 0.7); color: #fff;
    padding: 8px 14px; border-radius: 20px;
    font-size: 14px; cursor: pointer; user-select: none;
    border: 1px solid rgba(255, 255, 255, 0.3);
    display: none;
  }
  #unmute.visible { display: block; }
  /* Error / status banner */
  #status {
    position: fixed; bottom: 16px; left: 50%; transform: translateX(-50%);
    background: rgba(0, 0, 0, 0.8); color: #fff;
    padding: 10px 16px; border-radius: 6px;
    font-size: 13px; max-width: 80%; text-align: center;
    display: none;
  }
  #status.visible { display: block; }
  #status.error { background: rgba(180, 0, 0, 0.9); }
</style>
</head><body>
<div class="info">Haven Stream</div>
<video id="v" controls autoplay muted playsinline></video>
<div id="unmute">🔇 Tap to unmute</div>
<div id="status"></div>
<script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
<script>
  const video = document.getElementById('v');
  const unmuteBtn = document.getElementById('unmute');
  const statusEl = document.getElementById('status');
  const src = '/stream.m3u8';

  function showStatus(msg, isError) {
    statusEl.textContent = msg;
    statusEl.className = 'visible' + (isError ? ' error' : '');
  }
  function hideStatus() { statusEl.className = ''; }

  function showUnmute() { unmuteBtn.classList.add('visible'); }
  function hideUnmute() { unmuteBtn.classList.remove('visible'); }

  unmuteBtn.addEventListener('click', () => {
    video.muted = false;
    video.play().catch(() => {});
    hideUnmute();
  });

  video.addEventListener('volumechange', () => {
    if (!video.muted) hideUnmute();
  });

  video.addEventListener('playing', () => {
    hideStatus();
    // If we're playing muted, surface the unmute hint
    if (video.muted) showUnmute();
  });

  video.addEventListener('error', () => {
    const err = video.error;
    showStatus('Video error: ' + (err ? 'code ' + err.code : 'unknown'), true);
  });

  function tryPlay() {
    // Start muted so Chrome allows autoplay, then show the unmute hint.
    video.muted = true;
    video.play().catch(e => {
      console.warn('autoplay failed:', e);
      showStatus('Tap the play button to start', false);
    });
  }

  if (video.canPlayType('application/vnd.apple.mpegurl')) {
    // Safari / iOS — native HLS
    video.src = src;
    video.addEventListener('loadedmetadata', tryPlay, { once: true });
  } else if (Hls.isSupported()) {
    const hls = new Hls();
    hls.on(Hls.Events.ERROR, (_, data) => {
      console.warn('hls.js error:', data);
      if (data.fatal) {
        showStatus('Stream error: ' + data.details, true);
      }
    });
    hls.on(Hls.Events.MANIFEST_PARSED, tryPlay);
    hls.loadSource(src);
    hls.attachMedia(video);
  } else {
    showStatus('HLS is not supported in this browser', true);
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
