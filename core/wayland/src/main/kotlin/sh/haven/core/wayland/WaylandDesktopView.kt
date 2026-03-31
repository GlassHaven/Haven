package sh.haven.core.wayland

import android.annotation.SuppressLint
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import sh.haven.core.data.preferences.NavBlockMode
import sh.haven.core.data.preferences.ToolbarLayout

/**
 * Composable that displays the native Wayland compositor output
 * and forwards touch + keyboard input to the compositor.
 * Zoom and pan are handled at the Compose level via graphicsLayer transforms.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun WaylandDesktopView(
    modifier: Modifier = Modifier,
    toolbarLayout: ToolbarLayout = ToolbarLayout.DEFAULT,
    navBlockMode: NavBlockMode = NavBlockMode.ALIGNED,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        onDispose {
            WaylandBridge.nativeSetSurface(null)
        }
    }

    Column(modifier = modifier) {
    Box(
        modifier = Modifier.weight(1f)
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Wait for first finger
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var prevCentroid = Offset.Zero
                    var prevSpan = 0f
                    var gestureStarted = false

                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointers = event.changes.filter { it.pressed }
                        val count = pointers.size

                        // Only intercept multi-finger gestures (zoom/pan).
                        // Single-finger events pass through to the AndroidView.
                        if (count >= 2) {
                            val centroid = Offset(
                                pointers.map { it.position.x }.average().toFloat(),
                                pointers.map { it.position.y }.average().toFloat(),
                            )
                            val span = pointers.map {
                                (it.position - centroid).getDistance()
                            }.average().toFloat()

                            if (gestureStarted && prevSpan > 0f && span > 0f) {
                                val scaleFactor = span / prevSpan
                                val newZoom = (zoom * scaleFactor).coerceIn(0.5f, 5f)
                                panX += (centroid.x - panX) * (1 - scaleFactor)
                                panY += (centroid.y - panY) * (1 - scaleFactor)
                                zoom = newZoom

                                val dx = centroid.x - prevCentroid.x
                                val dy = centroid.y - prevCentroid.y
                                panX += dx
                                panY += dy
                            }
                            prevCentroid = centroid
                            prevSpan = span
                            gestureStarted = true
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AndroidView(
            factory = { context ->
                @SuppressLint("ClickableViewAccessibility")
                object : TextureView(context) {
                    init {
                        isFocusable = true
                        isFocusableInTouchMode = true

                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var nativeSurface: Surface? = null
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                android.util.Log.i("WaylandTV", "TextureAvailable: ${w}x${h} view=${width}x${height}")
                                st.setDefaultBufferSize(w, h)
                                nativeSurface = Surface(st)
                                WaylandBridge.nativeSetSurface(nativeSurface)
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                android.util.Log.i("WaylandTV", "TextureSizeChanged: ${w}x${h} view=${width}x${height}")
                                st.setDefaultBufferSize(w, h)
                            }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                WaylandBridge.nativeSetSurface(null)
                                nativeSurface?.release()
                                nativeSurface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }

                        setOnTouchListener { view, event ->
                            // Single-finger: pointer events + keyboard
                            if (event.pointerCount == 1) {
                                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                    if (!hasFocus()) requestFocus()
                                    val imm = context.getSystemService(
                                        android.content.Context.INPUT_METHOD_SERVICE
                                    ) as android.view.inputmethod.InputMethodManager
                                    imm.restartInput(this)
                                    imm.showSoftInput(this, 0)
                                }
                                val nx = event.x / view.width
                                val ny = event.y / view.height
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN ->
                                        WaylandBridge.nativeSendTouch(0, nx, ny)
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                        WaylandBridge.nativeSendTouch(1, nx, ny)
                                    MotionEvent.ACTION_MOVE ->
                                        WaylandBridge.nativeSendTouch(2, nx, ny)
                                }
                            }
                            true
                        }
                    }

                    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
                        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT
                        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                            EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        val view = this
                        return object : BaseInputConnection(view, false) {
                            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                                text?.forEach { ch -> sendCharAsEvdev(ch) }
                                return true
                            }

                            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                                repeat(beforeLength) {
                                    WaylandBridge.nativeSendKey(14, 1) // KEY_BACKSPACE
                                    WaylandBridge.nativeSendKey(14, 0)
                                }
                                return true
                            }

                            override fun sendKeyEvent(event: AndroidKeyEvent): Boolean {
                                val evdev = androidToEvdev(event.keyCode)
                                if (evdev >= 0) {
                                    val pressed = if (event.action == AndroidKeyEvent.ACTION_DOWN) 1 else 0
                                    WaylandBridge.nativeSendKey(evdev, pressed)
                                    return true
                                }
                                return super.sendKeyEvent(event)
                            }
                        }
                    }

                    override fun onCheckIsTextEditor(): Boolean = true

                    override fun onKeyDown(keyCode: Int, event: AndroidKeyEvent?): Boolean {
                        val evdev = androidToEvdev(keyCode)
                        if (evdev >= 0) {
                            WaylandBridge.nativeSendKey(evdev, 1)
                            return true
                        }
                        return super.onKeyDown(keyCode, event)
                    }

                    override fun onKeyUp(keyCode: Int, event: AndroidKeyEvent?): Boolean {
                        val evdev = androidToEvdev(keyCode)
                        if (evdev >= 0) {
                            WaylandBridge.nativeSendKey(evdev, 0)
                            return true
                        }
                        return super.onKeyUp(keyCode, event)
                    }

                    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: AndroidKeyEvent?): Boolean {
                        val chars = event?.characters ?: return super.onKeyMultiple(keyCode, repeatCount, event)
                        for (ch in chars) { sendCharAsEvdev(ch) }
                        return true
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                },
        )
    }
    WaylandToolbar(
        layout = toolbarLayout,
        navBlockMode = navBlockMode,
        modifier = Modifier.fillMaxWidth(),
    )
    } // Column
}

/** Map Android KeyEvent keyCode to Linux evdev scancode (input-event-codes.h). */
private fun androidToEvdev(keyCode: Int): Int = when (keyCode) {
    AndroidKeyEvent.KEYCODE_A -> 30
    AndroidKeyEvent.KEYCODE_B -> 48
    AndroidKeyEvent.KEYCODE_C -> 46
    AndroidKeyEvent.KEYCODE_D -> 32
    AndroidKeyEvent.KEYCODE_E -> 18
    AndroidKeyEvent.KEYCODE_F -> 33
    AndroidKeyEvent.KEYCODE_G -> 34
    AndroidKeyEvent.KEYCODE_H -> 35
    AndroidKeyEvent.KEYCODE_I -> 23
    AndroidKeyEvent.KEYCODE_J -> 36
    AndroidKeyEvent.KEYCODE_K -> 37
    AndroidKeyEvent.KEYCODE_L -> 38
    AndroidKeyEvent.KEYCODE_M -> 50
    AndroidKeyEvent.KEYCODE_N -> 49
    AndroidKeyEvent.KEYCODE_O -> 24
    AndroidKeyEvent.KEYCODE_P -> 25
    AndroidKeyEvent.KEYCODE_Q -> 16
    AndroidKeyEvent.KEYCODE_R -> 19
    AndroidKeyEvent.KEYCODE_S -> 31
    AndroidKeyEvent.KEYCODE_T -> 20
    AndroidKeyEvent.KEYCODE_U -> 22
    AndroidKeyEvent.KEYCODE_V -> 47
    AndroidKeyEvent.KEYCODE_W -> 17
    AndroidKeyEvent.KEYCODE_X -> 45
    AndroidKeyEvent.KEYCODE_Y -> 21
    AndroidKeyEvent.KEYCODE_Z -> 44
    AndroidKeyEvent.KEYCODE_0 -> 11
    AndroidKeyEvent.KEYCODE_1 -> 2
    AndroidKeyEvent.KEYCODE_2 -> 3
    AndroidKeyEvent.KEYCODE_3 -> 4
    AndroidKeyEvent.KEYCODE_4 -> 5
    AndroidKeyEvent.KEYCODE_5 -> 6
    AndroidKeyEvent.KEYCODE_6 -> 7
    AndroidKeyEvent.KEYCODE_7 -> 8
    AndroidKeyEvent.KEYCODE_8 -> 9
    AndroidKeyEvent.KEYCODE_9 -> 10
    AndroidKeyEvent.KEYCODE_SPACE -> 57
    AndroidKeyEvent.KEYCODE_ENTER -> 28
    AndroidKeyEvent.KEYCODE_DEL -> 14 // backspace
    AndroidKeyEvent.KEYCODE_TAB -> 15
    AndroidKeyEvent.KEYCODE_ESCAPE -> 1
    AndroidKeyEvent.KEYCODE_DPAD_UP -> 103
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 108
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> 105
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 106
    AndroidKeyEvent.KEYCODE_SHIFT_LEFT -> 42
    AndroidKeyEvent.KEYCODE_SHIFT_RIGHT -> 54
    AndroidKeyEvent.KEYCODE_CTRL_LEFT -> 29
    AndroidKeyEvent.KEYCODE_CTRL_RIGHT -> 97
    AndroidKeyEvent.KEYCODE_ALT_LEFT -> 56
    AndroidKeyEvent.KEYCODE_ALT_RIGHT -> 100
    AndroidKeyEvent.KEYCODE_MINUS -> 12
    AndroidKeyEvent.KEYCODE_EQUALS -> 13
    AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> 26
    AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> 27
    AndroidKeyEvent.KEYCODE_BACKSLASH -> 43
    AndroidKeyEvent.KEYCODE_SEMICOLON -> 39
    AndroidKeyEvent.KEYCODE_APOSTROPHE -> 40
    AndroidKeyEvent.KEYCODE_GRAVE -> 41
    AndroidKeyEvent.KEYCODE_COMMA -> 51
    AndroidKeyEvent.KEYCODE_PERIOD -> 52
    AndroidKeyEvent.KEYCODE_SLASH -> 53
    AndroidKeyEvent.KEYCODE_AT -> 3 // mapped to '2' key (shift+2 = @)
    AndroidKeyEvent.KEYCODE_FORWARD_DEL -> 111
    AndroidKeyEvent.KEYCODE_PAGE_UP -> 104
    AndroidKeyEvent.KEYCODE_PAGE_DOWN -> 109
    AndroidKeyEvent.KEYCODE_MOVE_HOME -> 102
    AndroidKeyEvent.KEYCODE_MOVE_END -> 107
    AndroidKeyEvent.KEYCODE_INSERT -> 110
    AndroidKeyEvent.KEYCODE_F1 -> 59
    AndroidKeyEvent.KEYCODE_F2 -> 60
    AndroidKeyEvent.KEYCODE_F3 -> 61
    AndroidKeyEvent.KEYCODE_F4 -> 62
    AndroidKeyEvent.KEYCODE_F5 -> 63
    AndroidKeyEvent.KEYCODE_F6 -> 64
    AndroidKeyEvent.KEYCODE_F7 -> 65
    AndroidKeyEvent.KEYCODE_F8 -> 66
    AndroidKeyEvent.KEYCODE_F9 -> 67
    AndroidKeyEvent.KEYCODE_F10 -> 68
    AndroidKeyEvent.KEYCODE_F11 -> 87
    AndroidKeyEvent.KEYCODE_F12 -> 88
    else -> -1
}

