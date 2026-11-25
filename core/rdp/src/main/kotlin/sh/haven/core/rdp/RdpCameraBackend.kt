package sh.haven.core.rdp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import sh.haven.rdp.CameraCallback
import sh.haven.rdp.RdpCameraDevice
import sh.haven.rdp.RdpCameraFormat
import sh.haven.rdp.RdpCameraMode
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Camera2 backend for `MS-RDPECAM` (`RDCamera_Device_Enumerator` +
 * per-device dynamic virtual channels, #890).
 *
 * Raw frames are never logged (`#518` PII scrub policy), and `YUV_420_888`
 * frames are converted/encoded only when the RDP server has an outstanding
 * `SampleRequest` on that camera.
 */
class RdpCameraBackend internal constructor(
    private val source: CameraSourceAdapter,
    private val hasPendingRequest: (cameraId: String) -> Boolean,
    private val pushSample: (cameraId: String, sample: ByteArray) -> Boolean,
    private val log: (level: String, msg: String) -> Unit = { _, _ -> },
) : CameraCallback, Closeable {

    internal interface CameraSourceAdapter {
        fun hasCameraPermission(): Boolean
        fun listCameras(): List<RdpCameraDevice>
        fun openStream(
            cameraId: String,
            mode: RdpCameraMode,
            shouldEncodeFrame: () -> Boolean,
            onFrame: (ByteArray) -> Unit,
        ): Closeable?
    }

    private val activeStreams = ConcurrentHashMap<String, Closeable>()
    private val closed = AtomicBoolean(false)

    fun hasCameraPermission(): Boolean = source.hasCameraPermission()

    override fun listCameras(): List<RdpCameraDevice> {
        if (closed.get()) return emptyList()
        return runCatching { source.listCameras() }.getOrDefault(emptyList())
    }

    override fun startStream(cameraId: String, mode: RdpCameraMode): Boolean {
        if (closed.get()) return false
        if (!source.hasCameraPermission()) {
            log("D", "RDP camera stream waiting for CAMERA permission")
            return false
        }
        stopStream(cameraId)
        val handle = runCatching {
            source.openStream(
                cameraId = cameraId,
                mode = mode,
                shouldEncodeFrame = { !closed.get() && hasPendingRequest(cameraId) },
                onFrame = { bytes ->
                    if (!closed.get()) {
                        pushSample(cameraId, bytes)
                    }
                },
            )
        }.onFailure { err ->
            log("E", "RDP camera open failed: ${err.message}")
        }.getOrNull() ?: return false

        if (closed.get()) {
            runCatching { handle.close() }
            return false
        }
        activeStreams.put(cameraId, handle)?.let { prev ->
            runCatching { prev.close() }
        }
        log("D", "RDP camera stream active (${mode.format} ${mode.width}x${mode.height}@${mode.fps})")
        return true
    }

    override fun stopStream(cameraId: String) {
        activeStreams.remove(cameraId)?.let { handle ->
            runCatching { handle.close() }
            log("D", "RDP camera stream stopped")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val handles = activeStreams.values.toList()
        activeStreams.clear()
        for (h in handles) {
            runCatching { h.close() }
        }
    }

    companion object {
        private val PREFERRED_SIZES: List<Pair<Int, Int>> = listOf(
            1280 to 720,
            640 to 480,
            320 to 240,
        )

        fun create(
            context: Context,
            hasPendingRequest: (cameraId: String) -> Boolean,
            pushSample: (cameraId: String, sample: ByteArray) -> Boolean,
            log: (level: String, msg: String) -> Unit = { _, _ -> },
        ): RdpCameraBackend {
            return RdpCameraBackend(
                source = AndroidCamera2SourceAdapter(context.applicationContext, log),
                hasPendingRequest = hasPendingRequest,
                pushSample = pushSample,
                log = log,
            )
        }

        /**
         * Pack 3-plane `YUV_420_888` buffers (arbitrary `rowStride` and
         * `pixelStride` 1 or 2) into tightly-packed `NV12` (`uvOrderNv21 = false`,
         * `Y` + `U,V`) or `NV21` (`uvOrderNv21 = true`, `Y` + `V,U`).
         */
        internal fun packYuv420ToSemiPlanar(
            width: Int,
            height: Int,
            yBuffer: ByteBuffer,
            yRowStride: Int,
            yPixelStride: Int,
            uBuffer: ByteBuffer,
            uRowStride: Int,
            uPixelStride: Int,
            vBuffer: ByteBuffer,
            vRowStride: Int,
            vPixelStride: Int,
            uvOrderNv21: Boolean,
        ): ByteArray {
            require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
                "YUV 4:2:0 dimensions must be positive even numbers"
            }
            val ySize = width * height
            val out = ByteArray(ySize + ySize / 2)

            val yDup = yBuffer.duplicate()
            var dst = 0
            if (yPixelStride == 1 && yRowStride == width) {
                yDup.position(0)
                yDup.get(out, 0, ySize)
                dst = ySize
            } else {
                val rowBuf = if (yPixelStride == 1) ByteArray(width) else null
                for (row in 0 until height) {
                    val rowStart = row * yRowStride
                    if (rowBuf != null) {
                        yDup.position(rowStart)
                        yDup.get(rowBuf, 0, width)
                        System.arraycopy(rowBuf, 0, out, dst, width)
                        dst += width
                    } else {
                        for (col in 0 until width) {
                            out[dst++] = yDup.get(rowStart + col * yPixelStride)
                        }
                    }
                }
            }

            val uDup = uBuffer.duplicate()
            val vDup = vBuffer.duplicate()
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            for (row in 0 until chromaHeight) {
                val uRowStart = row * uRowStride
                val vRowStart = row * vRowStride
                for (col in 0 until chromaWidth) {
                    val uByte = uDup.get(uRowStart + col * uPixelStride)
                    val vByte = vDup.get(vRowStart + col * vPixelStride)
                    if (uvOrderNv21) {
                        out[dst++] = vByte
                        out[dst++] = uByte
                    } else {
                        out[dst++] = uByte
                        out[dst++] = vByte
                    }
                }
            }
            return out
        }

        internal fun buildModesForSupportedSizes(sizes: Set<Pair<Int, Int>>): List<RdpCameraMode> {
            val chosen = PREFERRED_SIZES.filter { it in sizes }.ifEmpty {
                sizes.filter { (w, h) ->
                    w in 160..1920 && h in 120..1080 && w % 2 == 0 && h % 2 == 0
                }.sortedByDescending { (w, h) -> w * h }.take(2)
            }
            if (chosen.isEmpty()) return emptyList()
            val modes = ArrayList<RdpCameraMode>(chosen.size * 4)
            // Advertise MJPEG first (bandwidth-friendly at 720p/480p), then NV12 fallback.
            for ((w, h) in chosen) {
                modes.add(RdpCameraMode(RdpCameraFormat.MJPG, w.toUInt(), h.toUInt(), 30u))
                modes.add(RdpCameraMode(RdpCameraFormat.MJPG, w.toUInt(), h.toUInt(), 15u))
            }
            for ((w, h) in chosen) {
                if (w <= 640 && h <= 480) {
                    modes.add(RdpCameraMode(RdpCameraFormat.NV12, w.toUInt(), h.toUInt(), 30u))
                    modes.add(RdpCameraMode(RdpCameraFormat.NV12, w.toUInt(), h.toUInt(), 15u))
                }
            }
            return modes
        }
    }
}

private class AndroidCamera2SourceAdapter(
    private val appContext: Context,
    private val log: (level: String, msg: String) -> Unit,
) : RdpCameraBackend.CameraSourceAdapter {

    private val cameraManager: CameraManager? =
        appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    override fun hasCameraPermission(): Boolean {
        return appContext.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun listCameras(): List<RdpCameraDevice> {
        val cm = cameraManager ?: return emptyList()
        val ids = runCatching { cm.cameraIdList }.getOrNull() ?: return emptyList()

        data class Candidate(
            val id: String,
            val facing: Int,
            val modes: List<RdpCameraMode>,
        )

        val candidates = ArrayList<Candidate>()
        var seenFront = false
        var seenBack = false

        for (id in ids) {
            val chars = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (caps == null || !caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
                continue
            }
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            // Expose primary front, primary back, and any external USB cameras so
            // multi-lens phones do not flood the remote session with duplicate lenses.
            when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> {
                    if (seenFront) continue
                    seenFront = true
                }
                CameraCharacteristics.LENS_FACING_BACK -> {
                    if (seenBack) continue
                    seenBack = true
                }
            }
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
                ?.map { it.width to it.height }
                ?.toSet()
                .orEmpty()
            val modes = RdpCameraBackend.buildModesForSupportedSizes(yuvSizes)
            if (modes.isEmpty()) continue
            candidates.add(Candidate(id = id, facing = facing, modes = modes))
        }

        // Put Front camera first (natural default for remote video calls), then Back, then External.
        val ordered = candidates.sortedBy { cand ->
            when (cand.facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> 0
                CameraCharacteristics.LENS_FACING_BACK -> 1
                else -> 2
            }
        }
        var extCount = 0
        return ordered.map { cand ->
            val name = when (cand.facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Haven Front Camera"
                CameraCharacteristics.LENS_FACING_BACK -> "Haven Back Camera"
                else -> {
                    extCount += 1
                    if (extCount == 1) "Haven External Camera" else "Haven External Camera $extCount"
                }
            }
            RdpCameraDevice(id = cand.id, name = name, modes = cand.modes)
        }
    }

    @SuppressLint("MissingPermission")
    override fun openStream(
        cameraId: String,
        mode: RdpCameraMode,
        shouldEncodeFrame: () -> Boolean,
        onFrame: (ByteArray) -> Unit,
    ): Closeable? {
        if (!hasCameraPermission()) return null
        val cm = cameraManager ?: return null
        val width = mode.width.toInt()
        val height = mode.height.toInt()
        val targetFps = mode.fps.toInt().coerceIn(1, 60)

        val thread = HandlerThread("rdp-cam-$cameraId").apply { start() }
        val handler = Handler(thread.looper)
        val imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)

        val closed = AtomicBoolean(false)
        var openedDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        val readyLatch = CountDownLatch(1)
        val startedOk = AtomicBoolean(false)

        imageReader.setOnImageAvailableListener({ reader ->
            val image: Image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (closed.get() || !shouldEncodeFrame()) return@setOnImageAvailableListener
                val planes = image.planes
                if (planes.size < 3) return@setOnImageAvailableListener
                val sample = when (mode.format) {
                    RdpCameraFormat.NV12 -> RdpCameraBackend.packYuv420ToSemiPlanar(
                        width = image.width,
                        height = image.height,
                        yBuffer = planes[0].buffer,
                        yRowStride = planes[0].rowStride,
                        yPixelStride = planes[0].pixelStride,
                        uBuffer = planes[1].buffer,
                        uRowStride = planes[1].rowStride,
                        uPixelStride = planes[1].pixelStride,
                        vBuffer = planes[2].buffer,
                        vRowStride = planes[2].rowStride,
                        vPixelStride = planes[2].pixelStride,
                        uvOrderNv21 = false,
                    )
                    RdpCameraFormat.MJPG -> {
                        val nv21 = RdpCameraBackend.packYuv420ToSemiPlanar(
                            width = image.width,
                            height = image.height,
                            yBuffer = planes[0].buffer,
                            yRowStride = planes[0].rowStride,
                            yPixelStride = planes[0].pixelStride,
                            uBuffer = planes[1].buffer,
                            uRowStride = planes[1].rowStride,
                            uPixelStride = planes[1].pixelStride,
                            vBuffer = planes[2].buffer,
                            vRowStride = planes[2].rowStride,
                            vPixelStride = planes[2].pixelStride,
                            uvOrderNv21 = true,
                        )
                        val out = ByteArrayOutputStream(width * height / 4)
                        val ok = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                            .compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
                        if (!ok) return@setOnImageAvailableListener
                        out.toByteArray()
                    }
                }
                onFrame(sample)
            } catch (e: Exception) {
                log("E", "RDP camera frame encode error: ${e.message}")
            } finally {
                runCatching { image.close() }
            }
        }, handler)

        val closeHandle = Closeable {
            if (!closed.compareAndSet(false, true)) return@Closeable
            val teardownLatch = CountDownLatch(1)
            val posted = handler.post {
                runCatching { captureSession?.close() }
                captureSession = null
                runCatching { openedDevice?.close() }
                openedDevice = null
                runCatching { imageReader.close() }
                teardownLatch.countDown()
            }
            if (posted) {
                teardownLatch.await(1_000L, TimeUnit.MILLISECONDS)
            } else {
                runCatching { imageReader.close() }
            }
            runCatching { thread.quitSafely() }
        }

        try {
            cm.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (closed.get()) {
                            runCatching { camera.close() }
                            readyLatch.countDown()
                            return
                        }
                        openedDevice = camera
                        try {
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(
                                listOf(imageReader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        if (closed.get()) {
                                            runCatching { session.close() }
                                            readyLatch.countDown()
                                            return
                                        }
                                        captureSession = session
                                        try {
                                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                addTarget(imageReader.surface)
                                                set(
                                                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                                    Range(targetFps.coerceAtMost(15), targetFps),
                                                )
                                            }.build()
                                            session.setRepeatingRequest(req, null, handler)
                                            startedOk.set(true)
                                        } catch (e: Exception) {
                                            log("E", "RDP camera repeating request failed: ${e.message}")
                                        } finally {
                                            readyLatch.countDown()
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        log("E", "RDP camera session configure failed")
                                        readyLatch.countDown()
                                    }
                                },
                                handler,
                            )
                        } catch (e: Exception) {
                            log("E", "RDP camera session setup failed: ${e.message}")
                            readyLatch.countDown()
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        openedDevice = null
                        readyLatch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        log("E", "RDP camera device error ($error)")
                        runCatching { camera.close() }
                        openedDevice = null
                        readyLatch.countDown()
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            log("E", "RDP camera openCamera threw: ${e.message}")
            closeHandle.close()
            return null
        }

        val completed = readyLatch.await(3_500L, TimeUnit.MILLISECONDS)
        if (!completed || !startedOk.get()) {
            closeHandle.close()
            return null
        }
        return closeHandle
    }
}
