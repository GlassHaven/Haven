package sh.haven.core.data.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Prepare user-picked images for the chat vision path: decode with bounds
 * sampling, scale so the long edge is at most [MAX_DIMENSION], JPEG-encode at
 * [JPEG_QUALITY] and base64-encode. This lives in :core:data (not
 * :core:openai, whose unit tests run without Robolectric) because it needs
 * android.graphics; callers map [PreparedImage] onto the client's ChatImage.
 *
 * The same pipeline shape as SensesToolProvider's withContextDownscale in :app.
 */
object ChatImagePrep {

    const val MAX_DIMENSION = 1280
    const val JPEG_QUALITY = 75

    data class PreparedImage(val mimeType: String, val base64: String)

    /**
     * Decode [uri], downscale to ≤ [maxDim] on the long edge, and return the
     * base64 JPEG — or null when the URI can't be decoded.
     */
    suspend fun prepare(
        context: Context,
        uri: Uri,
        maxDim: Int = MAX_DIMENSION,
        quality: Int = JPEG_QUALITY,
    ): PreparedImage? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsOpen = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        boundsOpen.exceptionOrNull()?.let {
            android.util.Log.w("ChatImagePrep", "bounds decode failed for $uri", it)
        }
        // NOTE: the result itself is useless — decodeStream with
        // inJustDecodeBounds always returns null. Only the dimensions it
        // reported decide whether this is a decodable image.
        if (boundsOpen.isFailure) return@withContext null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            android.util.Log.w("ChatImagePrep", "bounds report no image: ${bounds.outWidth}x${bounds.outHeight} for $uri mime=${bounds.outMimeType}")
            return@withContext null
        }

        // Power-of-2 subsample to roughly the target first (cheap on memory),
        // then a final exact scale down below.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val fullOpen = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        }
        fullOpen.exceptionOrNull()?.let {
            android.util.Log.w("ChatImagePrep", "full decode failed for $uri", it)
        }
        val decoded = fullOpen.getOrNull() ?: return@withContext null
        return@withContext encodePrepared(decoded, bounds.outMimeType, maxDim, quality)
    }

    /**
     * Same pipeline over an already-read byte payload — the chat attach
     * from-Files flow reads a remote file into memory and then prepares it
     * exactly like a picked/captured one.
     */
    suspend fun prepare(
        bytes: ByteArray,
        maxDim: Int = MAX_DIMENSION,
        quality: Int = JPEG_QUALITY,
    ): PreparedImage? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext null
        encodePrepared(decoded, bounds.outMimeType, maxDim, quality)
    }

    /** Scale + encode tail shared by both [prepare] overloads. */
    private fun encodePrepared(
        decoded: Bitmap,
        boundsMimeType: String?,
        maxDim: Int,
        quality: Int,
    ): PreparedImage {
        val scaled = scaleDown(decoded, maxDim)
        val png = decoded !== scaled && boundsMimeType?.contains("png") == true && hasAlpha(scaled)
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(format, if (png) 100 else quality, out)
            out.toByteArray()
        }
        return PreparedImage(
            mimeType = if (png) "image/png" else "image/jpeg",
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    /** Small preview for UI thumbnails; null when [base64] isn't a decodable image. */
    fun decodePreview(base64: String, maxDim: Int = 256): Bitmap? {
        val raw = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return scaleDown(decoded, maxDim)
    }

    /**
     * Write a carried chat image back out as a shareable cache file so it can
     * go on the system clipboard as a URI clip. Bytes are written verbatim (no
     * re-encode); the fixed per-format filename keeps the cache bounded since
     * the clipboard only ever holds one image. Null when [base64] isn't valid
     * or the write fails.
     *
     * [toShareUri] defaults to the app's FileProvider (declared in :app's
     * manifest); it is injectable because Robolectric has no provider manifest.
     */
    suspend fun decodeToCache(
        context: Context,
        base64: String,
        mimeType: String,
        toShareUri: (File) -> Uri = {
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", it,
            )
        },
    ): Uri? = withContext(Dispatchers.IO) {
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            ?: return@withContext null
        val ext = if (mimeType.contains("png", ignoreCase = true)) "png" else "jpg"
        val dir = File(context.cacheDir, "chat").apply { mkdirs() }
        val file = File(dir, "chat_copy.$ext")
        runCatching { file.writeBytes(bytes) }.getOrNull() ?: return@withContext null
        runCatching { toShareUri(file) }.getOrNull()
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun hasAlpha(bitmap: Bitmap): Boolean = bitmap.hasAlpha()
}