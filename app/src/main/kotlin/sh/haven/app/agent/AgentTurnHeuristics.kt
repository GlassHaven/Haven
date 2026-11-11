package sh.haven.app.agent

import org.connectbot.terminal.AgentSnapshot

/**
 * Pure turn-boundary heuristics behind the await_turn / read_last_turn /
 * isAgentRepl MCP tools (#226). Two detection paths:
 *
 *  - OSC 133 (shells with shell integration): the terminal is idle-at-prompt
 *    iff the cursor row carries a PROMPT or COMMAND_INPUT segment of the
 *    NEWEST promptId. OSC 133;B plants the input marker at prompt-draw time,
 *    so "newest input has no COMMAND_FINISHED" is true both at rest and
 *    mid-command — the cursor-on-the-newest-prompt-row test is what actually
 *    distinguishes them (mid-command the cursor has moved below the prompt).
 *
 *  - Screen heuristics (Claude Code and other TUIs that don't emit shell-style
 *    OSC 133): busy markers (spinner / "esc to interrupt"), a prompt-looking
 *    line near the bottom, and screen stability across polls.
 *
 * All functions are pure over snapshot data so they unit-test without a
 * device (prototype spec: scripts/haven_convo_prototype.py, round-tripped live).
 */

/** A Claude-Code-style REPL is mid-turn (still working). */
internal val AGENT_BUSY = Regex(
    "esc to interrupt" +
        "|[✶✻✢✳✽✺·*+]\\s+\\S+…" + // spinner frame + gerund, e.g. "✳ Simmering…" (ASCII frames * and + seen live)
        "|[·↑↓]\\s*\\d+(\\.\\d+)?k?\\s*tokens",
)

/** Markers that identify an agent REPL (Claude Code TUI), busy or idle. */
internal val AGENT_REPL_MARKERS = Regex(
    "esc to interrupt|\\? for shortcuts|bypass permissions|plan mode|accept edits",
)

/**
 * True when [trimmed] looks like an input prompt waiting for input.
 * '%' (zsh) only counts after whitespace so "downloading 42%" doesn't read
 * as a prompt; '$'/'#'/'>' stay loose ("user@host:~$", "bash-5.1$").
 */
internal fun looksLikePromptLine(trimmed: String): Boolean =
    trimmed.startsWith("❯") || trimmed.startsWith("➜") ||
        trimmed.startsWith("│ ❯") || trimmed.startsWith("│❯") ||
        Regex("([\\$#>]|\\s%)\\s?$").containsMatchIn(trimmed)

/**
 * The emulator returns never-painted cells as NUL, which Kotlin's
 * isBlank()/trim() do NOT treat as whitespace — every blank-drop and
 * end-anchored regex here breaks on raw screen rows without this.
 */
private fun scrub(lines: List<String>): List<String> =
    lines.map { it.replace('\u0000', ' ') }

/**
 * True when any of the last [window] CONTENT lines looks like a waiting
 * prompt. Trailing blank rows are dropped first — a fresh shell's prompt
 * sits at the TOP of an otherwise-empty screen (device-verified: a plain
 * busybox "# " on row 0 of 24, NUL-padded).
 */
internal fun promptPresent(lines: List<String>, window: Int = 8): Boolean =
    scrub(lines).dropLastWhile { it.isBlank() }.takeLast(window).any { looksLikePromptLine(it.trim()) }

internal fun looksBusy(lines: List<String>): Boolean =
    lines.any { AGENT_BUSY.containsMatchIn(it) }

/**
 * Heuristic: does this screen belong to an agent REPL (Claude Code style)?
 * Scans the bottom [window] lines for TUI chrome. Honest limits: a shell
 * whose scrollback happens to show those strings false-positives; a REPL
 * with all chrome hidden false-negatives.
 */
internal fun looksLikeAgentRepl(lines: List<String>, window: Int = 15): Boolean =
    scrub(lines).dropLastWhile { it.isBlank() }.takeLast(window).any {
        AGENT_REPL_MARKERS.containsMatchIn(it) || it.trim().let { t ->
            t.startsWith("│ ❯") || t.startsWith("│❯")
        }
    }

/**
 * One await_turn idle poll over a snapshot. Agent-REPL chrome forces the
 * heuristic path even when OSC 133 segments are visible: a REPL launched
 * from an integrated shell leaves the outer shell's stale PROMPT rows
 * on-screen, and the cursor never returns to them, so the osc133 test
 * reads busy forever until they scroll off (device-reproduced, #226).
 * Returns idle to "osc133"|"heuristic".
 */
internal fun idlePoll(snap: AgentSnapshot, stable: Boolean): Pair<Boolean, String> {
    val texts = snap.lines.map { it.text }
    if (!looksLikeAgentRepl(texts)) {
        osc133Idle(snap)?.let { return it to "osc133" }
    }
    return (!looksBusy(texts) && promptPresent(texts) && stable) to "heuristic"
}

/**
 * OSC 133 idle test over the VISIBLE screen. Returns null when no semantic
 * segments are visible (no shell integration, or a TUI owns the screen) —
 * caller falls back to screen heuristics.
 */
internal fun osc133Idle(snap: AgentSnapshot): Boolean? {
    var maxPromptId = -1
    for (line in snap.lines) {
        for (seg in line.semanticSegments) {
            if (seg.promptId > maxPromptId) maxPromptId = seg.promptId
        }
    }
    if (maxPromptId < 0) return null
    val cursorLine = snap.lines.getOrNull(snap.cursorRow) ?: return false
    return cursorLine.semanticSegments.any {
        it.promptId == maxPromptId && (it.type == "PROMPT" || it.type == "COMMAND_INPUT")
    }
}

private val BOX_OR_RULE = Regex("^[─╌═━╭╮╰╯│┌┐└┘\\s]+$")

/** Claude Code's labeled divider, e.g. "──────── my-task-name ──". */
private val LABELED_RULE = Regex("^─+\\s.+\\s─+$")

/** A tmux status line, e.g. "[haven] 0:zsh* …" — present on every tmux-carried REPL. */
private val TMUX_STATUS = Regex("^\\[[^\\]]+\\]\\s")

/**
 * Claude-Code-aware scrape of the assistant's latest reply: drop the input
 * box / status chrome from the bottom, then return the last contiguous
 * ●/⏺-bulleted block. Null when nothing scrapeable.
 */
internal fun scrapeLastAgentBlock(rawLines: List<String>): String? {
    val lines = scrub(rawLines).map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
    if (lines.isEmpty()) return null
    // Walk the trailing chrome REGION off the bottom (input box borders,
    // prompt line, status bar, blanks) — it's several lines, not one.
    var cut = lines.size
    while (cut > 0) {
        val l = lines[cut - 1]
        val t = l.trim()
        val chrome = t.isEmpty() || looksLikePromptLine(t) ||
            BOX_OR_RULE.matches(l) || AGENT_REPL_MARKERS.containsMatchIn(l) ||
            LABELED_RULE.matches(t) || TMUX_STATUS.containsMatchIn(t)
        if (!chrome) break
        cut--
    }
    val body = lines.subList(0, cut).dropLastWhile { it.isBlank() }
    if (body.isEmpty()) return null
    // Keep from the last assistant bullet onward; else the whole body.
    val start = body.indexOfLast { it.trimStart().startsWith("●") || it.trimStart().startsWith("⏺") }
    val block = if (start >= 0) body.subList(start, body.size) else body
    return block.joinToString("\n").trim().ifEmpty { null }
}
