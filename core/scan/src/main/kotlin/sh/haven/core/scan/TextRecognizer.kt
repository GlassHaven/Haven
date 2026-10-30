package sh.haven.core.scan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs RapidOCR (PP-OCRv4 DBNet detector + SVTR/CRNN recognizer via ONNX Runtime)
 * over a [Bitmap], producing either plain text ([recognize]) or a full spatial
 * [OcrScreenIndex] backed by a 2D k-d tree ([recognizeIndex]).
 *
 * The ONNX sessions are initialized lazily on the first OCR call and reused across
 * subsequent calls behind a [Mutex]. [recognizeIndex] also caches the most recent
 * [OcrScreenIndex] by frame SHA-256 so repeated MCP coordinate/text lookups on an
 * unchanged remote desktop frame complete in under a millisecond.
 */
@Singleton
class TextRecognizer @Inject constructor(
    private val trainedData: TrainedDataManager,
    private val engineFactory: OcrEngineFactory,
) {
    private val mutex = Mutex()
    @Volatile private var engine: OcrEngine? = null

    @Volatile private var cachedSig: TileGridSignature? = null
    @Volatile private var cachedMinScore: Float = -1f
    @Volatile private var cachedIndex: OcrScreenIndex? = null

    /** True when the most recent [recognizeIndex] call returned a cached index without running ONNX. */
    @Volatile var lastCacheHit: Boolean = false
        private set

    /** True when the most recent [recognizeIndex] call ignored localized cursor-wobbler motion outside text boxes. */
    @Volatile var lastWobbleIgnored: Boolean = false
        private set

    /**
     * Recognize text in [bitmap] and return lines joined by `\n`, or `null`
     * when no text was detected. Used by the terminal attach camera/photo OCR flow.
     */
    suspend fun recognize(bitmap: Bitmap): String? = withContext(Dispatchers.Default) {
        val index = recognizeIndex(bitmap, forceRefresh = true)
        val text = index.blocks.asSequence()
            .flatMap { it.lines.asSequence() }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
        text.takeIf { it.isNotEmpty() }
    }

    /**
     * Run RapidOCR over [bitmap] (or an optional [region] within [bitmap]) and
     * build a spatial [OcrScreenIndex] with a 2D k-d tree over all detected
     * words and lines in full-frame pixel coordinates.
     *
     * Uses a perceptual 16×16 tile-grid signature to:
     * 1. Ignore `±1..6` LSB HDMI capture / JPEG re-encoding noise on unchanged frames.
     * 2. Ignore localized (`≤ 2×2` tile) hardware mouse-wobbler motion when the
     *    wobbling cursor does not overlap any previously detected text line.
     */
    suspend fun recognizeIndex(
        bitmap: Bitmap,
        minScore: Float = 0.45f,
        forceRefresh: Boolean = false,
    ): OcrScreenIndex = recognizeIndex(bitmap, minScore, forceRefresh, region = null)

    suspend fun recognizeIndex(
        bitmap: Bitmap,
        minScore: Float = 0.45f,
        forceRefresh: Boolean = false,
        region: OcrBbox?,
    ): OcrScreenIndex = withContext(Dispatchers.Default) {
        val clampedRegion = region?.let { clampRegion(it, bitmap.width, bitmap.height) }
        val sig = TileGridSignature.compute(bitmap, clampedRegion)
        if (!forceRefresh) {
            val existing = cachedIndex
            val prevSig = cachedSig
            if (existing != null && prevSig != null && cachedMinScore == minScore) {
                val diff = sig.diffAgainst(prevSig, existing)
                if (diff.identical || diff.wobbleOnly) {
                    lastCacheHit = true
                    lastWobbleIgnored = diff.wobbleOnly
                    return@withContext existing
                }
            }
        }

        val paths = trainedData.ensureModels()
        val (detections, elapsedMs) = mutex.withLock {
            if (!forceRefresh) {
                val existing = cachedIndex
                val prevSig = cachedSig
                if (existing != null && prevSig != null && cachedMinScore == minScore) {
                    val diff = sig.diffAgainst(prevSig, existing)
                    if (diff.identical || diff.wobbleOnly) {
                        lastCacheHit = true
                        lastWobbleIgnored = diff.wobbleOnly
                        return@withContext existing
                    }
                }
            }
            val eng = engine ?: try {
                engineFactory.create(paths.detModel, paths.recModel).also { engine = it }
            } catch (t: Throwable) {
                throw TextRecognizeException("RapidOCR init failed: ${t.message}", t)
            }
            val t0 = System.nanoTime()
            val lines = try {
                if (clampedRegion != null &&
                    (clampedRegion.x != 0 || clampedRegion.y != 0 ||
                        clampedRegion.width != bitmap.width || clampedRegion.height != bitmap.height)
                ) {
                    val sub = Bitmap.createBitmap(
                        bitmap,
                        clampedRegion.x,
                        clampedRegion.y,
                        clampedRegion.width,
                        clampedRegion.height,
                    )
                    try {
                        eng.detectAndRecognize(sub).map { det ->
                            det.copy(
                                bbox = OcrBbox(
                                    x = det.bbox.x + clampedRegion.x,
                                    y = det.bbox.y + clampedRegion.y,
                                    width = det.bbox.width,
                                    height = det.bbox.height,
                                ),
                            )
                        }
                    } finally {
                        if (sub !== bitmap) sub.recycle()
                    }
                } else {
                    eng.detectAndRecognize(bitmap)
                }
            } catch (t: Throwable) {
                throw TextRecognizeException("RapidOCR recognise failed: ${t.message}", t)
            }
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            lines to ms
        }

        val built = OcrScreenIndex.build(
            detections = detections,
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            frameHash = sig.hash,
            ocrMs = elapsedMs,
            minScore = minScore,
        )
        lastCacheHit = false
        lastWobbleIgnored = false
        cachedSig = sig
        cachedMinScore = minScore
        cachedIndex = built
        built
    }

    /** Release the native ONNX sessions. */
    suspend fun shutdown() {
        mutex.withLock {
            engine?.let { runCatching { it.close() } }
            engine = null
            cachedSig = null
            cachedIndex = null
            lastCacheHit = false
            lastWobbleIgnored = false
        }
    }

    interface OcrEngine : AutoCloseable {
        fun detectAndRecognize(bitmap: Bitmap): List<OcrLineDetection>
    }

    fun interface OcrEngineFactory {
        fun create(detModel: File, recModel: File): OcrEngine
    }

    companion object {
        internal fun clampRegion(region: OcrBbox, width: Int, height: Int): OcrBbox? {
            if (width <= 0 || height <= 0) return null
            val rx = region.x.coerceIn(0, max(0, width - 1))
            val ry = region.y.coerceIn(0, max(0, height - 1))
            val rw = region.width.coerceIn(1, width - rx)
            val rh = region.height.coerceIn(1, height - ry)
            return if (rw >= 4 && rh >= 4) OcrBbox(rx, ry, rw, rh) else null
        }

        internal fun hashBitmap(bitmap: Bitmap): String =
            TileGridSignature.compute(bitmap, null).hash
    }
}

