package sh.haven.core.rdp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import sh.haven.rdp.MicrophoneCallback
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Android `AudioRecord` backend for `MS-RDPEAI` (`AUDIO_INPUT` dynamic virtual
 * channel, #890).
 *
 * Raw audio samples are never logged (`#518` PII scrub policy).
 */
class RdpMicrophoneBackend internal constructor(
    private val source: MicSourceAdapter,
    private val pushPcm: (pcmS16Le: ByteArray) -> Boolean,
    private val log: (level: String, msg: String) -> Unit = { _, _ -> },
) : MicrophoneCallback, Closeable {

    internal interface MicSourceAdapter {
        fun hasMicPermission(): Boolean
        fun openCapture(
            sampleRate: Int,
            channels: Int,
            packetBytes: Int,
            onPacket: (ByteArray) -> Unit,
        ): Closeable?
    }

    private val activeHandle = AtomicReference<Closeable?>(null)
    private val closed = AtomicBoolean(false)

    fun hasMicrophonePermission(): Boolean = source.hasMicPermission()

    override fun onMicOpen(sampleRate: UInt, channels: UShort, framesPerPacket: UInt): Boolean {
        if (closed.get()) return false
        if (!source.hasMicPermission()) {
            log("D", "RDP microphone capture waiting for RECORD_AUDIO permission")
            return false
        }
        val rate = sampleRate.toInt()
        val ch = channels.toInt()
        val fpp = framesPerPacket.toInt()
        if (rate !in 8_000..48_000 || ch !in 1..2 || fpp !in 1..48_000) {
            log("E", "RDP microphone open rejected invalid params (${rate}Hz ${ch}ch ${fpp}fpp)")
            return false
        }
        val packetBytes = fpp * ch * 2
        stopActiveCapture()

        val handle = runCatching {
            source.openCapture(
                sampleRate = rate,
                channels = ch,
                packetBytes = packetBytes,
                onPacket = { bytes ->
                    if (!closed.get()) {
                        pushPcm(bytes)
                    }
                },
            )
        }.onFailure { err ->
            log("E", "RDP microphone open failed: ${err.message}")
        }.getOrNull() ?: return false

        if (closed.get()) {
            runCatching { handle.close() }
            return false
        }
        activeHandle.getAndSet(handle)?.let { prev ->
            runCatching { prev.close() }
        }
        log("D", "RDP microphone capture active (${rate}Hz ${ch}ch ${fpp}fpp)")
        return true
    }

    override fun onMicClose() {
        if (stopActiveCapture()) {
            log("D", "RDP microphone capture stopped")
        }
    }

    private fun stopActiveCapture(): Boolean {
        val prev = activeHandle.getAndSet(null) ?: return false
        runCatching { prev.close() }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopActiveCapture()
    }

    companion object {
        fun create(
            context: Context,
            sessionId: String,
            pushPcm: (pcmS16Le: ByteArray) -> Boolean,
            log: (level: String, msg: String) -> Unit = { _, _ -> },
        ): RdpMicrophoneBackend {
            return RdpMicrophoneBackend(
                source = AndroidAudioRecordSourceAdapter(
                    appContext = context.applicationContext,
                    sessionId = sessionId,
                    log = log,
                ),
                pushPcm = pushPcm,
                log = log,
            )
        }
    }
}

private class AndroidAudioRecordSourceAdapter(
    private val appContext: Context,
    private val sessionId: String,
    private val log: (level: String, msg: String) -> Unit,
) : RdpMicrophoneBackend.MicSourceAdapter {

    override fun hasMicPermission(): Boolean {
        return appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    override fun openCapture(
        sampleRate: Int,
        channels: Int,
        packetBytes: Int,
        onPacket: (ByteArray) -> Unit,
    ): Closeable? {
        if (!hasMicPermission()) return null
        val channelConfig = if (channels == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            log("E", "AudioRecord.getMinBufferSize returned $minBuf for ${sampleRate}Hz ${channels}ch")
            return null
        }
        val bufferSize = maxOf(minBuf * 2, packetBytes * 4)

        val record = createInitializedRecord(sampleRate, channelConfig, bufferSize) ?: return null
        val running = AtomicBoolean(true)

        try {
            record.startRecording()
        } catch (e: Exception) {
            log("E", "AudioRecord.startRecording failed: ${e.message}")
            runCatching { record.release() }
            return null
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            log("E", "AudioRecord did not enter RECORDSTATE_RECORDING")
            runCatching { record.release() }
            return null
        }

        val worker = Thread({
            val packet = ByteArray(packetBytes)
            var filled = 0
            try {
                while (running.get()) {
                    val n = record.read(packet, filled, packetBytes - filled)
                    if (n > 0) {
                        filled += n
                        if (filled == packetBytes) {
                            onPacket(packet.copyOf())
                            filled = 0
                        }
                    } else if (n < 0) {
                        if (running.get()) {
                            log("E", "AudioRecord.read returned $n")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    log("E", "RDP microphone worker error: ${e.message}")
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
        }, "rdp-mic-$sessionId").apply {
            isDaemon = true
            start()
        }

        return Closeable {
            if (!running.compareAndSet(true, false)) return@Closeable
            runCatching { record.stop() }
            runCatching { worker.join(500L) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createInitializedRecord(
        sampleRate: Int,
        channelConfig: Int,
        bufferSize: Int,
    ): AudioRecord? {
        for (audioSource in intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )) {
            val candidate = runCatching {
                AudioRecord(
                    audioSource,
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }.getOrNull() ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate.release() }
        }
        log("E", "AudioRecord failed to initialize for ${sampleRate}Hz")
        return null
    }
}
