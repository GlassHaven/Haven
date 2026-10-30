package sh.haven.core.scan

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Axis-aligned bounding box in full-resolution screen pixels `[x, y, width, height]`.
 */
data class OcrBbox(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val cx: Double get() = x + width / 2.0
    val cy: Double get() = y + height / 2.0

    fun contains(px: Double, py: Double): Boolean =
        px >= x && px <= right && py >= y && py <= bottom

    /**
     * Euclidean distance from ([px], [py]) to the nearest point on or inside
     * this rectangle (`0.0` when ([px], [py]) is inside the box).
     */
    fun rectDistanceTo(px: Double, py: Double): Double {
        val dx = when {
            px < x -> x - px
            px > right -> px - right
            else -> 0.0
        }
        val dy = when {
            py < y -> y - py
            py > bottom -> py - bottom
            else -> 0.0
        }
        return hypot(dx, dy)
    }

    fun centerDistanceTo(px: Double, py: Double): Double = hypot(px - cx, py - cy)
}

/** Raw line-level detection emitted by [RapidOcrEngine]. */
data class OcrLineDetection(
    val bbox: OcrBbox,
    val text: String,
    val score: Float,
)

/**
 * A single clickable OCR detection node (either a full [Level.LINE] or a
 * proportional-width [Level.WORD] inside a line) indexed in [OcrScreenIndex].
 */
data class OcrNode(
    val id: String,
    val level: Level,
    val text: String,
    val score: Float,
    val bbox: OcrBbox,
    val blockId: String,
    val lineId: String,
) {
    enum class Level { LINE, WORD }
}

/**
 * A spatial neighbor returned by a [KdTree2D] lookup around a query point.
 *
 * [dx] and [dy] are the signed offset from the query point to the neighbor's
 * center (`dx > 0` = right of query, `dy > 0` = below query).
 */
data class OcrNeighbor(
    val node: OcrNode,
    val centerDistance: Double,
    val rectDistance: Double,
    val dx: Double,
    val dy: Double,
)

data class OcrLine(
    val id: String,
    val text: String,
    val score: Float,
    val bbox: OcrBbox,
    val words: List<OcrNode>,
)

data class OcrBlock(
    val id: String,
    val lines: List<OcrLine>,
)

/**
 * Spatial + text index over a desktop frame's RapidOCR detections, backed by
 * a 2D k-d tree ([KdTree2D]) over all word and line centers.
 *
 * Supports:
 * - **Coordinate → Element** ([queryAt]): what element is at `(x, y)` (bbox
 *   containment) plus the `k` nearest elements in the 2D k-d tree.
 * - **Text → Coordinates** ([findText]): find words/lines matching `query`,
 *   optionally disambiguated by Euclidean distance to an anchor `(nearX, nearY)`,
 *   along with each match's `k` nearest spatial neighbors from the k-d tree.
 */
