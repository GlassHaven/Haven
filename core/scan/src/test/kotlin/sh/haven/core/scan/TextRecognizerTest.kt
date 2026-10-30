package sh.haven.core.scan

import android.graphics.Bitmap
import android.graphics.Color
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Validates the orchestration [TextRecognizer] wraps around the native ONNX
 * engine without touching JNI. Here we substitute a fake
 * [TextRecognizer.OcrEngineFactory] and verify model extraction, lazy engine
 * lifecycle, frame-hash index caching, and shutdown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextRecognizerTest {

    private val bitmap: Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    private val modelPaths = TrainedDataManager.RapidOcrModelPaths(
        detModel = File("/tmp/det.onnx"),
        recModel = File("/tmp/rec.onnx"),
    )

    @Test
    fun `recognize delegates to the engine and returns newline-joined text`() = runTest {
        val engine = mockk<TextRecognizer.OcrEngine>(relaxed = true) {
            every { detectAndRecognize(any()) } returns listOf(
                OcrLineDetection(OcrBbox(10, 10, 100, 20), "  hello world  ", 0.96f),
                OcrLineDetection(OcrBbox(10, 40, 80, 20), "second line", 0.91f),
            )
        }
        val factory = TextRecognizer.OcrEngineFactory { _, _ -> engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureModels() } returns modelPaths
        }

        val recognizer = TextRecognizer(trainedData, factory)
        val result = recognizer.recognize(bitmap)

        assertEquals("hello world\nsecond line", result)
        val bmpSlot = slot<Bitmap>()
        verify { engine.detectAndRecognize(capture(bmpSlot)) }
        assertEquals(bitmap, bmpSlot.captured)
    }

    @Test
    fun `recognize returns null when the engine produces no detections`() = runTest {
        val engine = mockk<TextRecognizer.OcrEngine>(relaxed = true) {
            every { detectAndRecognize(any()) } returns emptyList()
        }
        val factory = TextRecognizer.OcrEngineFactory { _, _ -> engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureModels() } returns modelPaths
        }

        val recognizer = TextRecognizer(trainedData, factory)

        assertNull(recognizer.recognize(bitmap))
    }

    @Test
    fun `recognizeIndex caches by frame hash until bitmap pixels change or forceRefresh is true`() = runTest {
        var engineCalls = 0
        val engine = mockk<TextRecognizer.OcrEngine>(relaxed = true) {
            every { detectAndRecognize(any()) } answers {
                engineCalls++
                listOf(OcrLineDetection(OcrBbox(5, 5, 40, 12), "Save", 0.95f))
            }
        }
        val factory = TextRecognizer.OcrEngineFactory { _, _ -> engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureModels() } returns modelPaths
        }

        val recognizer = TextRecognizer(trainedData, factory)
        val bmpA = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val idx1 = recognizer.recognizeIndex(bmpA, forceRefresh = false)
        val idx2 = recognizer.recognizeIndex(bmpA, forceRefresh = false)

        assertSame(idx1, idx2)
        assertEquals(1, engineCalls)

        // Mutating a sampled pixel invalidates the frame hash and re-runs OCR.
        val bmpB = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.RED)
        }
        recognizer.recognizeIndex(bmpB, forceRefresh = false)
        assertEquals(2, engineCalls)

        // forceRefresh bypasses cache even on identical pixels.
        recognizer.recognizeIndex(bmpB, forceRefresh = true)
        assertEquals(3, engineCalls)
    }

    @Test
    fun `engine is initialised once across calls and recreated after shutdown`() = runTest {
        var created = 0
        val engine = mockk<TextRecognizer.OcrEngine>(relaxed = true) {
            every { detectAndRecognize(any()) } returns listOf(
                OcrLineDetection(OcrBbox(0, 0, 20, 10), "x", 0.9f),
            )
        }
        val factory = TextRecognizer.OcrEngineFactory { _, _ -> created++; engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureModels() } returns modelPaths
        }

        val recognizer = TextRecognizer(trainedData, factory)
        recognizer.recognize(bitmap)
        recognizer.recognize(bitmap)
        assertEquals(1, created)
        coVerify(exactly = 2) { trainedData.ensureModels() }

        recognizer.shutdown()
        recognizer.recognize(bitmap)

        verify { engine.close() }
        assertEquals(2, created)
    }

    @Test
    fun `recognizeIndex tolerates HDMI quantization noise and mouse wobbler outside text boxes`() = runTest {
        var engineCalls = 0
        val engine = mockk<TextRecognizer.OcrEngine>(relaxed = true) {
            every { detectAndRecognize(any()) } answers {
                engineCalls++
                listOf(
                    OcrLineDetection(OcrBbox(40, 40, 200, 28), "TerminalReady", 0.97f),
                )
            }
        }
        val factory = TextRecognizer.OcrEngineFactory { _, _ -> engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureModels() } returns modelPaths
        }
        val recognizer = TextRecognizer(trainedData, factory)

        val baseFrame = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        baseFrame.eraseColor(Color.rgb(30, 32, 36))
        val idx1 = recognizer.recognizeIndex(baseFrame, forceRefresh = false)
        assertEquals(1, engineCalls)
        assertFalse(recognizer.lastCacheHit)
        assertFalse(recognizer.lastWobbleIgnored)

        // 1. Add +/-3 LSB HDMI/JPEG noise across the frame -> perceptual cache hit!
        val noisyFrame = baseFrame.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 0 until noisyFrame.height step 4) {
            for (x in 0 until noisyFrame.width step 4) {
                noisyFrame.setPixel(x, y, Color.rgb(33, 29, 39))
            }
        }
        val idx2 = recognizer.recognizeIndex(noisyFrame, forceRefresh = false)
        assertSame(idx1, idx2)
        assertEquals(1, engineCalls)
        assertTrue(recognizer.lastCacheHit)
        assertFalse(recognizer.lastWobbleIgnored)

        // 2. Simulate GLKVM mouse wobbler moving a bright cursor in a localized region (500, 360)
        // far away from the text box at (40, 40, 200, 28) -> wobble-ignored cache hit!
        val wobbleFrame = baseFrame.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 360 until 380) {
            for (x in 500 until 520) {
                wobbleFrame.setPixel(x, y, Color.WHITE)
            }
        }
        val idx3 = recognizer.recognizeIndex(wobbleFrame, forceRefresh = false)
        assertSame(idx1, idx3)
        assertEquals(1, engineCalls)
        assertTrue(recognizer.lastCacheHit)
        assertTrue(recognizer.lastWobbleIgnored)

        // 3. When a change overlaps the detected text box at (40, 40, 200, 28), cache invalidates!
        val textChangedFrame = baseFrame.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 42 until 64) {
            for (x in 50 until 90) {
                textChangedFrame.setPixel(x, y, Color.WHITE)
            }
        }
        recognizer.recognizeIndex(textChangedFrame, forceRefresh = false)
        assertEquals(2, engineCalls)
        assertFalse(recognizer.lastCacheHit)
        assertFalse(recognizer.lastWobbleIgnored)
    }

    @Test
    fun `recognizeIndex with region crops input and offsets detected bboxes into full frame`() = runTest {
        val capturedWidths = mutableListOf<Int>()
        val capturedHeights = mutableListOf<Int>()
        val engine = mockk<TextRecognizer.OcrEngine>(relaxed = true) {
            every { detectAndRecognize(any()) } answers {
                val b = firstArg<Bitmap>()
                capturedWidths.add(b.width)
                capturedHeights.add(b.height)
                listOf(OcrLineDetection(OcrBbox(12, 8, 80, 20), "Deploy", 0.94f))
            }
        }
        val factory = TextRecognizer.OcrEngineFactory { _, _ -> engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureModels() } returns modelPaths
        }
        val recognizer = TextRecognizer(trainedData, factory)
        val fullBmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)

        val idx = recognizer.recognizeIndex(
            fullBmp,
            region = OcrBbox(x = 300, y = 200, width = 400, height = 150),
        )
        assertEquals(listOf(400), capturedWidths)
        assertEquals(listOf(150), capturedHeights)
        assertEquals(1280, idx.frameWidth)
        assertEquals(720, idx.frameHeight)
        val line = idx.blocks.single().lines.single()
        assertEquals(OcrBbox(312, 208, 80, 20), line.bbox)
    }

    @Test
    fun `PerceptualLineCache reuses line recognition across HDMI noise and misses on content change`() {
        val cache = PerceptualLineCache()
        val bmp1 = Bitmap.createBitmap(200, 60, Bitmap.Config.ARGB_8888)
        bmp1.eraseColor(Color.rgb(24, 24, 24))
        for (x in 20 until 100) {
            for (y in 15 until 35) {
                bmp1.setPixel(x, y, Color.rgb(220, 220, 220))
            }
        }
        val box = OcrBbox(10, 10, 140, 30)
        val grid1 = PerceptualLineCache.sampleLumaGrid(bmp1, box)
        assertNull(cache.lookup(box, grid1))
        cache.put(box, grid1, "build passed", 0.98f)

        // Slight HDMI noise (+/-3 LSB) and 2px box jitter -> cache hit
        val bmp2 = bmp1.copy(Bitmap.Config.ARGB_8888, true)
        for (x in 0 until bmp2.width) {
            for (y in 0 until bmp2.height) {
                val c = bmp2.getPixel(x, y)
                val r = ((c ushr 16) and 0xFF) + 3
                val g = ((c ushr 8) and 0xFF) - 2
                val b = (c and 0xFF) + 2
                bmp2.setPixel(x, y, Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)))
            }
        }
        val jitterBox = OcrBbox(11, 10, 142, 30)
        val grid2 = PerceptualLineCache.sampleLumaGrid(bmp2, jitterBox)
        val hit = cache.lookup(jitterBox, grid2)
        assertNotNull(hit)
        assertEquals("build passed", hit?.first)

        // Major content change inside the box -> cache miss
        val bmp3 = bmp1.copy(Bitmap.Config.ARGB_8888, true)
        bmp3.eraseColor(Color.rgb(240, 240, 240))
        val grid3 = PerceptualLineCache.sampleLumaGrid(bmp3, box)
        assertNull(cache.lookup(box, grid3))
    }
}
