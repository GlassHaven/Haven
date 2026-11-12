package sh.haven.core.ui

import android.content.pm.ActivityInfo
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDesktopViewportTest {

    @Test
    fun `diffRemoteImeCommit handles append, leading space, CJK commit, backspace, and autocorrect`() {
        assertEquals("a" to 0, diffRemoteImeCommit("", "a"))
        assertEquals(" " to 0, diffRemoteImeCommit("", " "))
        assertEquals("h" to 0, diffRemoteImeCommit(" ", " h"))
        assertEquals(null to 0, diffRemoteImeCommit("abc", "abc"))

        var last = ""
        val emitted = mutableListOf<String>()
        for (newText in listOf("a", "ab", "abc", "abcA", "abcAB", "abcABC", "abcABC1", "abcABC12", "abcABC123")) {
            val (added, deleted) = diffRemoteImeCommit(last, newText)
            assertEquals(0, deleted)
            if (added != null) emitted.add(added)
            last = newText
        }
        assertEquals(listOf("a", "b", "c", "A", "B", "C", "1", "2", "3"), emitted)

        assertEquals("ni" to 0, diffRemoteImeCommit("", "ni"))
        assertEquals("你" to 2, diffRemoteImeCommit("ni", "你"))
        assertEquals(null to 2, diffRemoteImeCommit("hello", "hel"))
        assertEquals(null to 3, diffRemoteImeCommit("abc", ""))
        assertEquals("he" to 2, diffRemoteImeCommit("teh", "the"))
        assertEquals("low" to 1, diffRemoteImeCommit("horld", "hlowrld"))
    }

    @Test
    fun `screenToRemoteDesktop maps letterbox, zoomed-panned, and coverFill coordinates`() {
        // Zero view size returns origin safely
        assertEquals(
            0 to 0,
            screenToRemoteDesktop(Offset(50f, 50f), IntSize.Zero, 1920, 1080, 1f, 0f, 0f),
        )

        // Exact 16:9 fit: center maps to (960, 540)
        assertEquals(
            960 to 540,
            screenToRemoteDesktop(Offset(960f, 540f), IntSize(1920, 1080), 1920, 1080, 1f, 0f, 0f),
        )

        // Letterboxed 16:9 framebuffer inside 1920x1200 viewport (top/bottom bars = 60px each)
        assertEquals(
            0 to 0,
            screenToRemoteDesktop(Offset(0f, 60f), IntSize(1920, 1200), 1920, 1080, 1f, 0f, 0f),
        )
        // Touch inside top black bar clamps to y=0
        assertEquals(
            100 to 0,
            screenToRemoteDesktop(Offset(100f, 10f), IntSize(1920, 1200), 1920, 1080, 1f, 0f, 0f),
        )

        // Zoom 2x with pan (+200, -100) on 1920x1080
        // screen (1160, 440) -> local = ((1160 - 960 - 200)/2 + 960, (440 - 540 - (-100))/2 + 540) = (960, 540)
        assertEquals(
            960 to 540,
            screenToRemoteDesktop(Offset(1160f, 440f), IntSize(1920, 1080), 1920, 1080, 2f, 200f, -100f),
        )

        // coverFill = true on 2400x1080 ultrawide phone displaying 1920x1080 app window:
        // fitScale = max(2400/1920=1.25, 1080/1080=1.0) = 1.25 -> dst = 2400x1350, offsetY = -135
        // Top-left of screen (0, 0) lands at y = ((0 - (-135)) / 1.25).toInt() = 108
        assertEquals(
            0 to 108,
            screenToRemoteDesktop(
                Offset(0f, 0f),
                IntSize(2400, 1080),
                1920,
                1080,
                zoom = 1f,
                panX = 0f,
                panY = 0f,
                coverFill = true,
            ),
        )
    }

    @Test
    fun `applyRemoteViewportPinch and applyRemoteViewportPan honour zoom bounds and coverFill clamping`() {
        val pinched = applyRemoteViewportPinch(
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

        // VNC desktop allows minZoom = 0.5f, maxZoom = 10f
        val zoomedOutVnc = applyRemoteViewportPinch(
            zoom = 1f,
            panX = 100f,
            panY = 50f,
            prevSpan = 200f,
            span = 50f,
            centroid = Offset(960f, 540f),
            viewWidth = 1920f,
            viewHeight = 1080f,
            fbWidth = 1920,
            fbHeight = 1080,
            minZoom = 0.5f,
            maxZoom = 10f,
        )
        assertEquals(0.5f, zoomedOutVnc.zoom, 0.001f)
        assertEquals(0f, zoomedOutVnc.panX, 0.001f)
        assertEquals(0f, zoomedOutVnc.panY, 0.001f)

        // Panning past cover edge clamps to maxPan = (1920*2 - 1920)/2 = 960
        val panned = applyRemoteViewportPan(
            zoom = 2f,
            panX = 900f,
            panY = 0f,
            dx = 300f,
            dy = -50f,
            viewWidth = 1920f,
            viewHeight = 1080f,
            fbWidth = 1920,
            fbHeight = 1080,
        )
        assertEquals(960f, panned.panX, 0.001f)
        assertEquals(-50f, panned.panY, 0.001f)
    }

    @Test
    fun `remoteCameraFollowClamped keeps cursor inside dead-zone without exposing black border`() {
        // No-op when unzoomed
        assertEquals(
            0f to 0f,
            remoteCameraFollowClamped(
                cursorFbX = 1919,
                cursorFbY = 1079,
                fbWidth = 1920,
                fbHeight = 1080,
                viewW = 1920f,
                viewH = 1080f,
                zoom = 1f,
                panX = 0f,
                panY = 0f,
            ),
        )

        // At zoom=2 on 1920x1080, cursor at extreme right edge (1919) snaps pan negative,
        // clamped so panX >= -960f (never exposing black past the right edge)
        val (clampedX, clampedY) = remoteCameraFollowClamped(
            cursorFbX = 1919,
            cursorFbY = 540,
            fbWidth = 1920,
            fbHeight = 1080,
            viewW = 1920f,
            viewH = 1080f,
            zoom = 2f,
            panX = 0f,
            panY = 0f,
        )
        assertTrue("Expected negative panX to follow cursor right, got $clampedX", clampedX < 0f)
        assertTrue("Expected panX >= -960f cover bound, got $clampedX", clampedX >= -960f)
        assertEquals(0f, clampedY, 0.001f)
    }

    @Test
    fun `hardware mouse detection, button resolution, wheel notch accumulator, and long-press predicates`() {
        assertFalse(isRemoteHardwareMouseGesture(PointerType.Touch, PointerButtons(0), InputDevice.SOURCE_TOUCHSCREEN))
        assertTrue(isRemoteHardwareMouseGesture(PointerType.Mouse, PointerButtons(0), 0))
        assertTrue(isRemoteHardwareMouseGesture(PointerType.Touch, PointerButtons(0), InputDevice.SOURCE_MOUSE))

        assertEquals(0, resolveRemoteHardwareMouseButton(PointerButtons(0), 0))
        assertEquals(1, resolveRemoteHardwareMouseButton(PointerButtons(MotionEvent.BUTTON_PRIMARY), 0))
        assertEquals(2, resolveRemoteHardwareMouseButton(PointerButtons(MotionEvent.BUTTON_TERTIARY), 0))
        assertEquals(3, resolveRemoteHardwareMouseButton(PointerButtons(MotionEvent.BUTTON_SECONDARY), 0))

        val acc = RemoteWheelNotchAccumulator()
        assertEquals(0, acc.feed(-0.4f))
        assertEquals(1, acc.feed(-0.7f))
        assertEquals(-2, acc.feed(2.3f))

        // Held toolbar mouse button suppresses both long-press right-click and post-gesture tap (#183)
        assertTrue(
            shouldFireRemoteTouchLongPressRightClick(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 2f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
                heldButton = null,
            ),
        )
        assertFalse(
            shouldFireRemoteTouchLongPressRightClick(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 2f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
                heldButton = 1,
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            remainingRemoteTouchLongPressTimeoutMs(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 2f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
                heldButton = 1,
                longPressMs = 400L,
                elapsedMs = 50L,
            ),
        )
        assertTrue(shouldFireRemotePostGestureTap(1, 3f, 18f, longPressFired = false, heldButton = null))
        assertFalse(shouldFireRemotePostGestureTap(1, 3f, 18f, longPressFired = false, heldButton = 2))
    }

    @Test
    fun `DesktopOrientationMode cycles Landscape to Portrait to Auto and back`() {
        assertEquals(DesktopOrientationMode.Landscape, DesktopOrientationMode.fromActivityValue(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE))
        assertEquals(DesktopOrientationMode.Portrait, DesktopOrientationMode.Landscape.next())
        assertEquals(DesktopOrientationMode.Auto, DesktopOrientationMode.Portrait.next())
        assertEquals(DesktopOrientationMode.Landscape, DesktopOrientationMode.Auto.next())
        assertEquals(DesktopOrientationMode.Landscape, DesktopOrientationMode.fromActivityValue(-999))
    }

    @Test
    fun `configurable desktop mouse gestures resolve long-press, two-finger, and three-finger actions`() {
        assertEquals(DesktopGestureAction.RIGHT_CLICK, DesktopGestureAction.fromStorage("RIGHT_CLICK", DesktopGestureAction.NONE))
        assertEquals(DesktopGestureAction.MIDDLE_CLICK, DesktopGestureAction.fromStorage("UNKNOWN", DesktopGestureAction.MIDDLE_CLICK))
        assertEquals(DesktopGestureAction.NONE, DesktopGestureAction.fromStorage(null, DesktopGestureAction.NONE))

        val fired = mutableListOf<String>()
        for (action in DesktopGestureAction.entries) {
            dispatchDesktopGestureAction(
                action = action,
                x = 120,
                y = 340,
                onLeftClick = { x, y -> fired += "L:$x,$y" },
                onMiddleClick = { x, y -> fired += "M:$x,$y" },
                onRightClick = { x, y -> fired += "R:$x,$y" },
            )
        }
        assertEquals(listOf("R:120,340", "M:120,340", "L:120,340"), fired)

        // Disabling long-press cancels the timeout timer AND suppresses stationary holds >= longPressMs,
        // while keeping normal short taps (< longPressMs) and hardware mouse clicks intact (#836).
        assertFalse(
            shouldFireRemoteTouchLongPressRightClick(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 1f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
                longPressAction = DesktopGestureAction.NONE,
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            remainingRemoteTouchLongPressTimeoutMs(
                isMouseDevice = false,
                totalFingers = 1,
                longPressFired = false,
                dragging = false,
                totalMovement = 1f,
                touchSlopPx = 18f,
                isFollowUpTouch = false,
                longPressAction = DesktopGestureAction.NONE,
                longPressMs = 400L,
                elapsedMs = 50L,
            ),
        )
        assertTrue(
            shouldFireRemotePostGestureTap(
                totalFingers = 1,
                totalMovement = 2f,
                touchSlopPx = 18f,
                longPressFired = false,
                isMouseDevice = false,
                longPressDisabled = true,
                gestureDurationMs = 150L,
                longPressMs = 400L,
            ),
        )
        assertFalse(
            shouldFireRemotePostGestureTap(
                totalFingers = 1,
                totalMovement = 2f,
                touchSlopPx = 18f,
                longPressFired = false,
                isMouseDevice = false,
                longPressDisabled = true,
                gestureDurationMs = 450L,
                longPressMs = 400L,
            ),
        )
        assertTrue(
            shouldFireRemotePostGestureTap(
                totalFingers = 1,
                totalMovement = 0f,
                touchSlopPx = 18f,
                longPressFired = false,
                isMouseDevice = true,
                longPressDisabled = true,
                gestureDurationMs = 900L,
                longPressMs = 400L,
            ),
        )

        val precisionTouchpad = DesktopMouseGestureConfig(
            longPressAction = DesktopGestureAction.NONE,
            twoFingerTapAction = DesktopGestureAction.RIGHT_CLICK,
            threeFingerTapAction = DesktopGestureAction.MIDDLE_CLICK,
        )
        assertEquals(
            DesktopGestureAction.RIGHT_CLICK,
            resolveRemoteMultiFingerTapAction(
                isMouseDevice = false,
                totalFingers = 2,
                multiFingerMovement = 4f,
                touchSlopPx = 18f,
                longPressFired = false,
                heldButton = null,
                gestures = precisionTouchpad,
            ),
        )
        assertEquals(
            DesktopGestureAction.MIDDLE_CLICK,
            resolveRemoteMultiFingerTapAction(
                isMouseDevice = false,
                totalFingers = 3,
                multiFingerMovement = 6f,
                touchSlopPx = 18f,
                longPressFired = false,
                heldButton = null,
                gestures = precisionTouchpad,
            ),
        )
        // Pinch/scroll motion >= touchSlopPx suppresses multi-finger tap click
        assertEquals(
            DesktopGestureAction.NONE,
            resolveRemoteMultiFingerTapAction(
                isMouseDevice = false,
                totalFingers = 2,
                multiFingerMovement = 24f,
                touchSlopPx = 18f,
                longPressFired = false,
                heldButton = null,
                gestures = precisionTouchpad,
            ),
        )
        // Held toolbar button or 4-finger gesture yields NONE
        assertEquals(
            DesktopGestureAction.NONE,
            resolveRemoteMultiFingerTapAction(
                isMouseDevice = false,
                totalFingers = 2,
                multiFingerMovement = 0f,
                touchSlopPx = 18f,
                longPressFired = false,
                heldButton = 1,
                gestures = precisionTouchpad,
            ),
        )
        assertEquals(
            DesktopGestureAction.NONE,
            resolveRemoteMultiFingerTapAction(
                isMouseDevice = false,
                totalFingers = 4,
                multiFingerMovement = 0f,
                touchSlopPx = 18f,
                longPressFired = false,
                heldButton = null,
                gestures = precisionTouchpad,
            ),
        )
    }

    @Test
    fun `RemoteDesktopViewportState seeds virtualCursor from late CURSOR_INIT before user touch and preserves cursor across Saver`() {
        // Initial mount happens when display frame arrives while cursor channel is still at (0, 0).
        val state = RemoteDesktopViewportState(initialCursor = 0 to 0)
        state.syncVirtualCursorSeed(inputMode = "TOUCHPAD", pointerPos = 0 to 0)
        assertEquals(0 to 0, state.virtualCursor)

        // Late SPICE_MSG_CURSOR_INIT arrives with guest pointer at (220, 190) before any user touch:
        // virtualCursor must adopt (220, 190) so relative server-mode deltas can reach (0, 0) (#608).
        state.syncVirtualCursorSeed(inputMode = "TOUCHPAD", pointerPos = 220 to 190)
        assertEquals(220 to 190, state.virtualCursor)

        // User now steps (-220, -190) on touchpad -> reaches top-left (0, 0) cleanly without clamping early.
        assertEquals(0 to 0, state.stepVirtualCursor(-220f, -190f, fbWidth = 1024, fbHeight = 768))

        // Once the user has moved the touchpad cursor, server pointerPos echoes must NOT snap
        // sub-pixel virtualCursorF or overwrite user-driven tracking.
        state.stepVirtualCursor(15.6f, 24.4f, fbWidth = 1024, fbHeight = 768)
        assertEquals(15 to 24, state.virtualCursor)
        state.syncVirtualCursorSeed(inputMode = "TOUCHPAD", pointerPos = 400 to 300)
        assertEquals(15 to 24, state.virtualCursor)
        assertEquals(15.6f, state.virtualCursorF.x, 0.001f)
        assertEquals(24.4f, state.virtualCursorF.y, 0.001f)

        // Switching inputMode (DIRECT -> TOUCHPAD) re-seeds from current pointerPos.
        state.syncVirtualCursorSeed(inputMode = "DIRECT", pointerPos = 512 to 384)
        state.syncVirtualCursorSeed(inputMode = "TOUCHPAD", pointerPos = 512 to 384)
        assertEquals(512 to 384, state.virtualCursor)

        // Saver round-trip (fullscreen / rotation restore) preserves sub-pixel virtualCursor and user-moved bit.
        state.applyTransform(RemoteViewportTransform(zoom = 1.5f, panX = -40f, panY = 25f))
        state.stepVirtualCursor(10.5f, -5.25f, fbWidth = 1024, fbHeight = 768)
        val saved = with(RemoteDesktopViewportState.Saver) {
            androidx.compose.runtime.saveable.SaverScope { true }.save(state)
        }!!
        val restored = RemoteDesktopViewportState.Saver.restore(saved)!!
        assertEquals(1.5f, restored.zoom, 0.001f)
        assertEquals(-40f, restored.panX, 0.001f)
        assertEquals(25f, restored.panY, 0.001f)
        assertEquals(522 to 378, restored.virtualCursor)
        assertEquals(522.5f, restored.virtualCursorF.x, 0.001f)
        assertEquals(378.75f, restored.virtualCursorF.y, 0.001f)
        // Restored user-moved state must also resist stale server pointerPos overwrite:
        restored.syncVirtualCursorSeed(inputMode = "TOUCHPAD", pointerPos = 0 to 0)
        assertEquals(522 to 378, restored.virtualCursor)
    }
}
