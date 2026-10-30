package sh.haven.core.glkvm

import kotlin.math.roundToInt

/**
 * #GLKVM pure coordinate and keyboard-layout math for the GL.iNet GLKVM
 * (PiKVM kvmd fork).
 *
 * The device HID mouse takes signed 16-bit absolutes (`-32768..32767`) across
 * the active target desktop area. On a native 16:9 target display, the active
 * desktop spans the full frame (`activeLeftPx = 0`, `activeWidthPx = frameW`).
 * When a 16:10 laptop display is mirrored/pillarboxed inside a 16:9 HDMI
 * capture frame (e.g. 2304x1440 active area centered inside 2560x1440 with
 * 128px black bars on each side), the target OS maps the USB HID absolute
 * digitizer across the active desktop `[activeLeftPx, activeLeftPx + activeWidthPx]`
 * rather than the outer HDMI pillarbox bars.
 */
object GlkvmGeometry {
    const val X_OFF_FRAC = 0.0
    const val X_SPAN_FRAC = 1.0

    /**
     * px → device int for the x axis across the active desktop region
     * `[activeLeftPx, activeLeftPx + activeWidthPx]`, clamped to int16.
     */
    fun vFromPxX(
        x: Double,
        frameW: Int,
        activeLeftPx: Int = 0,
        activeWidthPx: Int = frameW,
    ): Int {
        val span = activeWidthPx.coerceAtLeast(1)
        val v = ((x - activeLeftPx) * 65535.0 / span - 32768.0).roundToInt()
        return v.coerceIn(-32768, 32767)
    }

    /**
     * px → device int for the y axis across the active desktop region
     * `[activeTopPx, activeTopPx + activeHeightPx]`, clamped to int16.
     */
    fun vFromPxY(
        y: Double,
        frameH: Int,
        activeTopPx: Int = 0,
        activeHeightPx: Int = frameH,
    ): Int {
        val span = activeHeightPx.coerceAtLeast(1)
        val v = ((y - activeTopPx) * 65535.0 / span - 32768.0).roundToInt()
        return v.coerceIn(-32768, 32767)
    }

    /** Inverse of [vFromPxX]/[vFromPxY] — device ints back to px. */
    fun pxFromV(
        vx: Int,
        vy: Int,
        frameW: Int,
        frameH: Int,
        activeLeftPx: Int = 0,
        activeWidthPx: Int = frameW,
        activeTopPx: Int = 0,
        activeHeightPx: Int = frameH,
    ): Pair<Double, Double> {
        val x = activeLeftPx + activeWidthPx.coerceAtLeast(1) * (vx + 32768) / 65535.0
        val y = activeTopPx + activeHeightPx.coerceAtLeast(1) * (vy + 32768) / 65535.0
        return x to y
    }

    /**
     * Detect symmetric horizontal pillarbox bars from column max-channel intensities.
     *
     * @param frameW full captured frame width in pixels
     * @param isColumnBlack predicate returning true when column `x` (`0 until frameW`)
     *   is pure black across sampled vertical scanlines
     * @return `activeLeftPx to activeWidthPx` (`0 to frameW` when no symmetric pillarbox bars exist)
     */
    inline fun detectHorizontalPillarbox(
        frameW: Int,
        isColumnBlack: (x: Int) -> Boolean,
    ): Pair<Int, Int> {
        if (frameW <= 0) return 0 to frameW
        val maxMargin = (frameW * 0.20).toInt()
        var leftBar = 0
        while (leftBar < maxMargin && isColumnBlack(leftBar)) {
            leftBar++
        }
        var rightBar = 0
        while (rightBar < maxMargin && isColumnBlack(frameW - 1 - rightBar)) {
            rightBar++
        }
        val minMargin = (frameW * 0.02).toInt().coerceAtLeast(8)
        val tolerance = (frameW * 0.005).toInt().coerceAtLeast(6)
        if (leftBar >= minMargin && rightBar >= minMargin && kotlin.math.abs(leftBar - rightBar) <= tolerance) {
            val margin = minOf(leftBar, rightBar)
            val activeW = frameW - 2 * margin
            if (activeW > frameW / 2) {
                return margin to activeW
            }
        }
        return 0 to frameW
    }

    /**
     * Wheel steps the device expects. Measured: one client wheel notch
     * down ≈ device delta_y = -3 (and +3 up). One POST per notch.
     */
    const val WHEEL_STEP = 3

    /**
     * Max per-channel intensity (0..255) considered HDMI black in JPEG pillarbox bars.
     * Studio/limited-range HDMI video (BT.709 / YCbCr 16..235) encodes black at
     * RGB(16, 16, 16), with 4:2:0 JPEG ringing up to ~21 on the 1–2px border transition.
     */
    const val PILLARBOX_BLACK_MAX = 22

    /**
     * Max RGB channel spread (`max(r,g,b) - min(r,g,b)`) for HDMI black pillarbox bars,
     * rejecting dark tinted desktop UI while tolerating JPEG chroma subsampling ringing.
     */
    const val PILLARBOX_CHROMA_SPREAD_MAX = 8

    /** True when `(r, g, b)` (`0..255`) is full-range or studio-range HDMI black. */
    fun isPillarboxBlackRgb(r: Int, g: Int, b: Int): Boolean {
        val maxCh = maxOf(r, maxOf(g, b))
        if (maxCh > PILLARBOX_BLACK_MAX) return false
        val minCh = minOf(r, minOf(g, b))
        return (maxCh - minCh) <= PILLARBOX_CHROMA_SPREAD_MAX
    }
}