/**
 * Perceptual 16×16 tile-grid signature of a frame (or region), with 2×2
 * sub-quadrant RGB means per tile. Tolerates HDMI capture / JPEG quantization
 * noise (`±6` LSB) and identifies localized `≤ 2×2` tile mouse-wobbler
 * changes that do not intersect any recognized text line.
 */
internal class TileGridSignature(
    val width: Int,
    val height: Int,
    val region: OcrBbox?,
    val subR: IntArray,
    val subG: IntArray,
    val subB: IntArray,
    val hash: String,
) {
    data class FrameDiff(
        val identical: Boolean,
        val wobbleOnly: Boolean,
        val changedTileCount: Int,
    )

    fun diffAgainst(other: TileGridSignature, cachedIndex: OcrScreenIndex?): FrameDiff {
        if (width != other.width || height != other.height || region != other.region) {
            return FrameDiff(identical = false, wobbleOnly = false, changedTileCount = GRID_W * GRID_H)
        }
        var changedCount = 0
        var minTx = GRID_W
        var maxTx = -1
        var minTy = GRID_H
        var maxTy = -1

        for (ty in 0 until GRID_H) {
            for (tx in 0 until GRID_W) {
                val tileIdx = (ty * GRID_W + tx) * 4
                var tileChanged = false
                for (q in 0 until 4) {
                    val idx = tileIdx + q
                    val dr = kotlin.math.abs(subR[idx] - other.subR[idx])
                    val dg = kotlin.math.abs(subG[idx] - other.subG[idx])
                    val db = kotlin.math.abs(subB[idx] - other.subB[idx])
                    if (dr > NOISE_LSB_TOLERANCE || dg > NOISE_LSB_TOLERANCE || db > NOISE_LSB_TOLERANCE) {
                        tileChanged = true
                        break
                    }
                }
                if (tileChanged) {
                    changedCount++
                    if (tx < minTx) minTx = tx
                    if (tx > maxTx) maxTx = tx
                    if (ty < minTy) minTy = ty
                    if (ty > maxTy) maxTy = ty
                }
            }
        }

        if (changedCount == 0) {
            return FrameDiff(identical = true, wobbleOnly = false, changedTileCount = 0)
        }

        val effW = region?.width ?: width
        val effH = region?.height ?: height
        val effX = region?.x ?: 0
        val effY = region?.y ?: 0

        if (changedCount in 1..4 &&
            effW >= MIN_WOBBLE_FRAME_WIDTH &&
            effH >= MIN_WOBBLE_FRAME_HEIGHT &&
            cachedIndex != null &&
            cachedIndex.lineCount > 0 &&
            (maxTx - minTx) <= 1 &&
            (maxTy - minTy) <= 1
        ) {
            val dirtyX0 = effX + (minTx * effW) / GRID_W - WOBBLE_MARGIN_PX
            val dirtyY0 = effY + (minTy * effH) / GRID_H - WOBBLE_MARGIN_PX
            val dirtyX1 = effX + ((maxTx + 1) * effW) / GRID_W + WOBBLE_MARGIN_PX
            val dirtyY1 = effY + ((maxTy + 1) * effH) / GRID_H + WOBBLE_MARGIN_PX

            val overlapsText = cachedIndex.blocks.any { blk ->
                blk.lines.any { line ->
                    val b = line.bbox
                    b.right >= dirtyX0 && b.x <= dirtyX1 && b.bottom >= dirtyY0 && b.y <= dirtyY1
                }
            }
            if (!overlapsText) {
                return FrameDiff(identical = false, wobbleOnly = true, changedTileCount = changedCount)
            }
        }

        return FrameDiff(identical = false, wobbleOnly = false, changedTileCount = changedCount)
    }

    companion object {
        const val GRID_W = 16
        const val GRID_H = 16
        const val NOISE_LSB_TOLERANCE = 6
        const val MIN_WOBBLE_FRAME_WIDTH = 320
        const val MIN_WOBBLE_FRAME_HEIGHT = 240
        const val WOBBLE_MARGIN_PX = 6

        fun compute(bitmap: Bitmap, region: OcrBbox?): TileGridSignature {
            val w = bitmap.width
            val h = bitmap.height
            val numSub = GRID_W * GRID_H * 4
            if (w <= 0 || h <= 0) {
                return TileGridSignature(w, h, region, IntArray(numSub), IntArray(numSub), IntArray(numSub), "empty")
            }
            val rx = region?.x ?: 0
            val ry = region?.y ?: 0
            val rw = region?.width ?: w
            val rh = region?.height ?: h

            val subR = IntArray(numSub)
            val subG = IntArray(numSub)
            val subB = IntArray(numSub)

            // Sample a 2x2 grid of points inside each of the 2x2 sub-quadrants per tile
            // (64x64 samples across the frame/region).
            val samplesPerSub = 2
            val totalSampleY = GRID_H * 2 * samplesPerSub
            val totalSampleX = GRID_W * 2 * samplesPerSub
            val rowPixels = IntArray(rw)

            val sumR = IntArray(numSub)
            val sumG = IntArray(numSub)
            val sumB = IntArray(numSub)
            val counts = IntArray(numSub)

            for (sy in 0 until totalSampleY) {
                val py = ry + ((sy * 2 + 1) * rh / (totalSampleY * 2)).coerceIn(0, rh - 1)
                bitmap.getPixels(rowPixels, 0, rw, rx, py, rw, 1)
                val ty = (sy / (2 * samplesPerSub)).coerceIn(0, GRID_H - 1)
                val qy = (sy / samplesPerSub) and 1
                for (sx in 0 until totalSampleX) {
                    val px = ((sx * 2 + 1) * rw / (totalSampleX * 2)).coerceIn(0, rw - 1)
                    val tx = (sx / (2 * samplesPerSub)).coerceIn(0, GRID_W - 1)
                    val qx = (sx / samplesPerSub) and 1
                    val subIdx = (ty * GRID_W + tx) * 4 + (qy * 2 + qx)
                    val c = rowPixels[px]
                    sumR[subIdx] += (c ushr 16) and 0xFF
                    sumG[subIdx] += (c ushr 8) and 0xFF
                    sumB[subIdx] += c and 0xFF
                    counts[subIdx]++
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(w).putInt(h).putInt(rx).putInt(ry).putInt(rw).putInt(rh)
            digest.update(header.array(), 0, 24)

            val quantBuf = ByteArray(numSub * 3)
            for (i in 0 until numSub) {
                val cnt = max(1, counts[i])
                val r = sumR[i] / cnt
                val g = sumG[i] / cnt
                val b = sumB[i] / cnt
                subR[i] = r
                subG[i] = g
                subB[i] = b
                // Quantize by >> 4 (16-LSB buckets) for the compact hex hash string.
                quantBuf[i * 3] = (r ushr 4).toByte()
                quantBuf[i * 3 + 1] = (g ushr 4).toByte()
                quantBuf[i * 3 + 2] = (b ushr 4).toByte()
            }
            digest.update(quantBuf)
            val hex = digest.digest().take(6).joinToString("") { "%02x".format(it) }
            return TileGridSignature(w, h, region, subR, subG, subB, hex)
        }
    }
}

/**
 * Per-line crop cache that skips SVTR/CRNN recognition on text boxes whose
 * position, dimensions, and `16×6` sampled luma grid are perceptually unchanged
 * (`±5` mean LSB) across consecutive frames.
 */
internal class PerceptualLineCache(
    private val maxEntries: Int = 256,
) {
    data class Entry(
        val bbox: OcrBbox,
        val lumaGrid: ByteArray,
        val text: String,
        val score: Float,
    )

    private val entries = ArrayList<Entry>(maxEntries)

    @Synchronized
    fun lookup(bbox: OcrBbox, lumaGrid: ByteArray): Pair<String, Float>? {
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            if (kotlin.math.abs(e.bbox.x - bbox.x) <= POS_TOLERANCE_PX &&
                kotlin.math.abs(e.bbox.y - bbox.y) <= POS_TOLERANCE_PX &&
                kotlin.math.abs(e.bbox.width - bbox.width) <= SIZE_TOLERANCE_PX &&
                kotlin.math.abs(e.bbox.height - bbox.height) <= SIZE_TOLERANCE_PX &&
                isLumaPerceptuallyEqual(e.lumaGrid, lumaGrid)
            ) {
                return e.text to e.score
            }
        }
        return null
    }

    @Synchronized
    fun put(bbox: OcrBbox, lumaGrid: ByteArray, text: String, score: Float) {
        entries.removeAll { e ->
            kotlin.math.abs(e.bbox.x - bbox.x) <= POS_TOLERANCE_PX &&
                kotlin.math.abs(e.bbox.y - bbox.y) <= POS_TOLERANCE_PX &&
                kotlin.math.abs(e.bbox.width - bbox.width) <= SIZE_TOLERANCE_PX &&
                kotlin.math.abs(e.bbox.height - bbox.height) <= SIZE_TOLERANCE_PX
        }
        if (entries.size >= maxEntries) {
            entries.removeAt(0)
        }
        entries.add(Entry(bbox, lumaGrid, text, score))
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val SAMPLE_W = 16
        const val SAMPLE_H = 6
        const val SAMPLE_COUNT = SAMPLE_W * SAMPLE_H
        const val POS_TOLERANCE_PX = 4
        const val SIZE_TOLERANCE_PX = 6
        const val MAX_MEAN_LUMA_DIFF = 5
        const val MAX_POINT_LUMA_DIFF = 12
        const val MAX_OUTLIER_POINTS = 3

        fun sampleLumaGrid(bitmap: Bitmap, box: OcrBbox): ByteArray {
            val grid = ByteArray(SAMPLE_COUNT)
            val bw = box.width.coerceAtLeast(1)
            val bh = box.height.coerceAtLeast(1)
            val row = IntArray(bw)
            var idx = 0
            for (gy in 0 until SAMPLE_H) {
                val py = (box.y + ((gy * 2 + 1) * bh) / (SAMPLE_H * 2)).coerceIn(0, bitmap.height - 1)
                val safeW = min(bw, bitmap.width - box.x.coerceIn(0, bitmap.width - 1))
                if (safeW <= 0) break
                bitmap.getPixels(row, 0, safeW, box.x.coerceIn(0, bitmap.width - 1), py, safeW, 1)
                for (gx in 0 until SAMPLE_W) {
                    val lx = (((gx * 2 + 1) * safeW) / (SAMPLE_W * 2)).coerceIn(0, safeW - 1)
                    val px = row[lx]
                    val r = (px ushr 16) and 0xFF
                    val g = (px ushr 8) and 0xFF
                    val b = px and 0xFF
                    val luma = (r * 77 + g * 150 + b * 29) ushr 8
                    grid[idx++] = luma.toByte()
                }
            }
            return grid
        }

        fun isLumaPerceptuallyEqual(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diffSum = 0
            var outliers = 0
            for (i in a.indices) {
                val da = a[i].toInt() and 0xFF
                val db = b[i].toInt() and 0xFF
                val d = kotlin.math.abs(da - db)
                diffSum += d
                if (d > MAX_POINT_LUMA_DIFF) {
                    outliers++
                    if (outliers > MAX_OUTLIER_POINTS) return false
                }
            }
            return diffSum <= MAX_MEAN_LUMA_DIFF * a.size
        }
    }
}

/**
 * Production [TextRecognizer.OcrEngineFactory] backed by ONNX Runtime and the
 * PP-OCRv4 detection (`ch_PP-OCRv4_det_infer.onnx`) and recognition
 * (`ch_PP-OCRv4_rec_infer.onnx`) models.
 */
@Singleton
class DefaultOcrEngineFactory @Inject constructor() : TextRecognizer.OcrEngineFactory {
    override fun create(detModel: File, recModel: File): TextRecognizer.OcrEngine {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val detSession = env.createSession(detModel.absolutePath, opts)
        val recSession = env.createSession(recModel.absolutePath, opts)
        val charMeta = recSession.metadata.customMetadata["character"]
            ?: error("PP-OCRv4 rec model at ${recModel.absolutePath} is missing 'character' metadata")
        val vocab = buildList {
            add("") // index 0 = CTC blank token
            addAll(charMeta.lines())
            add(" ")
        }
        Log.i("TextRecognizer", "RapidOCR ONNX sessions initialised (vocab=${vocab.size})")
        return OnnxRapidOcrEngine(env, opts, detSession, recSession, vocab)
    }
}

internal class OnnxRapidOcrEngine(
    private val env: OrtEnvironment,
    private val sessionOptions: OrtSession.SessionOptions,
    private val detSession: OrtSession,
    private val recSession: OrtSession,
    private val vocab: List<String>,
) : TextRecognizer.OcrEngine {

    private val detInputName: String = detSession.inputNames.first()
    private val recInputName: String = recSession.inputNames.first()
    private val lineCache = PerceptualLineCache()

    override fun detectAndRecognize(bitmap: Bitmap): List<OcrLineDetection> {
        val origW = bitmap.width
        val origH = bitmap.height
        if (origW < 4 || origH < 4) return emptyList()

        val srcBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        // 1. Resize for DBNet detection (multiple of 32, max side <= 1280).
        val maxSide = max(origW, origH)
        val ratio = if (maxSide > DET_MAX_SIDE) DET_MAX_SIDE.toDouble() / maxSide else 1.0
        val detW = max(32, ((origW * ratio) / 32.0).roundToInt() * 32)
        val detH = max(32, ((origH * ratio) / 32.0).roundToInt() * 32)

        val detBitmap = if (detW == origW && detH == origH) {
            srcBitmap
        } else {
            Bitmap.createScaledBitmap(srcBitmap, detW, detH, true)
        }

        val planeSize = detW * detH
        val pixels = IntArray(planeSize)
        detBitmap.getPixels(pixels, 0, detW, 0, 0, detW, detH)
        if (detBitmap !== srcBitmap && detBitmap !== bitmap) {
            detBitmap.recycle()
        }

        val detInputBuf = ByteBuffer.allocateDirect(3 * planeSize * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (i in 0 until planeSize) {
            val px = pixels[i]
            val b = ((px and 0xFF) / 255f - MEAN_B) / STD_B
            val g = (((px ushr 8) and 0xFF) / 255f - MEAN_G) / STD_G
            val r = (((px ushr 16) and 0xFF) / 255f - MEAN_R) / STD_R
            detInputBuf.put(i, b)
            detInputBuf.put(planeSize + i, g)
            detInputBuf.put(2 * planeSize + i, r)
        }
        detInputBuf.rewind()

        val probMap = FloatArray(planeSize)
        OnnxTensor.createTensor(env, detInputBuf, longArrayOf(1L, 3L, detH.toLong(), detW.toLong())).use { tensor ->
            detSession.run(mapOf(detInputName to tensor)).use { result ->
                val outTensor = result[0] as OnnxTensor
                outTensor.floatBuffer.get(probMap)
            }
        }

        // 2. Threshold + 2x2 dilation + 8-connected components + DBNet unclip.
        val rawBoxes = extractBoxes(probMap, detW, detH, origW, origH)
        if (rawBoxes.isEmpty()) {
            if (srcBitmap !== bitmap) srcBitmap.recycle()
            return emptyList()
        }

        // 3. Recognize each detected line box with SVTR/CRNN, reusing cached
        // line recognitions for boxes whose perceptual luma grid is unchanged.
        val detections = ArrayList<OcrLineDetection>(rawBoxes.size)
        for (box in rawBoxes) {
            if (box.width < 4 || box.height < 4) continue
            val lumaGrid = PerceptualLineCache.sampleLumaGrid(srcBitmap, box)
            val cached = lineCache.lookup(box, lumaGrid)
            if (cached != null) {
                detections.add(OcrLineDetection(box, cached.first, cached.second))
                continue
            }

            val crop = Bitmap.createBitmap(srcBitmap, box.x, box.y, box.width, box.height)
            val cropW = max(16, (REC_HEIGHT.toDouble() * box.width / box.height).roundToInt())
            val scaledCrop = if (crop.width == cropW && crop.height == REC_HEIGHT) {
                crop
            } else {
                Bitmap.createScaledBitmap(crop, cropW, REC_HEIGHT, true)
            }
            if (scaledCrop !== crop) crop.recycle()

            val cropPlane = cropW * REC_HEIGHT
            val cropPixels = IntArray(cropPlane)
            scaledCrop.getPixels(cropPixels, 0, cropW, 0, 0, cropW, REC_HEIGHT)
            scaledCrop.recycle()

            val recBuf = ByteBuffer.allocateDirect(3 * cropPlane * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            for (i in 0 until cropPlane) {
                val px = cropPixels[i]
                val b = ((px and 0xFF) / 255f - 0.5f) / 0.5f
                val g = (((px ushr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                val r = (((px ushr 16) and 0xFF) / 255f - 0.5f) / 0.5f
                recBuf.put(i, b)
                recBuf.put(cropPlane + i, g)
                recBuf.put(2 * cropPlane + i, r)
            }
            recBuf.rewind()

            OnnxTensor.createTensor(env, recBuf, longArrayOf(1L, 3L, REC_HEIGHT.toLong(), cropW.toLong())).use { recTensor ->
                recSession.run(mapOf(recInputName to recTensor)).use { recResult ->
                    val outTensor = recResult[0] as OnnxTensor
                    val shape = outTensor.info.shape // [1, T, V]
                    val timeSteps = shape[1].toInt()
                    val vocabSize = shape[2].toInt()
                    val fb = outTensor.floatBuffer
                    val sb = StringBuilder()
                    var scoreSum = 0f
                    var charCount = 0
                    var prevIdx = 0
                    for (t in 0 until timeSteps) {
                        val baseOffset = t * vocabSize
                        var bestIdx = 0
                        var bestProb = -1f
                        for (v in 0 until vocabSize) {
                            val p = fb.get(baseOffset + v)
                            if (p > bestProb) {
                                bestProb = p
                                bestIdx = v
                            }
                        }
                        if (bestIdx != 0 && bestIdx != prevIdx && bestIdx < vocab.size) {
                            sb.append(vocab[bestIdx])
                            scoreSum += bestProb
                            charCount++
                        }
                        prevIdx = bestIdx
                    }
                    if (charCount > 0) {
                        val meanScore = scoreSum / charCount
                        val text = sb.toString()
                        if (text.isNotBlank()) {
                            lineCache.put(box, lumaGrid, text, meanScore)
                            detections.add(OcrLineDetection(box, text, meanScore))
                        }
                    }
                }
            }
        }

        if (srcBitmap !== bitmap) {
            srcBitmap.recycle()
        }
        return detections
    }

    private fun extractBoxes(
        probMap: FloatArray,
        detW: Int,
        detH: Int,
        origW: Int,
        origH: Int,
    ): List<OcrBbox> {
        val planeSize = detW * detH
        val seg = BooleanArray(planeSize)
        for (i in 0 until planeSize) {
            seg[i] = probMap[i] > DET_THRESH
        }
        // 2x2 dilation (matches RapidOCR's use_dilation = True).
        val mask = seg.copyOf()
        for (y in 1 until detH) {
            val row = y * detW
            val prevRow = (y - 1) * detW
            for (x in 1 until detW) {
                val idx = row + x
                if (seg[prevRow + x] || seg[row + x - 1] || seg[prevRow + x - 1]) {
                    mask[idx] = true
                }
            }
        }

        val visited = BooleanArray(planeSize)
        val queue = IntArray(planeSize)
        val boxes = ArrayList<OcrBbox>()

        for (sy in 0 until detH) {
            val rowOffset = sy * detW
            for (sx in 0 until detW) {
                val startIdx = rowOffset + sx
                if (!mask[startIdx] || visited[startIdx]) continue

                var head = 0
                var tail = 0
                queue[tail++] = startIdx
                visited[startIdx] = true

                var minX = sx
                var maxX = sx
                var minY = sy
                var maxY = sy

                while (head < tail) {
                    val curr = queue[head++]
                    val cy = curr / detW
                    val cx = curr - cy * detW
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    val y0 = max(0, cy - 1)
                    val y1 = min(detH - 1, cy + 1)
                    val x0 = max(0, cx - 1)
                    val x1 = min(detW - 1, cx + 1)
                    for (ny in y0..y1) {
                        val nRow = ny * detW
                        for (nx in x0..x1) {
                            val nIdx = nRow + nx
                            if (mask[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                val bw = maxX - minX
                val bh = maxY - minY
                if (min(bw, bh) < 3) continue

                // box_score_fast: mean probability inside [minY..maxY, minX..maxX].
                var probSum = 0.0
                var count = 0
                for (yy in minY..maxY) {
                    val rOff = yy * detW
                    for (xx in minX..maxX) {
                        probSum += probMap[rOff + xx]
                        count++
                    }
                }
                if (count == 0 || (probSum / count) < BOX_THRESH) continue

                val d = (bw.toDouble() * bh.toDouble()) * UNCLIP_RATIO / (2.0 * (bw + bh))
                val rx0 = ((minX - d) * origW / detW).roundToInt().coerceIn(0, origW - 1)
                val ry0 = ((minY - d) * origH / detH).roundToInt().coerceIn(0, origH - 1)
                val rx1 = ((maxX + d) * origW / detW).roundToInt().coerceIn(0, origW)
                val ry1 = ((maxY + d) * origH / detH).roundToInt().coerceIn(0, origH)
                if (rx1 - rx0 >= 4 && ry1 - ry0 >= 4) {
                    boxes.add(OcrBbox(rx0, ry0, rx1 - rx0, ry1 - ry0))
                }
            }
        }
        return boxes
    }

    override fun close() {
        lineCache.clear()
        runCatching { detSession.close() }
        runCatching { recSession.close() }
        runCatching { sessionOptions.close() }
    }

    private companion object {
        const val DET_MAX_SIDE = 1280
        const val DET_THRESH = 0.3f
        const val BOX_THRESH = 0.5
        const val UNCLIP_RATIO = 1.6
        const val REC_HEIGHT = 48

        const val MEAN_B = 0.485f
        const val MEAN_G = 0.456f
        const val MEAN_R = 0.406f
        const val STD_B = 0.229f
        const val STD_G = 0.224f
        const val STD_R = 0.225f
    }
}

class TextRecognizeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
