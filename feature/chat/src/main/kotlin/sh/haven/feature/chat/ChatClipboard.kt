package sh.haven.feature.chat

import android.content.ClipData
import android.net.Uri

/**
 * Clipboard image detection for the composer paste affordance. A clip counts
 * as a pasteable image only when it both declares an image mime and carries a
 * content URI — some sources expose image mimes whose items coerce to text
 * only, and a visible button that can't act would be a lie.
 */
object ChatClipboard {

    fun hasImage(clip: ClipData?): Boolean = firstImageUri(clip) != null

    /** First image content URI on the clip, or null (some clips carry only coerced text). */
    fun firstImageUri(clip: ClipData?): Uri? {
        if (clip == null || clip.itemCount == 0) return null
        val description = clip.description ?: return null
        val hasImageMime = (0 until description.mimeTypeCount)
            .any { description.getMimeType(it)?.startsWith("image/") == true }
        if (!hasImageMime) return null
        return clip.getItemAt(0).uri ?: return null
    }
}