/**
 * Characters typed through [charSequence]: maps each char to a
 * KeyboardEvent.code name (a *position* code — the target OS then applies
 * ITS layout to the physical position) plus whether Shift is held.
 *
 * kvmd's server-side printer (`/api/hid/print`) bakes in a US layout and
 * mangles punctuation on other targets (verified: `"` → `@`, `\` → `#` on a
 * UK Windows target, and the GL fork ignores its `layout=` query param), so
 * table coverage is how text is typed correctly. Chars outside the table do
 * not produce a key sequence — they still exist, but the caller must fall
 * back to the printer path (phase-2 verb), which is fine for accents.
 */
object GlkvmLayout {

    /** (code, shift) — `shift` describes the PRIMARY char of the entry. */
    data class Key(val code: String, val shift: Boolean)

    private val letters: Map<Char, Key> = buildMap {
        for (i in 0..25) {
            val lower = ('a' + i)
            val upper = ('A' + i)
            put(lower, Key("Key$upper", false))
            put(upper, Key("Key$upper", true))
        }
    }

    private val ukPunct: Map<Char, Key> = mapOf(
        ' ' to Key("Space", false), '\t' to Key("Tab", false), '\n' to Key("Enter", false),
        '1' to Key("Digit1", false), '!' to Key("Digit1", true),
        '2' to Key("Digit2", false), '"' to Key("Digit2", true),
        '3' to Key("Digit3", false),   // UK shifted 3 is £ — non-ASCII, printer path
        '4' to Key("Digit4", false), '$' to Key("Digit4", true),
        '5' to Key("Digit5", false), '%' to Key("Digit5", true),
        '6' to Key("Digit6", false), '^' to Key("Digit6", true),
        '7' to Key("Digit7", false), '&' to Key("Digit7", true),
        '8' to Key("Digit8", false), '*' to Key("Digit8", true),
        '9' to Key("Digit9", false), '(' to Key("Digit9", true),
        '0' to Key("Digit0", false), ')' to Key("Digit0", true),
        '-' to Key("Minus", false), '_' to Key("Minus", true),
        '=' to Key("Equal", false), '+' to Key("Equal", true),
        '[' to Key("BracketLeft", false), '{' to Key("BracketLeft", true),
        ']' to Key("BracketRight", false), '}' to Key("BracketRight", true),
        ';' to Key("Semicolon", false), ':' to Key("Semicolon", true),
        '\'' to Key("Quote", false), '@' to Key("Quote", true),
        // UK: the ANSI backslash position carries # and ~
        '#' to Key("Backslash", false), '~' to Key("Backslash", true),
        // ISO 102nd key: UK Windows maps it to \ (shift: |)
        '\\' to Key("IntlBackslash", false), '|' to Key("IntlBackslash", true),
        ',' to Key("Comma", false), '<' to Key("Comma", true),
        '.' to Key("Period", false), '>' to Key("Period", true),
        '/' to Key("Slash", false), '?' to Key("Slash", true),
        '`' to Key("Backquote", false), // UK shifted is ¬ — non-ASCII
    )

    private val usPunct: Map<Char, Key> = mapOf(
        ' ' to Key("Space", false), '\t' to Key("Tab", false), '\n' to Key("Enter", false),
        '1' to Key("Digit1", false), '!' to Key("Digit1", true),
        '2' to Key("Digit2", false), '@' to Key("Digit2", true),
        '3' to Key("Digit3", false), '#' to Key("Digit3", true),
        '4' to Key("Digit4", false), '$' to Key("Digit4", true),
        '5' to Key("Digit5", false), '%' to Key("Digit5", true),
        '6' to Key("Digit6", false), '^' to Key("Digit6", true),
        '7' to Key("Digit7", false), '&' to Key("Digit7", true),
        '8' to Key("Digit8", false), '*' to Key("Digit8", true),
        '9' to Key("Digit9", false), '(' to Key("Digit9", true),
        '0' to Key("Digit0", false), ')' to Key("Digit0", true),
        '-' to Key("Minus", false), '_' to Key("Minus", true),
        '=' to Key("Equal", false), '+' to Key("Equal", true),
        '[' to Key("BracketLeft", false), '{' to Key("BracketLeft", true),
        ']' to Key("BracketRight", false), '}' to Key("BracketRight", true),
        ';' to Key("Semicolon", false), ':' to Key("Semicolon", true),
        '\'' to Key("Quote", false), '"' to Key("Quote", true),
        '\\' to Key("Backslash", false), '|' to Key("Backslash", true),
        ',' to Key("Comma", false), '<' to Key("Comma", true),
        '.' to Key("Period", false), '>' to Key("Period", true),
        '/' to Key("Slash", false), '?' to Key("Slash", true),
        '`' to Key("Backquote", false), '~' to Key("Backquote", true),
    )

    val UK: Map<Char, Key> = letters + ukPunct
    val US: Map<Char, Key> = letters + usPunct

    fun tableFor(targetLayout: String?): Map<Char, Key> =
        if (targetLayout.equals("us", ignoreCase = true)) US else UK

    /**
     * Key sequence for one character: Shift down, tap, Shift up — or just
     * the tap when no Shift is needed. Returns null when the char is not
     * covered by the table (caller falls back to `hid/print`).
     */
    fun keySequenceFor(ch: Char, targetLayout: String?): List<Pair<String, Boolean>>? {
        val key = tableFor(targetLayout)[ch] ?: return null
        return buildList {
            if (key.shift) add("ShiftLeft" to true)
            add(key.code to false)
            if (key.shift) add("ShiftLeft" to false)
        }
    }
}