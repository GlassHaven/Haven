package sh.haven.core.fido

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #732: the key-presentation wait must be bounded, and an agent-forwarded
 * sign request for a provably absent key must fail up front instead of
 * blocking the SSH session until the server's own deadline.
 *
 * Drives the real [getAssertion] entry point with an empty USB device list
 * and no foreground activity, so discovery never completes and the wait /
 * fail-fast paths are what run. [FidoAuthenticator.keyPresentationTimeoutMs]
 * is the test seam that shrinks the 60 s window to milliseconds (the wait
 * lives inside withContext(Dispatchers.IO), outside runTest's virtual clock).
 */
class FidoKeyPresentationTimeoutTest {

    private fun endpoint(dir: Int): UsbEndpoint = mockk {
        every { direction } returns dir
        every { type } returns UsbConstants.USB_ENDPOINT_XFER_INT
    }

    private fun ctaphidIface(): UsbInterface = mockk {
        every { interfaceClass } returns UsbConstants.USB_CLASS_HID
        every { endpointCount } returns 2
        every { getEndpoint(0) } returns endpoint(UsbConstants.USB_DIR_IN)
        every { getEndpoint(1) } returns endpoint(UsbConstants.USB_DIR_OUT)
    }

    private fun device(ifaces: List<UsbInterface>, id: Int): UsbDevice = mockk {
        every { interfaceCount } returns ifaces.size
        ifaces.forEachIndexed { i, f -> every { getInterface(i) } returns f }
        every { deviceId } returns id
    }

    /** Authenticator over a mocked Context whose USB device list is [devices]. */
    private fun authenticator(devices: Map<String, UsbDevice>): FidoAuthenticator {
        val usbManager = mockk<UsbManager> {
            every { deviceList } returns HashMap(devices)
        }
        val context = mockk<Context>(relaxed = true) {
            every { getSystemService(Context.USB_SERVICE) } returns usbManager
        }
        return FidoAuthenticator(context, mockk(relaxed = true))
    }

    private suspend fun FidoAuthenticator.sign(nonInteractive: Boolean) =
        getAssertion(
            rpId = "ssh:example.com",
            message = ByteArray(32),
            credentialId = ByteArray(64),
            requireUv = false,
            keyLabel = "work-yubikey",
            nonInteractive = nonInteractive,
        )

    @Test
    fun `forwarded request with no key connected and no tap possible fails up front`() = runTest {
        val auth = authenticator(emptyMap())
        // A generous window: if the fail-fast gate did NOT fire, this test
        // would block for a real minute — the timeout path is a separate test.
        try {
            auth.sign(nonInteractive = true)
            fail("expected FidoKeyNotPresentedException")
        } catch (e: FidoKeyNotPresentedException) {
            assertTrue(
                "expected the fail-fast message, got: ${e.message}",
                e.message!!.contains("none can be tapped"),
            )
            assertTrue(
                "message should name the key so the user knows which one is missing",
                e.message!!.contains("work-yubikey"),
            )
        }
        // The propagate-catch records the reason for the UI's error display.
        assertTrue(auth.lastAssertionError?.contains("none can be tapped") == true)
    }

    @Test
    fun `interactive wait is bounded and reports the timeout`() = runTest {
        val auth = authenticator(emptyMap())
        auth.keyPresentationTimeoutMs = 50L
        val started = System.nanoTime()
        try {
            auth.sign(nonInteractive = false)
            fail("expected FidoKeyNotPresentedException")
        } catch (e: FidoKeyNotPresentedException) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(
                "expected the timeout message, got: ${e.message}",
                e.message!!.contains("was presented within"),
            )
            // Bounded: fired near the 50 ms seam, not the 60 s production window.
            assertTrue("waited ${elapsedMs}ms — timeout did not fire", elapsedMs in 40..5_000)
        }
    }

    @Test
    fun `forwarded request does NOT fail fast while a tap is possible`() = runTest {
        // A foreground activity means the user could tap an NFC key on the
        // phone's back — the gate must fall through to the bounded wait
        // instead of refusing outright. Distinguished by message: the
        // timeout path says "was presented within", the fail-fast path
        // says "none can be tapped".
        val activity = mockk<Activity>()
        val auth = authenticator(emptyMap())
        auth.setActiveActivity(activity)
        auth.keyPresentationTimeoutMs = 50L
        try {
            auth.sign(nonInteractive = true)
            fail("expected FidoKeyNotPresentedException")
        } catch (e: FidoKeyNotPresentedException) {
            assertTrue(
                "expected the bounded-wait timeout message (gate must not fail fast " +
                    "while NFC is possible), got: ${e.message}",
                e.message!!.contains("was presented within"),
            )
        } finally {
            auth.clearActiveActivity(activity)
        }
    }

    @Test
    fun `forwarded request with a plugged key does not fail fast`() = runTest {
        // The gate only fires when absence is provable; a connected CTAPHID
        // device must take the normal path. Discovery completes immediately,
        // so the ceremony proceeds and fails later at the transport layer
        // (the mocked device can't be opened) — NOT with the fail-fast
        // message. Asserting the gate stayed shut, which is the point.
        val plugged = device(listOf(ctaphidIface()), id = 7)
        val auth = authenticator(mapOf("7" to plugged))
        auth.keyPresentationTimeoutMs = 50L
        try {
            auth.sign(nonInteractive = true)
            fail("expected the ceremony to fail at the transport layer")
        } catch (e: FidoKeyNotPresentedException) {
            fail(
                "plugged key must not hit the absent-key gate, got: ${e.message}",
            )
        } catch (_: Exception) {
            // Any other failure means discovery found the key and the real
            // ceremony ran — the gate correctly stayed closed.
        }
    }
}
