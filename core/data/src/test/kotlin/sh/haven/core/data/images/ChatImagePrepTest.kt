package sh.haven.core.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The chat vision image pipeline: decode a content Uri, downscale to the
 * 1280px cap, JPEG-encode and base64 — plus preview decoding for UI bubbles.
 */
@RunWith(RobolectricTestRunner::class)
class ChatImagePrepTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    private fun writeJpeg(width: Int, height: Int): Uri {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF3366CC.toInt())
        val file = File(context.cacheDir, "prep_test_${width}x${height}_${System.nanoTime()}.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return Uri.fromFile(file)
    }

    @Test
    fun `large image is downscaled to the dimension cap`() = runBlocking {
        val prepared = ChatImagePrep.prepare(context, writeJpeg(3200, 2400))
        assertNotNull(prepared)
        val bytes = android.util.Base64.decode(prepared!!.base64, android.util.Base64.DEFAULT)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals("image/jpeg", prepared.mimeType)
        assertTrue(
            "long edge ${maxOf(decoded.width, decoded.height)} must be <= 1280",
            maxOf(decoded.width, decoded.height) <= 1280,
        )
    }

    @Test
    fun `small image is left unscaled`() = runBlocking {
        val prepared = ChatImagePrep.prepare(context, writeJpeg(400, 300))
        assertNotNull(prepared)
        val bytes = android.util.Base64.decode(prepared!!.base64, android.util.Base64.DEFAULT)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(400, decoded.width)
        assertEquals(300, decoded.height)
    }

    // NOTE: the null paths (undecodable URI → null from prepare(), garbage
    // base64 → null from decodePreview()) are not testable under Robolectric's
    // legacy graphics: its BitmapFactory shadow fabricates a placeholder
    // bitmap for any byte stream instead of failing. On-device behaviour is
    // expected to return null (checked in Stage 5 verification).

    @Test
    fun `preview round-trips through decodePreview`() {
        val prepared = runBlocking { ChatImagePrep.prepare(context, writeJpeg(2000, 1000)) }!!
        val preview = ChatImagePrep.decodePreview(prepared.base64)
        assertNotNull(preview)
        assertTrue(maxOf(preview!!.width, preview.height) <= 256)
    }

    // ---- prepare(bytes): the chat attach from-Files pipeline ----
    // NATIVE graphics: the legacy shadow fabricates placeholder bitmaps for
    // every byte stream, which makes the bytes pipeline's real contract
    // (real decode, mime detection, garbage rejection) unobservable.

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `bytes overload round-trips like the uri overload`() = runBlocking {
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF3366CC.toInt())
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        val prepared = ChatImagePrep.prepare(bytes)
        assertNotNull(prepared)
        assertEquals("image/jpeg", prepared!!.mimeType)
        val raw = android.util.Base64.decode(prepared.base64, android.util.Base64.DEFAULT)
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size)
        assertEquals(400, decoded.width)
        assertEquals(300, decoded.height)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `bytes overload downscales large images to the cap`() = runBlocking {
        val bmp = Bitmap.createBitmap(3200, 2400, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF3366CC.toInt())
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        val prepared = ChatImagePrep.prepare(bytes)
        assertNotNull(prepared)
        val raw = android.util.Base64.decode(prepared!!.base64, android.util.Base64.DEFAULT)
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size)
        assertTrue(
            "long edge ${maxOf(decoded.width, decoded.height)} must be <= 1280",
            maxOf(decoded.width, decoded.height) <= 1280,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `bytes overload keeps png alpha when scaling re-encodes`() = runBlocking {
        // Alpha only survives when the pipeline re-encodes to PNG — i.e. the
        // image is large enough to be scaled (an unscaled passthrough stays
        // JPEG by design, matching the uri overload).
        val bmp = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0x803366CC.toInt())
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val prepared = ChatImagePrep.prepare(bytes)
        assertNotNull(prepared)
        assertEquals("image/png", prepared!!.mimeType)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `bytes overload rejects non-image payload`() = runBlocking {
        // Testable only under NATIVE graphics — the legacy shadow decodes
        // any byte stream into a placeholder bitmap (see the note above).
        assertEquals(null, ChatImagePrep.prepare("not an image at all".toByteArray()))
    }

    // ---- decodeToCache (clipboard copy-out) ----

    @Test
    fun `decodeToCache writes a decodable jpeg cache file`() = runBlocking {
        val prepared = ChatImagePrep.prepare(context, writeJpeg(400, 300))!!
        // FileProvider needs a manifest-declared authority the test app lacks,
        // so inject a plain file URI — the bytes-on-disk contract is the same.
        val uri = ChatImagePrep.decodeToCache(
            context, prepared.base64, prepared.mimeType,
        ) { Uri.fromFile(it) }
        assertNotNull(uri)
        val file = File(uri!!.path!!)
        assertTrue(file.path.contains("/chat/") && file.name == "chat_copy.jpg")
        val bytes = file.readBytes()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(400, decoded.width)
        assertEquals(300, decoded.height)
    }

    @Test
    fun `decodeToCache png mime maps to a png cache file`() {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFFCC3366.toInt())
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val uri = runBlocking {
            ChatImagePrep.decodeToCache(context, base64, "image/png") { Uri.fromFile(it) }
        }!!
        assertTrue("png mime must map to a .png file, got $uri", uri.path!!.endsWith(".png"))
        assertTrue(uri.path!!.contains("/chat/"))
    }

    @Test
    fun `decodeToCache rejects invalid base64`() = runBlocking {
        val uri = ChatImagePrep.decodeToCache(context, "not valid base64!!", "image/jpeg")
        assertEquals(null, uri)
    }

    @Test
    fun `decodeToCache overwrites instead of accumulating`() = runBlocking {
        val prepared = ChatImagePrep.prepare(context, writeJpeg(400, 300))!!
        ChatImagePrep.decodeToCache(context, prepared.base64, prepared.mimeType) { Uri.fromFile(it) }
        ChatImagePrep.decodeToCache(context, prepared.base64, prepared.mimeType) { Uri.fromFile(it) }
        val chatDir = File(context.cacheDir, "chat")
        val copies = chatDir.listFiles { f -> f.name.startsWith("chat_copy.") } ?: emptyArray()
        assertEquals(1, copies.size)
    }
}