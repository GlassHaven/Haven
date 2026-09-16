package sh.haven.feature.chat

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer paste-image affordance's contract: a clip counts only when it
 * both declares an image mime AND carries a content URI — visible button
 * must equal actionable tap.
 */
class ChatClipboardTest {

    private fun clip(mimes: List<String?>, uri: Uri?): ClipData {
        val description = mockk<ClipDescription>()
        every { description.mimeTypeCount } returns mimes.size
        mimes.forEachIndexed { i, m -> every { description.getMimeType(i) } returns m }
        val item = mockk<ClipData.Item>()
        every { item.uri } returns uri
        val clip = mockk<ClipData>()
        every { clip.description } returns description
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns item
        return clip
    }

    @Test
    fun `image clip with a uri is detected`() {
        val uri = mockk<Uri>()
        val clip = clip(listOf("image/jpeg"), uri)
        assertTrue(ChatClipboard.hasImage(clip))
        assertEquals(uri, ChatClipboard.firstImageUri(clip))
    }

    @Test
    fun `text-only clip is not an image`() {
        val clip = clip(listOf("text/plain"), null)
        assertFalse(ChatClipboard.hasImage(clip))
        assertNull(ChatClipboard.firstImageUri(clip))
    }

    @Test
    fun `null clip is safe`() {
        assertFalse(ChatClipboard.hasImage(null))
        assertNull(ChatClipboard.firstImageUri(null))
    }

    @Test
    fun `image mime without a uri hides the button`() {
        val clip = clip(listOf("image/png"), null)
        assertFalse(ChatClipboard.hasImage(clip))
        assertNull(ChatClipboard.firstImageUri(clip))
    }

    @Test
    fun `multiple mimes with one image are detected`() {
        val uri = mockk<Uri>()
        val clip = clip(listOf("text/plain", "image/webp"), uri)
        assertTrue(ChatClipboard.hasImage(clip))
        assertEquals(uri, ChatClipboard.firstImageUri(clip))
    }
}