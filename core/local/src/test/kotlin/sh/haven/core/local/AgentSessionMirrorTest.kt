package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentSessionMirrorTest {

    @Test
    fun `setTee replays buffered ring output before delivering subsequent live chunks`() {
        val mirror = AgentSessionMirror()
        val uiSink = ByteArrayOutputStream()
        val cb = mirror.mirror("s1") { d, o, l -> uiSink.write(d, o, l) }

        val first = "root@localhost:~# ".toByteArray()
        cb(first, 0, first.size)

        val teeOut = ByteArrayOutputStream()
        val teeSink: (ByteArray, Int, Int) -> Unit = { d, o, l -> teeOut.write(d, o, l) }
        mirror.setTee("s1", teeSink)

        val second = "echo ok\r\nok\r\n".toByteArray()
        cb(second, 0, second.size)

        assertEquals("root@localhost:~# echo ok\r\nok\r\n", teeOut.toString(Charsets.UTF_8.name()))
        assertEquals("root@localhost:~# echo ok\r\nok\r\n", uiSink.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `re-registering the identical tee sink does not duplicate already delivered bytes`() {
        val mirror = AgentSessionMirror()
        val cb = mirror.mirror("s1") { _, _, _ -> }

        val prompt = "prompt$ ".toByteArray()
        cb(prompt, 0, prompt.size)

        val teeOut = ByteArrayOutputStream()
        val teeSink: (ByteArray, Int, Int) -> Unit = { d, o, l -> teeOut.write(d, o, l) }
        mirror.setTee("s1", teeSink)
        mirror.setTee("s1", teeSink)

        assertEquals("prompt$ ", teeOut.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `setTee with replayExisting false streams only chunks arriving after registration`() {
        val mirror = AgentSessionMirror()
        val cb = mirror.mirror("s1") { _, _, _ -> }

        val before = "before\n".toByteArray()
        cb(before, 0, before.size)

        val teeOut = ByteArrayOutputStream()
        mirror.setTee("s1", { d, o, l -> teeOut.write(d, o, l) }, replayExisting = false)

        val after = "after\n".toByteArray()
        cb(after, 0, after.size)

        assertEquals("after\n", teeOut.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `concurrent PTY mirror writes and setTee preserve exact causal byte stream without gap or duplicate`() {
        repeat(25) { iter ->
            val mirror = AgentSessionMirror(scrollbackBytes = 512 * 1024)
            val sessionId = "race-$iter"
            val cb = mirror.mirror(sessionId) { _, _, _ -> }
            val teeOut = ByteArrayOutputStream()
            val startLatch = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val totalLines = 400
            val expected = StringBuilder()
            for (i in 0 until totalLines) {
                expected.append("line-$i\n")
            }

            try {
                val writer = pool.submit {
                    startLatch.await(5, TimeUnit.SECONDS)
                    for (i in 0 until totalLines) {
                        val bytes = "line-$i\n".toByteArray()
                        cb(bytes, 0, bytes.size)
                    }
                }
                val attacher = pool.submit {
                    startLatch.await(5, TimeUnit.SECONDS)
                    Thread.yield()
                    mirror.setTee(sessionId, { d, o, l ->
                        synchronized(teeOut) {
                            teeOut.write(d, o, l)
                        }
                    })
                }
                startLatch.countDown()
                writer.get(5, TimeUnit.SECONDS)
                attacher.get(5, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }

            assertEquals(expected.toString(), teeOut.toString(Charsets.UTF_8.name()))
        }
    }

    @Test
    fun `remove clears ring and tee state`() {
        val mirror = AgentSessionMirror()
        val cb = mirror.mirror("s1") { _, _, _ -> }
        val bytes = "hello".toByteArray()
        cb(bytes, 0, bytes.size)

        mirror.remove("s1")
        assertNull(mirror.snapshot("s1"))
    }
}