private const val KEY_LEFTSHIFT = 42

/**
 * Map a typed character to (evdev keycode, needsShift).
 * Returns (-1, false) for unmapped characters.
 */
internal fun charToEvdevWithShift(ch: Char): Pair<Int, Boolean> = when (ch) {
    'a' -> 30 to false; 'b' -> 48 to false; 'c' -> 46 to false
    'd' -> 32 to false; 'e' -> 18 to false; 'f' -> 33 to false
    'g' -> 34 to false; 'h' -> 35 to false; 'i' -> 23 to false
    'j' -> 36 to false; 'k' -> 37 to false; 'l' -> 38 to false
    'm' -> 50 to false; 'n' -> 49 to false; 'o' -> 24 to false
    'p' -> 25 to false; 'q' -> 16 to false; 'r' -> 19 to false
    's' -> 31 to false; 't' -> 20 to false; 'u' -> 22 to false
    'v' -> 47 to false; 'w' -> 17 to false; 'x' -> 45 to false
    'y' -> 21 to false; 'z' -> 44 to false
    'A' -> 30 to true; 'B' -> 48 to true; 'C' -> 46 to true
    'D' -> 32 to true; 'E' -> 18 to true; 'F' -> 33 to true
    'G' -> 34 to true; 'H' -> 35 to true; 'I' -> 23 to true
    'J' -> 36 to true; 'K' -> 37 to true; 'L' -> 38 to true
    'M' -> 50 to true; 'N' -> 49 to true; 'O' -> 24 to true
    'P' -> 25 to true; 'Q' -> 16 to true; 'R' -> 19 to true
    'S' -> 31 to true; 'T' -> 20 to true; 'U' -> 22 to true
    'V' -> 47 to true; 'W' -> 17 to true; 'X' -> 45 to true
    'Y' -> 21 to true; 'Z' -> 44 to true
    '0' -> 11 to false; '1' -> 2 to false; '2' -> 3 to false
    '3' -> 4 to false; '4' -> 5 to false; '5' -> 6 to false
    '6' -> 7 to false; '7' -> 8 to false; '8' -> 9 to false
    '9' -> 10 to false
    ' ' -> 57 to false; '\n' -> 28 to false; '\t' -> 15 to false
    '.' -> 52 to false; ',' -> 51 to false; '/' -> 53 to false
    '-' -> 12 to false; '=' -> 13 to false; ';' -> 39 to false
    '\'' -> 40 to false; '`' -> 41 to false; '[' -> 26 to false
    ']' -> 27 to false; '\\' -> 43 to false
    '!' -> 2 to true;  '@' -> 3 to true;  '#' -> 4 to true
    '$' -> 5 to true;  '%' -> 6 to true;  '^' -> 7 to true
    '&' -> 8 to true;  '*' -> 9 to true;  '(' -> 10 to true
    ')' -> 11 to true; '_' -> 12 to true; '+' -> 13 to true
    '{' -> 26 to true; '}' -> 27 to true; '|' -> 43 to true
    ':' -> 39 to true; '"' -> 40 to true; '~' -> 41 to true
    '<' -> 51 to true; '>' -> 52 to true; '?' -> 53 to true
    else -> -1 to false
}

/** Send a character as evdev key event(s), wrapping with Shift when needed. */
internal fun sendCharAsEvdev(ch: Char) {
    val (evdev, needsShift) = charToEvdevWithShift(ch)
    if (evdev < 0) return
    if (needsShift) WaylandBridge.nativeSendKey(KEY_LEFTSHIFT, 1)
    WaylandBridge.nativeSendKey(evdev, 1)
    WaylandBridge.nativeSendKey(evdev, 0)
    if (needsShift) WaylandBridge.nativeSendKey(KEY_LEFTSHIFT, 0)
}
