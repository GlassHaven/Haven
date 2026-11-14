package sh.haven.feature.preview.markdown

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.feature.preview.R

/**
 * Loads and decodes the images a document references, downsampled so a
 * phone-camera photo in a README does not cost 48 MB of heap. Decoded
 * images are kept for the life of the preview, so scrolling back up or
 * following a link and returning does not refetch them.
 */
class MarkdownImageLoader(
    /** Reads a file from the document's backend; null when it is missing or too large. */
    private val readFile: suspend (path: String) -> ByteArray?,
) {
    private val cache = LruCache<String, ImageBitmap>(CACHE_ENTRIES)

    suspend fun load(source: ImageSource): ImageBitmap? = when (source) {
        is ImageSource.File -> cache.get(source.path) ?: runCatching { readFile(source.path) }
            .getOrNull()
            ?.let { decode(it) }
            ?.also { cache.put(source.path, it) }
        is ImageSource.Inline -> decode(source.bytes)
        is ImageSource.Remote, ImageSource.Unsupported -> null
    }

    private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIMENSION_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private companion object {
        const val CACHE_ENTRIES = 32
        const val MAX_DIMENSION_PX = 2048
    }
}

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data class Loaded(val bitmap: ImageBitmap) : ImageLoad
    data object Failed : ImageLoad
}

/**
 * One Markdown image. Files on the backend are shown at their natural size
 * (one image pixel per dp, as a browser lays out CSS pixels) up to the
 * column width. Network images are never fetched — the preview does not
 * phone home on the reader's behalf — so they show as a labelled chip that
 * opens the image in the browser.
 */
@Composable
internal fun MarkdownImage(
    src: String,
    alt: String,
    context: MarkdownRenderContext,
    modifier: Modifier = Modifier,
    /** Set when the image sits inside a link: tapping it follows the link. */
    linkHref: String? = null,
) {
    val source = MarkdownLinks.resolveImage(context.documentPath, src)
    if (source is ImageSource.Remote) {
        ImageChip(
            label = alt.ifBlank { source.url.substringAfter("://").substringBefore('/') },
            remote = true,
            onClick = { context.onLink(linkHref ?: source.url) },
            modifier = modifier,
        )
        return
    }
    val load by produceState<ImageLoad>(ImageLoad.Loading, source) {
        value = context.imageLoader.load(source)?.let { ImageLoad.Loaded(it) } ?: ImageLoad.Failed
    }
    when (val state = load) {
        ImageLoad.Loading -> Box(
            modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        )
        ImageLoad.Failed -> ImageChip(
            label = alt.ifBlank { src.substringAfterLast('/') },
            remote = false,
            onClick = linkHref?.let { href -> { context.onLink(href) } },
            modifier = modifier,
        )
        is ImageLoad.Loaded -> {
            val bitmap = state.bitmap
            Image(
                bitmap = bitmap,
                contentDescription = alt.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .widthIn(max = bitmap.width.dp)
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                    .clip(RoundedCornerShape(4.dp))
                    .then(if (linkHref != null) Modifier.clickable { context.onLink(linkHref) } else Modifier),
            )
        }
    }
}

@Composable
private fun ImageChip(
    label: String,
    remote: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        if (remote) R.string.preview_markdown_remote_image else R.string.preview_markdown_image_unavailable,
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = "$description: $label" }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (remote) Icons.Outlined.Image else Icons.Outlined.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
