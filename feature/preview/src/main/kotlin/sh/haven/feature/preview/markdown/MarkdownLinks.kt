package sh.haven.feature.preview.markdown

import java.net.URLDecoder

/** Where a link in a previewed document points. */
sealed interface LinkTarget {
    /** A heading in the same document. */
    data class Anchor(val anchor: String) : LinkTarget

    /** A file or folder on the same backend as the document, as an absolute path. */
    data class File(val path: String, val anchor: String?) : LinkTarget

    /** Anything with a scheme (`https:`, `mailto:`…), handed to the system. */
    data class External(val uri: String) : LinkTarget
}

/** Where an image in a previewed document comes from. */
sealed interface ImageSource {
    /** A file on the same backend, as an absolute path. */
    data class File(val path: String) : ImageSource

    /** A network image. Never fetched by the preview; the reader opens it if they want it. */
    data class Remote(val url: String) : ImageSource

    /** An inline `data:` image. */
    data class Inline(val bytes: ByteArray) : ImageSource {
        override fun equals(other: Any?): Boolean = other is Inline && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object Unsupported : ImageSource
}

object MarkdownLinks {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val markdownExtensions = setOf("md", "markdown", "mdown", "mkd", "mkdn", "mdwn")

    fun isMarkdownFile(name: String): Boolean =
        name.substringAfterLast('/').substringAfterLast('.', "").lowercase() in markdownExtensions

    /** Resolves [href] found in the document at [documentPath]. */
    fun resolveLink(documentPath: String, href: String): LinkTarget {
        val trimmed = href.trim()
        if (trimmed.startsWith("//")) return LinkTarget.External("https:$trimmed")
        if (schemeRegex.containsMatchIn(trimmed)) return LinkTarget.External(trimmed)
        if (trimmed.startsWith("#")) return LinkTarget.Anchor(decode(trimmed.substring(1)))

        val anchor = trimmed.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(::decode)
        val pathPart = trimmed.substringBefore('#').substringBefore('?')
        if (pathPart.isEmpty()) return LinkTarget.Anchor(anchor.orEmpty())
        return LinkTarget.File(resolvePath(documentPath, decode(pathPart)), anchor)
    }

    /** Resolves an image [src] found in the document at [documentPath]. */
    fun resolveImage(documentPath: String, src: String): ImageSource {
        val trimmed = src.trim()
        return when {
            trimmed.isEmpty() -> ImageSource.Unsupported
            trimmed.startsWith("data:", ignoreCase = true) -> decodeDataUri(trimmed)
            trimmed.startsWith("//") -> ImageSource.Remote("https:$trimmed")
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> ImageSource.Remote(trimmed)
            schemeRegex.containsMatchIn(trimmed) -> ImageSource.Unsupported
            else -> {
                val pathPart = trimmed.substringBefore('#').substringBefore('?')
                if (pathPart.isEmpty()) ImageSource.Unsupported
                else ImageSource.File(resolvePath(documentPath, decode(pathPart)))
            }
        }
    }

    /**
     * Joins [relative] onto the folder holding [documentPath] and folds `.`
     * and `..` segments. A leading `/` keeps [relative] absolute; `..` never
     * climbs above the root. The result is rooted the way [documentPath] is:
     * rclone paths carry no leading slash, and must not gain one.
     */
    fun resolvePath(documentPath: String, relative: String): String {
        val segments = if (relative.startsWith("/")) {
            mutableListOf()
        } else {
            documentPath.split('/').dropLast(1).filter { it.isNotEmpty() }.toMutableList()
        }
        for (segment in relative.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        val root = if (documentPath.startsWith("/")) "/" else ""
        return root + segments.joinToString("/")
    }

    private fun decode(value: String): String =
        try {
            // URLDecoder treats '+' as a space, which is wrong for paths.
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            value
        }

    private fun decodeDataUri(uri: String): ImageSource {
        val comma = uri.indexOf(',')
        if (comma < 0) return ImageSource.Unsupported
        val meta = uri.substring(5, comma)
        if (!meta.startsWith("image/", ignoreCase = true) || !meta.endsWith(";base64", ignoreCase = true)) {
            return ImageSource.Unsupported
        }
        return try {
            ImageSource.Inline(java.util.Base64.getMimeDecoder().decode(uri.substring(comma + 1)))
        } catch (_: IllegalArgumentException) {
            ImageSource.Unsupported
        }
    }
}
