package sh.haven.feature.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Streaming terminal-output scanner that detects when a remote shell is
 * parked at an interactive `sudo` or `doas` password prompt.
 *
 * Strips ANSI/CSI/OSC escape sequences on the fly and inspects the trailing
 * unfinished line (after the most recent `\n`). Any newline or explicit
 * [clear] (wired to user input and banner dismissal) clears [activePrompt]
 * so scrolling output never leaves a stale prompt banner on screen.
 */
class SudoPromptDetector {

    private enum class State {
        GROUND,
        ESC,
        CSI,
        OSC,
        OSC_ESC,
    }

    private var state = State.GROUND
    private val lineBuf = StringBuilder(MAX_LINE_CHARS)
    private var sawSudoContext = false

    private val _activePrompt = MutableStateFlow<String?>(null)
    val activePrompt: StateFlow<String?> = _activePrompt.asStateFlow()

    @Synchronized
    fun process(data: ByteArray, offset: Int, length: Int) {
        val end = offset + length
        for (i in offset until end) {
            val b = data[i].toInt() and 0xFF
            when (state) {
                State.GROUND -> {
                    when {
                        b == 0x1B -> state = State.ESC
                        b == '\n'.code || b == '\r'.code -> {
                            onLineFinished()
                        }
                        b == 0x08 || b == 0x7F -> {
                            if (lineBuf.isNotEmpty()) {
                                lineBuf.setLength(lineBuf.length - 1)
                            }
                        }
                        b >= 0x20 && b != 0x7F -> {
                            if (lineBuf.length < MAX_LINE_CHARS) {
                                lineBuf.append(b.toChar())
                            }
                        }
                    }
                }
                State.ESC -> {
                    state = when (b) {
                        '['.code -> State.CSI
                        ']'.code -> State.OSC
                        else -> State.GROUND
                    }
                }
                State.CSI -> {
                    if (b in 0x40..0x7E) {
                        if (isCursorRowJumpCsi(b)) {
                            onCursorJump()
                        }
                        state = State.GROUND
                    }
                }
                State.OSC -> {
                    when (b) {
                        0x07 -> state = State.GROUND
                        0x1B -> state = State.OSC_ESC
                    }
                }
                State.OSC_ESC -> {
                    state = State.GROUND
                }
            }
        }

        val candidate = lineBuf.toString().trim()
        if (candidate.isNotEmpty() && isSudoPromptLine(candidate, sawSudoContext)) {
            sawSudoContext = true
            _activePrompt.value = candidate
        }
    }

    @Synchronized
    fun clear() {
        lineBuf.setLength(0)
        sawSudoContext = false
        _activePrompt.value = null
    }

    private fun onLineFinished() {
        val completed = lineBuf.toString().trim()
        lineBuf.setLength(0)
        _activePrompt.value = null
        if (completed.isEmpty()) return
        if (completed.equals("Sorry, try again.", ignoreCase = true) ||
            isSudoPromptLine(completed, sawSudoContext)
        ) {
            sawSudoContext = true
        } else {
            sawSudoContext = false
        }
    }

    private fun onCursorJump() {
        val segment = lineBuf.toString().trim()
        lineBuf.setLength(0)
        if (segment.isEmpty()) return
        if (isSudoPromptLine(segment, sawSudoContext)) {
            sawSudoContext = true
            _activePrompt.value = segment
        } else if (segment.equals("Sorry, try again.", ignoreCase = true)) {
            sawSudoContext = true
        }
    }

    companion object {
        private const val MAX_LINE_CHARS = 256

        private fun isCursorRowJumpCsi(finalByte: Int): Boolean =
            finalByte == 'H'.code ||
                finalByte == 'f'.code ||
                finalByte == 'A'.code ||
                finalByte == 'B'.code ||
                finalByte == 'E'.code ||
                finalByte == 'F'.code ||
                finalByte == 'd'.code

        private val SUDO_BRACKET_PROMPT = Regex(
            pattern = """^\[(?:sudo|doas)(?::[^\]]*)?]\s+\S.*:$""",
            option = RegexOption.IGNORE_CASE,
        )
        private val DOAS_PAREN_PROMPT = Regex(
            pattern = """^doas\s*\([^)]+\)\s*password\s*:$""",
            option = RegexOption.IGNORE_CASE,
        )
        private val NAMED_SUDO_PROMPT = Regex(
            pattern = """^(?:sudo|doas)\s+password(?:\s+for\s+\S+)?\s*:$""",
            option = RegexOption.IGNORE_CASE,
        )
        private val BARE_PASSWORD_PROMPT = Regex(
            pattern = """^password(?:\s+for\s+\S+)?\s*:$""",
            option = RegexOption.IGNORE_CASE,
        )

        internal fun isSudoPromptLine(trimmed: String, inSudoRetryContext: Boolean): Boolean {
            if (trimmed.length > 120 || !trimmed.endsWith(':')) return false
            if (trimmed.contains(';') || trimmed.contains("COMMAND=")) return false
            if (SUDO_BRACKET_PROMPT.matches(trimmed)) return true
            if (DOAS_PAREN_PROMPT.matches(trimmed)) return true
            if (NAMED_SUDO_PROMPT.matches(trimmed)) return true
            if (inSudoRetryContext && BARE_PASSWORD_PROMPT.matches(trimmed)) return true
            return false
        }
    }
}
