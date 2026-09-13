package sh.haven.core.local

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.proot.PackageOps
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Output-only audio bridge for proot guest apps (#257).
 *
 * Runs a PulseAudio daemon inside the active distro (system mode, as the
 * proot fake-root) with a null sink whose monitor is exposed as raw PCM
 * over a loopback TCP port via `module-simple-protocol-tcp`. Guest apps
 * play to PulseAudio the normal way (`PULSE_SERVER`); their audio lands in
 * the null sink, and this class reads the monitor PCM off the loopback
 * port and plays it through an Android [AudioTrack].
 *
 * The recipe was validated on-device (2026-06-24): PulseAudio `--system`
 * starts headless in proot (the D-Bus warnings are harmless), the null
 * sink reports s16le/48000/2ch, and the simple-protocol-tcp monitor streams
 * real PCM. proot does not namespace the network, so the guest's
 * 127.0.0.1:[PCM_PORT] is the same loopback the app connects to.
 *
 * Output only — no mic capture (that would need RECORD_AUDIO + consent).
 */
@Singleton
class AudioBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prootManager: ProotManager,
    preferences: UserPreferencesRepository,
) {
    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    data class Status(
        val state: State,
        val port: Int,
        val bytesStreamed: Long,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status(State.STOPPED, 0, 0))
    val status: StateFlow<Status> = _status.asStateFlow()

    // PCM format — must match the PulseAudio simple-protocol-tcp module args.
    private val sampleRate = 48000
    private val pcmPort = 4712

    private var paProcess: Process? = null
    private var readerThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var bytesStreamed = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Pref-reactive lifecycle: run while audioBridgeEnabled is on (auto-
        // resumes on app launch), stop when turned off. MCP start/stop override
        // manually. Default off emits first → a harmless no-op stop().
        scope.launch {
            preferences.audioBridgeEnabled.distinctUntilChanged().collect { on ->
                if (on) start() else stop()
            }
        }
    }

    /**
     * PulseAudio launch recipe (validated on-device). `exec` so the proot
     * launcher process *is* PulseAudio (clean to signal); `--disallow-exit`
     * + `--exit-idle-time=-1` keep it resident. Writes a PULSE_SERVER export
     * to /etc/profile.d so terminal / run_in_proot apps (login shells) pick
     * up the daemon, mirroring the DISPLAY export the native compositor does.
     */
    private fun paCommand(): String =
        "mkdir -p /var/run/pulse /etc/profile.d ; " +
            // Clear any stale pid file: PulseAudio can't verify the recorded pid
            // under proot ("Could not check to see if pid N is a pulseaudio
            // process. Assuming it is") and refuses to start. reapOrphanPulse
            // kills stray *processes*; this removes the leftover *file*.
            "rm -f /var/run/pulse/pid /run/pulse/pid 2>/dev/null ; " +
            "echo 'export PULSE_SERVER=unix:/var/run/pulse/native' > /etc/profile.d/pulse.sh ; " +
            "exec pulseaudio --system --disallow-exit=true --exit-idle-time=-1 --disable-shm=true -n " +
            "--log-target=newfile:/tmp/haven-pulse.log " +
            "-L \"module-null-sink sink_name=haven_out rate=$sampleRate channels=2\" " +
            "-L \"module-native-protocol-unix socket=/var/run/pulse/native auth-anonymous=true\" " +
            "-L \"module-simple-protocol-tcp record=true source=haven_out.monitor " +
            "format=s16le rate=$sampleRate channels=2 listen=127.0.0.1 port=$pcmPort\""

    /** The PULSE_SERVER value desktop launch scripts should export. */
    val pulseServerEnv: String = "unix:/var/run/pulse/native"

    val isRunning: Boolean get() = running

    /**
     * Install PulseAudio if missing, launch the daemon in the active distro,
     * and start pumping its monitor PCM to [AudioTrack]. Idempotent: a no-op
     * if already running. Suspends for the (one-time) package install.
     */
    suspend fun start() {
        if (running) return
        _status.value = Status(State.STARTING, pcmPort, 0)
        try {
            if (!prootManager.isRootfsInstalled) {
                fail("No Linux distro installed"); return
            }
            // Ensure pulseaudio is present (one-time per distro).
            val (whichOut, _) = prootManager.runCommandInProot("command -v pulseaudio || true")
            if (!whichOut.contains("pulseaudio")) {
                val ops = PackageOps.forFamily(prootManager.activeDistro.family)
                val install = ops.installCmd(listOf("pulseaudio", "pulseaudio-utils"))
                Log.d(TAG, "[audio] installing pulseaudio: $install")
                val (out, code) = prootManager.runCommandInProot("${ops.updateCmd()} >/dev/null 2>&1 ; $install 2>&1")
                if (!ops.installSucceeded(out) &&
                    prootManager.runCommandInProot("command -v pulseaudio || true").first.let { !it.contains("pulseaudio") }
                ) {
                    fail("PulseAudio install failed (exit $code): ${out.takeLast(300)}"); return
                }
            }
            reapOrphanPulse()
            running = true
            bytesStreamed = 0
            paProcess = prootManager.startCommandInProot(paCommand())
            Log.d(TAG, "[audio] PulseAudio launched, reading PCM from 127.0.0.1:$pcmPort")
            readerThread = Thread({ readLoop() }, "haven-audio-reader").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            Log.e(TAG, "[audio] start failed", e)
            fail(e.message ?: "audio bridge start failed")
            stop()
        }
    }

    /** Tear down: stop the reader, release AudioTrack/focus, kill PulseAudio. */
    fun stop() {
        running = false
        readerThread?.interrupt()
        readerThread = null
        try { paProcess?.destroyForcibly() } catch (_: Exception) {}
        paProcess = null
        reapOrphanPulse()
        _status.value = Status(State.STOPPED, pcmPort, bytesStreamed)
        Log.d(TAG, "[audio] stopped (streamed $bytesStreamed bytes)")
    }

    fun statusNow(): Status = _status.value

    private fun fail(msg: String) {
        running = false
        _status.value = Status(State.ERROR, pcmPort, bytesStreamed, msg)
    }

    /**
     * Connect to the PulseAudio simple-protocol-tcp port (retrying while the
     * daemon comes up) and stream PCM to AudioTrack until [stop].
     */
    private fun readLoop() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val focusReq = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(8192)
        val trackBuf = trackBufferSizeBytes(minBuf)
        var track: AudioTrack? = null
        var sock: Socket? = null
        try {
            audioManager.requestAudioFocus(focusReq)
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(trackBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()

            // Retry-connect while PulseAudio binds the port (~up to 10s).
            val deadline = System.currentTimeMillis() + 10_000
            while (running && sock == null) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress("127.0.0.1", pcmPort), 1000)
                    sock = s
                } catch (_: Exception) {
                    if (System.currentTimeMillis() > deadline) {
                        fail("PulseAudio PCM port $pcmPort never came up"); return
                    }
                    Thread.sleep(400)
                }
            }
            val input = sock?.getInputStream() ?: return
            _status.value = Status(State.RUNNING, pcmPort, 0)
            // #442: a reporter asked for shared-memory passthrough to cut audio
            // latency, and the honest answer was that nobody had measured where
            // the latency is. These two numbers are most of the answer on their
            // own: the render buffer is a latency floor that no change of
            // transport can get under. The underrun count during real audio is
            // how we check the headroom factor is still enough.
            Log.i(
                TAG,
                "[audio] AudioTrack buffer ${trackBuf}B = ${pcmBufferMillis(trackBuf, sampleRate)}ms " +
                    "(min ${minBuf}B = ${pcmBufferMillis(minBuf, sampleRate)}ms) at ${sampleRate}Hz stereo s16le",
            )
            val buf = ByteArray(16 * 1024)
            // Which of the two calls the loop actually sits in decides what to
            // fix. Blocked in write → we are render-bound, and the AudioTrack
            // buffer above is the thing to attack. Blocked in read → the guest
            // is not producing fast enough and the transport is worth looking at.
            var readUs = 0L
            var writeUs = 0L
            var windowBytes = 0L
            while (running) {
                val t0 = System.nanoTime()
                val n = input.read(buf)
                val t1 = System.nanoTime()
                if (n < 0) break
                if (n > 0) {
                    track.write(buf, 0, n)
                    readUs += (t1 - t0) / 1000
                    writeUs += (System.nanoTime() - t1) / 1000
                    windowBytes += n
                    bytesStreamed += n
                    // Cheap throttle on status churn — refresh the count ~1×/sec worth.
                    if ((bytesStreamed and 0x3FFFF) < n) {
                        _status.value = Status(State.RUNNING, pcmPort, bytesStreamed)
                        val secs = windowBytes.toDouble() / (sampleRate * 2 * 2)
                        Log.i(
                            TAG,
                            "[audio] over ${"%.1f".format(secs)}s of audio: " +
                                "socket read ${readUs / 1000}ms, AudioTrack write ${writeUs / 1000}ms, " +
                                "underruns ${track.underrunCount}",
                        )
                        readUs = 0; writeUs = 0; windowBytes = 0
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "[audio] reader error: ${e.message}")
        } finally {
            try { sock?.close() } catch (_: Exception) {}
            try { track?.stop(); track?.release() } catch (_: Exception) {}
            try { audioManager.abandonAudioFocusRequest(focusReq) } catch (_: Exception) {}
            if (running) {
                // Stream ended unexpectedly (PA died) — reflect it.
                _status.value = Status(State.ERROR, pcmPort, bytesStreamed, "audio stream ended")
                running = false
            }
        }
    }

    /**
     * Kill any leftover PulseAudio in the rootfs. proot's `--kill-on-exit`
     * is unreliable when the launcher is force-destroyed (the ptrace tracee
     * outlives it), so sweep for it by name.
     *
     * This used to say it took the same approach as
     * `DesktopManager.killOrphanedXvnc`. It no longer does: that one filtered
     * on a command-line *argument*, which `ps` does not print, so it matched
     * nothing on every run and has moved to `/proc/<pid>/cmdline` (#501).
     * Matching a process *name* — which is all this does — is not affected by
     * that, so the behaviour here is left alone rather than changed on
     * suspicion. If orphaned PulseAudio is ever observed surviving a stop,
     * `/proc/<pid>/cmdline` is where to look next; it is the more reliable
     * source on Android, where proot-launched binaries can appear under a
     * bracketed name.
     */
    private fun reapOrphanPulse() {
        try {
            val proc = ProcessBuilder(
                "sh", "-c",
                "ps -A 2>/dev/null | grep pulseaudio | grep -v grep | awk '{print \$2}'",
            ).redirectErrorStream(true).start()
            val pids = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            for (pid in pids.lines()) {
                if (pid.isBlank()) continue
                try { ProcessBuilder("kill", "-9", pid.trim()).start().waitFor() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "[audio] reapOrphanPulse failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AudioBridge"
    }
}

/**
 * Milliseconds of audio that [bytes] of PCM represents.
 *
 * Split out and given a test because it is the number the whole #442 question
 * turns on: an AudioTrack buffer is a latency floor, and a floor measured in
 * bytes says nothing until it is expressed in time. Defaults are the format
 * the bridge negotiates with PulseAudio — 16-bit stereo.
 */
internal fun pcmBufferMillis(
    bytes: Int,
    sampleRate: Int,
    channels: Int = 2,
    bytesPerSample: Int = 2,
): Long {
    val bytesPerSecond = sampleRate.toLong() * channels * bytesPerSample
    if (bytesPerSecond <= 0L) return 0L
    return bytes.toLong() * 1000L / bytesPerSecond
}

/**
 * AudioTrack buffer for the bridge's render side: the device minimum with
 * headroom for scheduling jitter, never below [TRACK_BUF_FLOOR_BYTES] —
 * 85 ms of 48 kHz stereo s16le.
 *
 * Measured on CPH2655 (2026-09-13): getMinBufferSize is 23064 B = 120 ms,
 * so any byte floor below 23 KB is dead code there and the headroom factor
 * is the whole answer. The old ×4 put the render buffer at 480 ms; the
 * on-device remeasure with real audio (60 s of sample playback, underrun
 * counter) showed ×2 = 240 ms underruns twice per minute and ×4 = 480 ms
 * zero underruns — the headroom is protecting against PulseAudio's own
 * pipeline fill, not just scheduling jitter. ×3 = 360 ms is the compromise
 * being measured now.
 */
internal const val TRACK_BUF_FLOOR_BYTES = 16 * 1024

internal fun trackBufferSizeBytes(minBuf: Int): Int =
    (minBuf * 3).coerceAtLeast(TRACK_BUF_FLOOR_BYTES)
