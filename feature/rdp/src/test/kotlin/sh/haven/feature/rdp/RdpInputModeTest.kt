package sh.haven.feature.rdp

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpInputModeTest {

    @Test
    fun `effectiveRdpInputMode preserves TOUCHSCREEN when touchscreenCapable is true`() {
        assertEquals("DIRECT", effectiveRdpInputMode("DIRECT", touchscreenCapable = true))
        assertEquals("TOUCHPAD", effectiveRdpInputMode("TOUCHPAD", touchscreenCapable = true))
        assertEquals("TOUCHSCREEN", effectiveRdpInputMode("TOUCHSCREEN", touchscreenCapable = true))
    }

    @Test
    fun `effectiveRdpInputMode falls back from TOUCHSCREEN to DIRECT when not capable`() {
        assertEquals("DIRECT", effectiveRdpInputMode("DIRECT", touchscreenCapable = false))
        assertEquals("TOUCHPAD", effectiveRdpInputMode("TOUCHPAD", touchscreenCapable = false))
        assertEquals("DIRECT", effectiveRdpInputMode("TOUCHSCREEN", touchscreenCapable = false))
    }

    @Test
    fun `nextRdpInputMode cycles 3-way when touchscreenCapable is true`() {
        val step1 = nextRdpInputMode("DIRECT", touchscreenCapable = true)
        assertEquals("TOUCHPAD", step1)
        val step2 = nextRdpInputMode(step1, touchscreenCapable = true)
        assertEquals("TOUCHSCREEN", step2)
        val step3 = nextRdpInputMode(step2, touchscreenCapable = true)
        assertEquals("DIRECT", step3)
    }

    @Test
    fun `nextRdpInputMode cycles 2-way when touchscreenCapable is false`() {
        val step1 = nextRdpInputMode("DIRECT", touchscreenCapable = false)
        assertEquals("TOUCHPAD", step1)
        val step2 = nextRdpInputMode(step1, touchscreenCapable = false)
        assertEquals("DIRECT", step2)
    }

    @Test
    fun `shouldForwardTouchscreenContacts pauses remote touch passthrough when viewport navigation is active`() {
        assertTrue(
            shouldForwardTouchscreenContacts(
                effectiveInputMode = "TOUCHSCREEN",
                hasTouchCallback = true,
                touchscreenViewportNav = false,
            ),
        )
        assertFalse(
            shouldForwardTouchscreenContacts(
                effectiveInputMode = "TOUCHSCREEN",
                hasTouchCallback = true,
                touchscreenViewportNav = true,
            ),
        )
        assertFalse(
            shouldForwardTouchscreenContacts(
                effectiveInputMode = "DIRECT",
                hasTouchCallback = true,
                touchscreenViewportNav = false,
            ),
        )
        assertFalse(
            shouldForwardTouchscreenContacts(
                effectiveInputMode = "TOUCHSCREEN",
                hasTouchCallback = false,
                touchscreenViewportNav = false,
            ),
        )
    }

    @Test
    fun `applyViewportPinch and applyViewportPan zoom and pan local viewport within cover bounds`() {
        val pinched = applyViewportPinch(
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            prevSpan = 100f,
            span = 200f,
            centroid = Offset(960f, 540f),
            viewWidth = 1920f,
            viewHeight = 1080f,
            fbWidth = 1920,
            fbHeight = 1080,
        )
        assertEquals(2f, pinched.zoom, 0.001f)
        assertEquals(0f, pinched.panX, 0.001f)
        assertEquals(0f, pinched.panY, 0.001f)

        val panned = applyViewportPan(
            zoom = pinched.zoom,
            panX = pinched.panX,
            panY = pinched.panY,
            dx = 150f,
            dy = -80f,
            viewWidth = 1920f,
            viewHeight = 1080f,
            fbWidth = 1920,
            fbHeight = 1080,
        )
        assertEquals(2f, panned.zoom, 0.001f)
        assertEquals(150f, panned.panX, 0.001f)
        assertEquals(-80f, panned.panY, 0.001f)
    }

    @Test
    fun `isHardwareMouseGesture distinguishes mouse and SOURCE_MOUSE trackpad from touchscreen`() {
        assertFalse(
            isHardwareMouseGesture(
                pointerType = PointerType.Touch,
                buttons = PointerButtons(0),
                motionSource = InputDevice.SOURCE_TOUCHSCREEN,
            ),
        )
        assertTrue(
            isHardwareMouseGesture(
                pointerType = PointerType.Mouse,
                buttons = PointerButtons(0),
                motionSource = 0,
            ),
        )
        // A hardware touchpad often reports PointerType.Touch (TOOL_TYPE_FINGER)
        // with InputDevice.SOURCE_MOUSE (#622).
        assertTrue(
            isHardwareMouseGesture(
                pointerType = PointerType.Touch,
                buttons = PointerButtons(0),
                motionSource = InputDevice.SOURCE_MOUSE,
            ),
        )
    }

    @Test
    fun `resolveHardwareMouseButton maps primary secondary tertiary and buttonless trackpad`() {
        assertEquals(0, resolveHardwareMouseButton(PointerButtons(0), 0))
        assertEquals(1, resolveHardwareMouseButton(PointerButtons(MotionEvent.BUTTON_PRIMARY), 0))
        assertEquals(2, resolveHardwareMouseButton(PointerButtons(MotionEvent.BUTTON_TERTIARY), 0))
        assertEquals(3, resolveHardwareMouseButton(PointerButtons(MotionEvent.BUTTON_SECONDARY), 0))
        assertEquals(3, resolveHardwareMouseButton(PointerButtons(0), MotionEvent.BUTTON_SECONDARY))
        assertEquals(2, resolveHardwareMouseButton(PointerButtons(0), MotionEvent.BUTTON_TERTIARY))
    }

    @Test
    fun `DesktopWheelNotchAccumulator accumulates fractional scroll deltas into signed notches`() {
        val acc = DesktopWheelNotchAccumulator()
        assertEquals(0, acc.feed(-0.4f))
        assertEquals(1, acc.feed(-0.7f)) // -1.1f total -> +1 notch (up), -0.1f remainder
        assertEquals(-2, acc.feed(2.3f)) // +2.2f total -> -2 notches (down), +0.2f remainder
    }

    @Test
    fun `remainingTouchLongPressTimeoutMs counts down only while single-finger touch stays still`() {
        assertEquals(
            280L,
            remainingTouchLongPressTimeoutMs(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 4f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
                longPressMs = 400L,
                elapsedMs = 120L,
            ),
        )
        // Clamps to 1ms once elapsed reaches or exceeds timeout before firing
        assertEquals(
            1L,
            remainingTouchLongPressTimeoutMs(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 0f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
                longPressMs = 400L,
                elapsedMs = 450L,
            ),
        )
        // Disables timeout for hardware mouse, tap-then-drag follow-up, movement >= slop, multi-finger, or already fired
        assertEquals(
            Long.MAX_VALUE,
            remainingTouchLongPressTimeoutMs(
                isMouseDevice = true,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 0f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
                longPressMs = 400L,
                elapsedMs = 100L,
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            remainingTouchLongPressTimeoutMs(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 0f,
                touchSlopPx = 18f,
                isFollowUpTouch = true,
                longPressMs = 400L,
                elapsedMs = 100L,
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            remainingTouchLongPressTimeoutMs(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 18f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
                longPressMs = 400L,
                elapsedMs = 100L,
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            remainingTouchLongPressTimeoutMs(
                isMouseDevice = false,
                totalFingers = 2,
                longPressFired = false,
                dragging = false,
                totalMovement = 0f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
                longPressMs = 400L,
                elapsedMs = 100L,
            ),
        )
    }

    @Test
    fun `shouldFireTouchLongPressRightClick fires only on stationary single-finger non-followup touch`() {
        assertTrue(
            shouldFireTouchLongPressRightClick(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 5f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
            ),
        )
        assertFalse(
            shouldFireTouchLongPressRightClick(
                isMouseDevice = true,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 0f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
            ),
        )
        assertFalse(
            shouldFireTouchLongPressRightClick(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 0f,
                touchSlopPx = 18f,
                isFollowUpTouch = true,
            ),
        )
        assertFalse(
            shouldFireTouchLongPressRightClick(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = true,
                totalMovement = 0f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
            ),
        )
        assertFalse(
            shouldFireTouchLongPressRightClick(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = true,
                dragging = false,
                totalMovement = 0f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
            ),
        )
    }

    @Test
    fun `resolveTouchLongPressTarget uses virtualCursor in TOUCHPAD and screenToRemote in DIRECT`() {
        assertEquals(
            320 to 240,
            resolveTouchLongPressTarget(
                effectiveInputMode = "TOUCHPAD",
                virtualCursor = 320 to 240,
                directPos = { 800 to 600 },
            ),
        )
        assertEquals(
            800 to 600,
            resolveTouchLongPressTarget(
                effectiveInputMode = "DIRECT",
                virtualCursor = 320 to 240,
                directPos = { 800 to 600 },
            ),
        )
    }

    @Test
    fun `shouldFirePostGestureTap suppresses left-click after long-press right-click`() {
        assertTrue(
            shouldFirePostGestureTap(
                totalFingers = 1,
                totalMovement = 4f,
                touchSlopPx = 18f,
                longPressFired = false,
            ),
        )
        assertFalse(
            shouldFirePostGestureTap(
                totalFingers = 1,
                totalMovement = 4f,
                touchSlopPx = 18f,
                longPressFired = true,
            ),
        )
    }
}
