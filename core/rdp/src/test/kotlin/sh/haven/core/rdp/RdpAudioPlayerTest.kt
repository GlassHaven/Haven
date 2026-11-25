package sh.haven.core.rdp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RdpAudioPlayerTest {

    private class RecordingSink(
        private val blockWriteLatch: CountDownLatch? = null,
    ) : PcmAudioSink {
        val configs = CopyOnWriteArrayList<Pair<Int, Int>>()
        val writes = CopyOnWriteArrayList<ByteArray>()
        val volumes = CopyOnWriteArrayList<Pair<Float, Float>>()
        var resets = 0
        var closedCount = 0
        val wroteSignal = CountDownLatch(1)

        override fun configure(sampleRate: Int, channels: Int) {
            configs.add(sampleRate to channels)
        }

        override fun write(pcmS16Le: ByteArray) {
            blockWriteLatch?.await(2, TimeUnit.SECONDS)
            writes.add(pcmS16Le)
            wroteSignal.countDown()
        }

        override fun setStereoVolume(left: Float, right: Float) {
            volumes.add(left to right)
        }

        override fun reset() {
            resets++
        }

        override fun close() {
            closedCount++
        }
    }

    @Test
    fun `delivers PCM volume and reset events and closes sink on teardown`() {
        val sink = RecordingSink()
        val player = RdpAudioPlayer(
            sessionId = "test-1",
            sinkFactory = { sink },
        )

        val pcm = byteArrayOf(1, 2, 3, 4)
        player.onAudioVolume(0.5f, 0.75f)
        player.onAudioPcm(pcm, 48_000u, 2u)
        player.onAudioClose()

        assertTrue(sink.wroteSignal.await(2, TimeUnit.SECONDS))
        player.close()

        assertEquals(listOf(0.5f to 0.75f), sink.volumes.toList())
        assertEquals(listOf(48_000 to 2), sink.configs.toList())
        assertEquals(1, sink.writes.size)
        assertArrayEquals(pcm, sink.writes[0])
        assertEquals(1, sink.closedCount)
    }

    @Test
    fun `bounded queue drops oldest audio block instead of blocking session thread`() {
        val holdSink = CountDownLatch(1)
        val sink = RecordingSink(blockWriteLatch = holdSink)
        val player = RdpAudioPlayer(
            sessionId = "test-overflow",
            queueCapacity = 2,
            sinkFactory = { sink },
        )

        // Block 1 enters sink.write() and blocks on holdSink; blocks 2..5
        // contend for the 2-slot queue so older blocks are evicted immediately.
        for (i in 1..5) {
            player.onAudioPcm(byteArrayOf(i.toByte(), 0), 44_100u, 1u)
        }
        holdSink.countDown()
        Thread.sleep(150)
        player.close()

        // Must never exceed 1 in-flight + 2 queued = 3 writes, and the newest
        // sample (5) must survive.
        assertTrue("writes=${sink.writes.size}", sink.writes.size in 1..3)
        assertEquals(5.toByte(), sink.writes.last()[0])
        assertEquals(1, sink.closedCount)
    }
}