class OcrScreenIndex private constructor(
    val frameWidth: Int,
    val frameHeight: Int,
    val frameHash: String,
    val ocrMs: Double,
    val blocks: List<OcrBlock>,
    val nodes: List<OcrNode>,
) {
    val kdTree: KdTree2D<OcrNode> = KdTree2D(nodes) { it.bbox.cx to it.bbox.cy }

    val lineCount: Int = blocks.sumOf { it.lines.size }
    val wordCount: Int = nodes.count { it.level == OcrNode.Level.WORD }

    data class CoordinateQueryResult(
        val x: Int,
        val y: Int,
        val containing: List<OcrNode>,
        val nearest: List<OcrNeighbor>,
    )

    data class TextMatchResult(
        val node: OcrNode,
        val anchorDistance: Double?,
        val neighbors: List<OcrNeighbor>,
    )

    /**
     * Query what OCR detections are at or near ([x], [y]) using the 2D k-d tree.
     *
     * @param x X coordinate in full-resolution framebuffer pixels.
     * @param y Y coordinate in full-resolution framebuffer pixels.
     * @param k Maximum number of nearest neighbors to return from the k-d tree.
     * @param maxRadius Optional maximum center distance in pixels.
     * @param level Optional filter to restrict nearest results to [OcrNode.Level.WORD] or [OcrNode.Level.LINE].
     */
    fun queryAt(
        x: Double,
        y: Double,
        k: Int = 5,
        maxRadius: Double = Double.POSITIVE_INFINITY,
        level: OcrNode.Level? = null,
    ): CoordinateQueryResult {
        val containing = nodes
            .filter { (level == null || it.level == level) && it.bbox.contains(x, y) }
            .sortedWith(
                compareBy<OcrNode> { it.level != OcrNode.Level.WORD }
                    .thenBy { it.bbox.centerDistanceTo(x, y) },
            )

        // Fetch a slightly wider candidate pool from the center-based k-d tree
        // so we can rank by rectDistance (edge proximity) as well as centerDistance.
        val poolSize = max(k * 3, 12)
        val hits = kdTree.nearest(
            qx = x,
            qy = y,
            k = poolSize,
            maxDistance = maxRadius,
            filter = { level == null || it.level == level },
        )
        val nearest = hits
            .map { hit ->
                val n = hit.item
                OcrNeighbor(
                    node = n,
                    centerDistance = hit.distance,
                    rectDistance = n.bbox.rectDistanceTo(x, y),
                    dx = n.bbox.cx - x,
                    dy = n.bbox.cy - y,
                )
            }
            .sortedWith(
                compareBy<OcrNeighbor> { it.rectDistance }
                    .thenBy { it.node.level != OcrNode.Level.WORD }
                    .thenBy { it.centerDistance },
            )
            .take(k.coerceAtLeast(0))

        return CoordinateQueryResult(
            x = x.roundToInt(),
            y = y.roundToInt(),
            containing = containing,
            nearest = nearest,
        )
    }

    /**
     * Find detections whose text matches [query] (case-insensitive substring, or
     * exact normalized equality when [exact] is true).
     *
     * When [nearX] and [nearY] are provided, matches are ranked by Euclidean
     * distance to `(nearX, nearY)` so multiple identical labels on screen
     * (e.g. `"Close"`, `"Refresh"`, `"X"`) resolve to the one closest to the
     * anchor coordinate. Each match also carries its [kNeighbors] nearest
     * spatial neighbors from the 2D k-d tree.
     */
    fun findText(
        query: String,
        exact: Boolean = false,
        nearX: Double? = null,
        nearY: Double? = null,
        kNeighbors: Int = 3,
        level: OcrNode.Level? = null,
    ): List<TextMatchResult> {
        val normQuery = normalizeText(query)
        val compactQuery = compactText(normQuery)
        if (normQuery.isEmpty()) return emptyList()

        val rawMatches = nodes.filter { node ->
            if (level != null && node.level != level) return@filter false
            val normNode = normalizeText(node.text)
            val compactNode = compactText(normNode)
            if (exact) {
                normNode == normQuery || (compactQuery.isNotEmpty() && compactNode == compactQuery)
            } else {
                normNode.contains(normQuery) || (compactQuery.isNotEmpty() && compactNode.contains(compactQuery))
            }
        }

        val sorted = if (nearX != null && nearY != null) {
            rawMatches.sortedWith(
                compareBy<OcrNode> {
                    // Bucket distance to 4px so a word and its enclosing line at
                    // almost the same point still prefer the tighter word box.
                    (it.bbox.rectDistanceTo(nearX, nearY) / 4.0).roundToInt()
                }
                    .thenBy { it.level != OcrNode.Level.WORD }
                    .thenBy { it.bbox.centerDistanceTo(nearX, nearY) },
            )
        } else {
            rawMatches.sortedWith(
                compareBy<OcrNode> {
                    val normNode = normalizeText(it.text)
                    val directMatch = if (exact) normNode == normQuery else normNode.contains(normQuery)
                    !directMatch
                }
                    .thenBy { it.level != OcrNode.Level.WORD }
                    .thenBy { (it.bbox.cy / 8.0).roundToInt() }
                    .thenBy { it.bbox.x },
            )
        }

        return sorted.map { match ->
            val anchorDist = if (nearX != null && nearY != null) {
                match.bbox.centerDistanceTo(nearX, nearY)
            } else {
                null
            }
            val neighbors = if (kNeighbors > 0) {
                kdTree.nearest(
                    qx = match.bbox.cx,
                    qy = match.bbox.cy,
                    k = kNeighbors,
                    filter = { candidate ->
                        candidate.id != match.id &&
                            // Exclude the parent line of a matched word (or child words of a matched line)
                            // so neighbors represent distinct surrounding UI elements.
                            !(candidate.lineId == match.lineId && candidate.level != match.level)
                    },
                ).map { hit ->
                    OcrNeighbor(
                        node = hit.item,
                        centerDistance = hit.distance,
                        rectDistance = hit.item.bbox.rectDistanceTo(match.bbox.cx, match.bbox.cy),
                        dx = hit.item.bbox.cx - match.bbox.cx,
                        dy = hit.item.bbox.cy - match.bbox.cy,
                    )
                }
            } else {
                emptyList()
            }
            TextMatchResult(
                node = match,
                anchorDistance = anchorDist,
                neighbors = neighbors,
            )
        }
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")

        fun normalizeText(s: String): String =
            s.trim().lowercase().replace(WHITESPACE_REGEX, " ")

        internal fun compactText(s: String): String =
            s.replace(" ", "")

        internal fun charRelativeWidth(ch: Char): Double = when {
            ch.code in 0x2E80..0x9FFF || ch.code in 0xFF00..0xFFEF ||
                ch == '—' || ch == '–' || ch == '…' || ch == '·' -> 1.0
            ch == ' ' -> 0.0
            ch.isUpperCase() -> 0.72
            ch.isDigit() -> 0.62
            ch.isLetter() -> 0.55
            else -> 0.34
        }

        internal fun splitWordSpans(text: String): List<IntRange> {
            val spans = mutableListOf<IntRange>()
            var start = 0
            var inWord = false
            for (i in text.indices) {
                if (text[i].isWhitespace()) {
                    if (inWord) {
                        spans.add(start until i)
                        inWord = false
                    }
                } else if (!inWord) {
                    start = i
                    inWord = true
                }
            }
            if (inWord) {
                spans.add(start until text.length)
            }
            return spans
        }

        internal fun estimateSubBbox(
            lineBbox: OcrBbox,
            lineText: String,
            span: IntRange,
        ): OcrBbox {
            val cum = DoubleArray(lineText.length + 1)
            for (i in lineText.indices) {
                cum[i + 1] = cum[i] + charRelativeWidth(lineText[i])
            }
            val total = if (cum[lineText.length] > 0.0) cum[lineText.length] else 1.0
            val leftFrac = cum[span.first] / total
            val rightFrac = cum[span.last + 1] / total
            var bx0 = lineBbox.x + leftFrac * lineBbox.width
            var bx1 = lineBbox.x + rightFrac * lineBbox.width
            if (bx1 - bx0 < 2.0) {
                bx0 -= 1.0
                bx1 += 1.0
            }
            val rx = bx0.roundToInt()
            val rw = max(2, (bx1 - bx0).roundToInt())
            return OcrBbox(rx, lineBbox.y, rw, lineBbox.height)
        }

        /**
         * Build an [OcrScreenIndex] (blocks → lines → words + 2D k-d tree) from
         * raw line detections.
         */
        fun build(
            detections: List<OcrLineDetection>,
            frameWidth: Int,
            frameHeight: Int,
            frameHash: String = "",
            ocrMs: Double = 0.0,
            minScore: Float = 0.45f,
        ): OcrScreenIndex {
            val filtered = detections
                .filter { it.score >= minScore && it.text.isNotBlank() }
                .sortedWith(
                    compareBy<OcrLineDetection> { (it.bbox.cy / 8.0).roundToInt() }
                        .thenBy { it.bbox.x },
                )

            val rawBlocks = mutableListOf<MutableList<OcrLineDetection>>()
            for (line in filtered) {
                if (rawBlocks.isNotEmpty()) {
                    val lastBlock = rawBlocks.last()
                    val prev = lastBlock.last()
                    val lh = max(max(prev.bbox.height, line.bbox.height), 10)
                    val overlapY = min(prev.bbox.bottom, line.bbox.bottom) - max(prev.bbox.y, line.bbox.y)
                    if (overlapY > -0.45 * lh) {
                        lastBlock.add(line)
                        continue
                    }
                }
                rawBlocks.add(mutableListOf(line))
            }

            val allNodes = mutableListOf<OcrNode>()
            val outBlocks = rawBlocks.mapIndexed { bi, blkLines ->
                blkLines.sortWith(
                    compareBy<OcrLineDetection> { (it.bbox.cy / 8.0).roundToInt() }
                        .thenBy { it.bbox.x },
                )
                val blockId = "b$bi"
                val lines = blkLines.mapIndexed { li, det ->
                    val lineId = "${blockId}l$li"
                    val lineNode = OcrNode(
                        id = lineId,
                        level = OcrNode.Level.LINE,
                        text = det.text,
                        score = det.score,
                        bbox = det.bbox,
                        blockId = blockId,
                        lineId = lineId,
                    )
                    allNodes.add(lineNode)

                    val spans = splitWordSpans(det.text)
                    val wordNodes = spans.mapIndexed { wi, span ->
                        val wordText = det.text.substring(span)
                        val wordBbox = if (spans.size == 1) det.bbox else estimateSubBbox(det.bbox, det.text, span)
                        OcrNode(
                            id = "${lineId}w$wi",
                            level = OcrNode.Level.WORD,
                            text = wordText,
                            score = det.score,
                            bbox = wordBbox,
                            blockId = blockId,
                            lineId = lineId,
                        )
                    }
                    allNodes.addAll(wordNodes)

                    OcrLine(
                        id = lineId,
                        text = det.text,
                        score = det.score,
                        bbox = det.bbox,
                        words = wordNodes,
                    )
                }
                OcrBlock(id = blockId, lines = lines)
            }

            return OcrScreenIndex(
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                frameHash = frameHash,
                ocrMs = ocrMs,
                blocks = outBlocks,
                nodes = allNodes,
            )
        }
    }
}
