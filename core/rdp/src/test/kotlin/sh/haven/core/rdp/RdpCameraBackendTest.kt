package sh.haven.core.rdp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.rdp.RdpCameraDevice
import sh.haven.rdp.RdpCameraFormat
import sh.haven.rdp.RdpCameraMode
import java.io.Closeable
import java.nio.ByteBuffer

class RdpCameraBackendTest {

    private class FakeCameraSource : RdpCameraBackend.CameraSourceAdapter {
        var permitted: Boolean = false
        var opens: Int = 0
        var closes: Int = 0
        var shouldEncode: (() -> Boolean)? = null
        var emitFrame: ((ByteArray) -> Unit)? = null

        override fun hasCameraPermission(): Boolean = permitted

        override fun listCameras(): List<RdpCameraDevice> = listOf(
            RdpCameraDevice(
                id = "front",
                name = "Haven Front Camera",
                modes = RdpCameraBackend.buildModesForSupportedSizes(setOf(640 to 480, 1280 to 720)),
            ),
        )

        override fun openStream(
            cameraId: String,
            mode: RdpCameraMode,
            shouldEncodeFrame: () -> Boolean,
            onFrame: (ByteArray) -> Unit,
        ): Closeable? {
            if (!permitted) return null
            opens += 1
            shouldEncode = shouldEncodeFrame
            emitFrame = onFrame
            return Closeable { closes += 1 }
        }
    }

    @Test
    fun `startStream declines without permission and succeeds once permitted`() {
        val fake = FakeCameraSource()
        var pending = false
        val pushed = mutableListOf<Pair<String, ByteArray>>()
        val logs = mutableListOf<String>()
        val backend = RdpCameraBackend(
            source = fake,
            hasPendingRequest = { pending },
            pushSample = { id, bytes ->
                pushed.add(id to bytes)
                true
            },
            log = { _, msg -> logs.add(msg) },
        )

        val mode = RdpCameraMode(RdpCameraFormat.MJPG, 640u, 480u, 30u)
        assertFalse(backend.startStream("front", mode))
        assertEquals(0, fake.opens)

        fake.permitted = true
        assertTrue(backend.startStream("front", mode))
        assertEquals(1, fake.opens)
        assertFalse(fake.shouldEncode!!.invoke())

        pending = true
        assertTrue(fake.shouldEncode!!.invoke())
        val sample = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        fake.emitFrame!!.invoke(sample)
        assertEquals(1, pushed.size)
        assertEquals("front", pushed[0].first)
        assertArrayEquals(sample, pushed[0].second)

        backend.close()
        assertEquals(1, fake.closes)
    }

    @Test
    fun `packYuv420ToSemiPlanar handles padded strides and both NV12 and NV21 chroma order`() {
        // 4x2 YUV420 frame with rowStride=6 on luma and pixelStride=2, rowStride=4 on chroma.
        val yBuf = ByteBuffer.wrap(
            byteArrayOf(
                10, 11, 12, 13, 99, 99,
                20, 21, 22, 23, 99, 99,
            ),
        )
        val uBuf = ByteBuffer.wrap(byteArrayOf(101, 0, 102, 0))
        val vBuf = ByteBuffer.wrap(byteArrayOf(111, 0, 112, 0))

        val nv12 = RdpCameraBackend.packYuv420ToSemiPlanar(
            width = 4,
            height = 2,
            yBuffer = yBuf,
            yRowStride = 6,
            yPixelStride = 1,
            uBuffer = uBuf,
            uRowStride = 4,
            uPixelStride = 2,
            vBuffer = vBuf,
            vRowStride = 4,
            vPixelStride = 2,
            uvOrderNv21 = false,
        )
        assertArrayEquals(
            byteArrayOf(10, 11, 12, 13, 20, 21, 22, 23, 101, 111, 102, 112),
            nv12,
        )

        val nv21 = RdpCameraBackend.packYuv420ToSemiPlanar(
            width = 4,
            height = 2,
            yBuffer = yBuf,
            yRowStride = 6,
            yPixelStride = 1,
            uBuffer = uBuf,
            uRowStride = 4,
            uPixelStride = 2,
            vBuffer = vBuf,
            vRowStride = 4,
            vPixelStride = 2,
            uvOrderNv21 = true,
        )
        assertArrayEquals(
            byteArrayOf(10, 11, 12, 13, 20, 21, 22, 23, 111, 101, 112, 102),
            nv21,
        )
    }

    @Test
    fun `buildModesForSupportedSizes ranks MJPG before NV12 and caps NV12 at 640x480`() {
        val modes = RdpCameraBackend.buildModesForSupportedSizes(setOf(1280 to 720, 640 to 480))
        assertEquals(6, modes.size)
        assertEquals(RdpCameraFormat.MJPG, modes[0].format)
        assertEquals(1280u, modes[0].width)
        assertTrue(modes.none { it.format == RdpCameraFormat.NV12 && it.width > 640u })
    }
}
