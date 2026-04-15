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

    /** One entry in a folder-as-playlist stream. */
    data class PlaylistItem(val title: String, val input: String)

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var ffmpegJob: FfmpegJob? = null
    private var hlsDir: File? = null

    /** Non-empty when streaming a folder playlist. */
    @Volatile
    private var playlist: List<PlaylistItem> = emptyList()

    @Volatile
    private var currentIndex: Int = 0

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * Optional observer for ffmpeg stderr lines. Lets callers capture the
     * transcoder output into their own verbose-log buffer (Haven's audit
     * log) alongside the normal Log.w broadcast to logcat.
     */
    @Volatile
    var onStderr: ((String) -> Unit)? = null

    /**
     * Start streaming a media file.
     *
     * @param inputPath Absolute path to the input file
     * @param preferredPort Port to listen on (0 = auto)
     * @return The port the server is listening on
     */
    fun startFile(inputPath: String, preferredPort: Int = 8080): Int {
        stop()
        val dir = prepareHlsDir()
        playlist = emptyList()
        currentIndex = 0
        startFfmpegForInput(inputPath, dir)
        startHttpServer(preferredPort, dir)
        return port
    }

    /**
     * Start streaming a folder playlist. The first item begins playing
     * immediately; the browser UI lets the viewer skip to any other item.
     * Each switch restarts ffmpeg with fresh segments.
     *
     * @param items list of media files to stream, already resolved to local
     *              paths or HTTP loopback URLs
     */
    fun startPlaylist(items: List<PlaylistItem>, preferredPort: Int = 8080): Int {
        require(items.isNotEmpty()) { "playlist must not be empty" }
        stop()
        val dir = prepareHlsDir()
        playlist = items
        currentIndex = 0
        startFfmpegForInput(items[0].input, dir)
        startHttpServer(preferredPort, dir)
        return port
    }

    /** Switch a running playlist stream to a different item. */
    @Synchronized
    fun switchTo(index: Int) {
        val items = playlist
        if (items.isEmpty() || index !in items.indices) return
        val dir = hlsDir ?: return
        ffmpegJob?.cancel()
        ffmpegJob = null
        // Wipe old segments so the player doesn't splice frames together.
        dir.listFiles()?.forEach { if (it.name.startsWith("seg") || it.name.endsWith(".m3u8")) it.delete() }
        currentIndex = index
        startFfmpegForInput(items[index].input, dir)
    }

    private fun prepareHlsDir(): File {
        val dir = File(context.cacheDir, "hls_stream").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        hlsDir = dir
        return dir
    }

    private fun startFfmpegForInput(inputPath: String, dir: File) {
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
                // `ultrafast` disables CABAC + 8x8dct + weight_p and makes
                // libx264 write "Constrained Baseline" into the SPS regardless
                // of `-profile:v main`. Chrome's MSE rejects Constrained
                // Baseline > 720p, so 1080p inputs silently fail with
                // MEDIA_ERR_SRC_NOT_SUPPORTED. `veryfast` is the cheapest
                // preset that actually honours Main profile. The `zerolatency`
                // tune is dropped because it's a live-streaming knob with no
                // benefit in a VOD pipeline and it bloats output ~2.7x.
                add("-preset"); add("veryfast")
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
        onStderr?.invoke("cmd=ffmpeg ${args.joinToString(" ")}")
        val job = ffmpegExecutor.startJob(args) { line ->
            Log.w(TAG, "ffmpeg: $line")
            onStderr?.invoke(line)
        }
        ffmpegJob = job

        // Monitor ffmpeg in a background thread — log if it exits early
        Thread({
            val result = job.await()
            Log.w(TAG, "ffmpeg exited: code=${result.exitCode} stderr=${result.stderr.take(500)}")
        }, "hls-ffmpeg-monitor").apply { isDaemon = true }.start()
    }

    private fun startHttpServer(preferredPort: Int, dir: File) {
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
        val dir = prepareHlsDir()
        playlist = emptyList()
        currentIndex = 0

        ffmpegJob = ffmpegExecutor.startJob(ffmpegArgs) { line ->
            Log.d(TAG, "ffmpeg: $line")
        }

        startHttpServer(preferredPort, dir)
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
        playlist = emptyList()
        currentIndex = 0
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
                    path == "/playlist.json" -> {
                        servePlaylistJson(out)
                    }
                    path.startsWith("/switch") -> {
                        val idx = path.substringAfter("i=", "").substringBefore('&')
                            .toIntOrNull()
                        if (idx != null && idx in playlist.indices) {
                            switchTo(idx)
                            val body = "{\"ok\":true,\"index\":$idx}"
                            val bytes = body.toByteArray()
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n").toByteArray())
                            out.write(bytes)
                        } else {
                            out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        }
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

    private fun servePlaylistJson(out: java.io.OutputStream) {
        val items = playlist
        val json = buildString {
            append("{\"current\":").append(currentIndex).append(",\"items\":[")
            items.forEachIndexed { i, it ->
                if (i > 0) append(',')
                append('"').append(jsonEscape(it.title)).append('"')
            }
            append("]}")
        }
        val bytes = json.toByteArray()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\n\r\n").toByteArray())
        out.write(bytes)
    }

    private fun jsonEscape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
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
  /* Playlist sidebar — hidden when playlist is empty (single-file mode). */
  #playlist {
    position: fixed; top: 0; right: 0; bottom: 0; width: 280px;
    background: rgba(20, 20, 20, 0.92); color: #eee; overflow-y: auto;
    padding: 12px 0; font-size: 13px;
    transform: translateX(100%); transition: transform 0.2s;
    z-index: 10;
  }
  #playlist.visible { transform: translateX(0); }
  #playlist .head { padding: 4px 16px 12px; color: #aaa; font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em; }
  #playlist .item { padding: 10px 16px; cursor: pointer; border-left: 3px solid transparent; word-break: break-word; }
  #playlist .item:hover { background: rgba(255,255,255,0.05); }
  #playlist .item.current { background: rgba(255,255,255,0.08); border-left-color: #4ea1ff; color: #fff; }
  #playlist-toggle {
    position: fixed; top: 16px; right: 16px;
    background: rgba(0,0,0,0.7); color: #fff;
    padding: 8px 12px; border-radius: 20px;
    font-size: 13px; cursor: pointer; user-select: none;
    border: 1px solid rgba(255,255,255,0.3);
    display: none; z-index: 11;
  }
  #playlist-toggle.visible { display: block; }
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
<div id="playlist-toggle">☰ Playlist</div>
<div id="playlist"><div class="head">Playlist</div><div id="playlist-items"></div></div>
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

  // HLS loader — use hls.js where possible, native HLS on Safari.
  // The loader is wrapped so we can tear down and reload on playlist switches.
  let hls = null;
  const nativeHls = video.canPlayType('application/vnd.apple.mpegurl');

  function loadSource() {
    if (nativeHls) {
      video.src = src + '?t=' + Date.now();
      video.addEventListener('loadedmetadata', tryPlay, { once: true });
    } else if (window.Hls && Hls.isSupported()) {
      if (hls) { try { hls.destroy(); } catch(e) {} }
      hls = new Hls();
      hls.on(Hls.Events.ERROR, (_, data) => {
        console.warn('hls.js error:', data);
        if (data.fatal) showStatus('Stream error: ' + data.details, true);
      });
      hls.on(Hls.Events.MANIFEST_PARSED, tryPlay);
      hls.loadSource(src);
      hls.attachMedia(video);
    } else {
      showStatus('HLS is not supported in this browser', true);
    }
  }

  loadSource();

  // Playlist UI — only visible when /playlist.json has entries.
  const playlistEl = document.getElementById('playlist');
  const itemsEl = document.getElementById('playlist-items');
  const toggleBtn = document.getElementById('playlist-toggle');
  toggleBtn.addEventListener('click', () => playlistEl.classList.toggle('visible'));

  function renderPlaylist(data) {
    itemsEl.innerHTML = '';
    if (!data.items || data.items.length === 0) return;
    toggleBtn.classList.add('visible');
    playlistEl.classList.add('visible');
    data.items.forEach((title, i) => {
      const row = document.createElement('div');
      row.className = 'item' + (i === data.current ? ' current' : '');
      row.textContent = (i + 1) + '. ' + title;
      row.addEventListener('click', () => switchTo(i));
      itemsEl.appendChild(row);
    });
  }

  async function switchTo(i) {
    try {
      showStatus('Loading item ' + (i + 1) + '…', false);
      await fetch('/switch?i=' + i);
      // Wait for ffmpeg to produce the first segment before reloading the
      // source. Poll the playlist file for ~6 s.
      for (let attempt = 0; attempt < 30; attempt++) {
        await new Promise(r => setTimeout(r, 200));
        const r = await fetch('/stream.m3u8?t=' + Date.now(), { cache: 'no-store' });
        if (r.ok) {
          const text = await r.text();
          if (text.indexOf('seg') !== -1) break;
        }
      }
      loadSource();
      refreshPlaylist();
    } catch (e) {
      showStatus('Switch failed: ' + e, true);
    }
  }

  async function refreshPlaylist() {
    try {
      const r = await fetch('/playlist.json', { cache: 'no-store' });
      if (r.ok) renderPlaylist(await r.json());
    } catch (e) { /* ignore */ }
  }

  refreshPlaylist();
</script>
</body></html>
        """.trimIndent()
        val bytes = html.toByteArray()
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
    }
}
