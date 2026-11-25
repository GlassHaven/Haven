package sh.haven.core.rdp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import sh.haven.rdp.AudioCallback
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Per-connection RDP sound routing mode (#890). */
enum class RdpSoundMode {
    /** Stream remote audio over `RDPSND` / `AUDIO_PLAYBACK_DVC` and play locally. */
    PLAY_ON_DEVICE,

    /** Set `INFO_REMOTECONSOLEAUDIO` so audio plays on the remote host's speakers. */
    PLAY_ON_SERVER,

    /** Set `INFO_NOAUDIOPLAYBACK` and attach no audio virtual channel. */
    DISABLED,
}

/** Abstraction over Android [AudioTrack] so [RdpAudioPlayer] is unit-testable on the JVM. */
internal interface PcmAudioSink : Closeable {
    fun configure(sampleRate: Int, channels: Int)
    fun write(pcmS16Le: ByteArray)
    fun setStereoVolume(left: Float, right: Float)
    fun reset()
}

private class AndroidAudioTrackSink(
    private val log: (String, String) -> Unit,
) : PcmAudioSink {
    private var track: AudioTrack? = null
    private var currentRate = 0
    private var currentChannels = 0
    private var leftGain = 1f
    private var rightGain = 1f

    override fun configure(sampleRate: Int, channels: Int) {
        if (track != null && currentRate == sampleRate && currentChannels == channels) return
        releaseTrack()

        val channelMask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                log("E", "Unsupported RDP audio channel count: $channels")
                return
            }
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            log("E", "AudioTrack.getMinBufferSize failed ($minBuf) for ${sampleRate}Hz ${channels}ch")
            return
        }
        // ~100ms floor or 2x hardware minimum keeps jitter underruns down without deep lag.
        val bytesPer100Ms = sampleRate * channels * 2 / 10
        val bufferSize = maxOf(minBuf * 2, bytesPer100Ms)

        try {
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            applyGain(created, leftGain, rightGain)
            created.play()
            track = created
            currentRate = sampleRate
            currentChannels = channels
            log("D", "AudioTrack started: ${sampleRate}Hz ${channels}ch buf=${bufferSize}B")
        } catch (e: Exception) {
            log("E", "AudioTrack init failed (${sampleRate}Hz ${channels}ch): ${e.message}")
        }
    }

    override fun write(pcmS16Le: ByteArray) {
        val t = track ?: return
        try {
            t.write(pcmS16Le, 0, pcmS16Le.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            log("E", "AudioTrack write failed: ${e.message}")
        }
    }

    override fun setStereoVolume(left: Float, right: Float) {
        leftGain = left.coerceIn(0f, 1f)
        rightGain = right.coerceIn(0f, 1f)
        track?.let { applyGain(it, leftGain, rightGain) }
    }

    @Suppress("DEPRECATION")
    private fun applyGain(t: AudioTrack, left: Float, right: Float) {
        try {
            t.setStereoVolume(left, right)
        } catch (_: Exception) {
        }
    }

    override fun reset() {
        val t = track ?: return
        try {
            t.pause()
            t.flush()
            t.play()
        } catch (_: Exception) {
        }
    }

    override fun close() {
        releaseTrack()
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        currentRate = 0
        currentChannels = 0
        try {
            t.pause()
            t.flush()
        } catch (_: Exception) {
        }
        try {
            t.release()
        } catch (_: Exception) {
        }
    }
}

/**
 * Non-blocking [AudioCallback] bridge from IronRDP's `rdpsnd` / `AUDIO_PLAYBACK_DVC`
 * decoder to a dedicated playback worker thread (#890).
 *
 * Native audio callbacks fire inline on the `rdp-session` decode thread; writing to
 * [AudioTrack] directly there would stall video decoding whenever the audio ring
 * buffer fills. Queuing onto a bounded ring (~32 wave blocks) keeps the session
 * loop lock-free and bounds worst-case audio lag by dropping the oldest queued
 * block on overflow.
 */
internal class RdpAudioPlayer(
    private val sessionId: String,
    private val log: (String, String) -> Unit = { _, _ -> },
    queueCapacity: Int = 32,
    private val sinkFactory: () -> PcmAudioSink = { AndroidAudioTrackSink(log) },
) : AudioCallback, Closeable {

    private sealed interface Event {
        data class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int) : Event
        data class Volume(val left: Float, val right: Float) : Event
        data object Reset : Event
        data object Stop : Event
    }

    private val queue = ArrayBlockingQueue<Event>(queueCapacity.coerceAtLeast(2))
    private val closed = AtomicBoolean(false)
    private val workerStarted = AtomicBoolean(false)

    @Volatile
    private var workerThread: Thread? = null

    private fun ensureWorker() {
        if (closed.get() || !workerStarted.compareAndSet(false, true)) return
        val thread = Thread({ runLoop() }, "rdp-audio-$sessionId").apply {
            isDaemon = true
            start()
        }
        workerThread = thread
    }

    private fun enqueue(event: Event) {
        if (closed.get()) return
        ensureWorker()
        while (!queue.offer(event)) {
            // Drop oldest queued event so real-time audio never falls seconds
            // behind video and never blocks the native session thread.
            val evicted = queue.poll()
            if (evicted == Event.Stop) {
                queue.offer(Event.Stop)
                return
            }
        }
    }

    override fun onAudioPcm(pcmS16le: ByteArray, sampleRate: UInt, channels: UShort) {
        if (closed.get() || pcmS16le.isEmpty()) return
        val rate = sampleRate.toInt()
        val ch = channels.toInt()
        if (rate <= 0 || ch !in 1..2) return
        enqueue(Event.Pcm(pcmS16le, rate, ch))
    }

    override fun onAudioVolume(left: Float, right: Float) {
        if (closed.get()) return
        enqueue(Event.Volume(left, right))
    }

    override fun onAudioClose() {
        if (closed.get() || !workerStarted.get()) return
        enqueue(Event.Reset)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val thread = workerThread ?: return
        queue.clear()
        queue.offer(Event.Stop)
        try {
            thread.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        workerThread = null
    }

    private fun runLoop() {
        val sink = sinkFactory()
        try {
            while (true) {
                val ev = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                when (ev) {
                    is Event.Pcm -> {
                        if (!closed.get()) {
                            sink.configure(ev.sampleRate, ev.channels)
                            sink.write(ev.data)
                        }
                    }
                    is Event.Volume -> sink.setStereoVolume(ev.left, ev.right)
                    Event.Reset -> sink.reset()
                    Event.Stop -> break
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { sink.close() }
        }
    }
}
