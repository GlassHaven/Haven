package sh.haven.feature.terminal

import android.content.ActivityNotFoundException
import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCaptureGateTest {

    @Test
    fun `take photo without CAMERA permission requests permission before launching TakePicture`() {
        val gate = CameraCaptureGate<TerminalViewModel.ScanMode>()
        val outputUri = mockk<Uri>()
        var requestedPermission: String? = null
        var launchedUri: Uri? = null
        var unavailableCalled = false

        gate.requestCapture(
            mode = null,
            hasCameraPermission = false,
            createOutputUri = { outputUri },
            launchTakePicture = { launchedUri = it },
            requestPermission = { requestedPermission = it },
            onCameraUnavailable = { unavailableCalled = true },
        )

        assertEquals(android.Manifest.permission.CAMERA, requestedPermission)
        assertNull(launchedUri)
        assertTrue(gate.awaitingPermission)
        assertNull(gate.pendingMode)
        assertFalse(unavailableCalled)

        gate.onPermissionResult(
            granted = true,
            createOutputUri = { outputUri },
            launchTakePicture = { launchedUri = it },
            onCameraUnavailable = { unavailableCalled = true },
        )

        assertSame(outputUri, launchedUri)
        assertFalse(gate.awaitingPermission)
        val (capturedUri, capturedMode) = gate.consumeCaptureResult()
        assertSame(outputUri, capturedUri)
        assertNull(capturedMode)
    }

    @Test
    fun `camera scan preserves ScanMode across permission grant`() {
        val gate = CameraCaptureGate<TerminalViewModel.ScanMode>()
        val outputUri = mockk<Uri>()
        var launchedUri: Uri? = null

        gate.requestCapture(
            mode = TerminalViewModel.ScanMode.BARCODE,
            hasCameraPermission = false,
            createOutputUri = { outputUri },
            launchTakePicture = { launchedUri = it },
            requestPermission = {},
            onCameraUnavailable = {},
        )
        assertNull(launchedUri)
        assertEquals(TerminalViewModel.ScanMode.BARCODE, gate.pendingMode)

        gate.onPermissionResult(
            granted = true,
            createOutputUri = { outputUri },
            launchTakePicture = { launchedUri = it },
            onCameraUnavailable = {},
        )

        assertSame(outputUri, launchedUri)
        val (capturedUri, capturedMode) = gate.consumeCaptureResult()
        assertSame(outputUri, capturedUri)
        assertEquals(TerminalViewModel.ScanMode.BARCODE, capturedMode)
    }

    @Test
    fun `denied CAMERA permission clears pending state without launching TakePicture`() {
        val gate = CameraCaptureGate<TerminalViewModel.ScanMode>()
        val outputUri = mockk<Uri>()
        var launchedUri: Uri? = null

        gate.requestCapture(
            mode = TerminalViewModel.ScanMode.OCR,
            hasCameraPermission = false,
            createOutputUri = { outputUri },
            launchTakePicture = { launchedUri = it },
            requestPermission = {},
            onCameraUnavailable = {},
        )

        gate.onPermissionResult(
            granted = false,
            createOutputUri = { outputUri },
            launchTakePicture = { launchedUri = it },
            onCameraUnavailable = {},
        )

        assertNull(launchedUri)
        assertFalse(gate.awaitingPermission)
        assertNull(gate.pendingMode)
        assertNull(gate.cameraOutputUri)
    }

    @Test
    fun `SecurityException and ActivityNotFoundException from TakePicture clear state and notify unavailable`() {
        val gate = CameraCaptureGate<TerminalViewModel.ScanMode>()
        val outputUri = mockk<Uri>()
        var unavailableCount = 0

        gate.requestCapture(
            mode = null,
            hasCameraPermission = true,
            createOutputUri = { outputUri },
            launchTakePicture = { throw SecurityException("with revoked permission android.permission.CAMERA") },
            requestPermission = {},
            onCameraUnavailable = { unavailableCount++ },
        )

        assertEquals(1, unavailableCount)
        assertNull(gate.cameraOutputUri)
        assertNull(gate.pendingMode)

        gate.requestCapture(
            mode = TerminalViewModel.ScanMode.BARCODE,
            hasCameraPermission = true,
            createOutputUri = { outputUri },
            launchTakePicture = { throw ActivityNotFoundException("No camera app") },
            requestPermission = {},
            onCameraUnavailable = { unavailableCount++ },
        )

        assertEquals(2, unavailableCount)
        assertNull(gate.cameraOutputUri)
        assertNull(gate.pendingMode)
    }
}
