package sh.haven.feature.terminal

import androidx.compose.ui.text.AnnotatedString
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.SelectionRange
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the smart-copy pipeline in [SelectionToolbar].
 *
 * Covers:
 * - [smartCopy] extraction across the three snapshot shapes it has to handle
 *   (single-row, multi-row hard-break, multi-row soft-wrap, TUI-bordered).
 * - [SmartTerminalClipboard.setText] fallback behaviour when smartCopy can't
 *   contribute non-blank content — this is the path that caused the v5.19.x
 *   clipboard-overwrite regression.
 */
class SmartCopyTest {

    private fun emulator(lines: List<String>, columns: Int? = null): TerminalEmulator {
        val width = columns ?: lines.maxOfOrNull { it.length } ?: 80
        return mockk(relaxed = true) {
            every { dimensions } returns TerminalDimensions(rows = lines.size, columns = width)
            every { getSnapshotLineTexts() } returns lines
        }
    }

    private fun controller(range: SelectionRange?, selectedText: String = ""): SelectionController =
        mockk(relaxed = true) {
            every { getSelectionRange() } returns range
            every { getSelectedText() } returns selectedText
        }

    // ---------- smartCopy ----------

    @Test
    fun `single-row selection returns the controller's text`() {
        val out = smartCopy(
            controller(
                SelectionRange(startRow = 0, startCol = 6, endRow = 0, endCol = 10),
                selectedText = "world",
            ),
            emulator(listOf("hello world")),
        )
        assertEquals("world", out)
    }

    @Test
    fun `multi-row hard-break selection keeps the controller's newlines`() {
        // The controller (backed by SelectionManager.getSelectedText) reports
        // hard-broken rows by inserting "\n" between them — these rows aren't
        // softWrapped, so smartCopy passes that through verbatim.
        val out = smartCopy(
            controller(
                SelectionRange(startRow = 0, startCol = 0, endRow = 1, endCol = 2),
                selectedText = "foo\nbar",
            ),
            emulator(listOf("foo", "bar"), columns = 40),
        )
        assertEquals("foo\nbar", out)
    }

    @Test
    fun `soft-wrapped selection joins without newlines via the controller`() {
        // The two rows happen to fill the 10-column viewport exactly, but
        // the controller already knows (via libvterm's softWrapped flag)
        // that they're one logical line — smartCopy returns the joined form.
        val out = smartCopy(
            controller(
                SelectionRange(startRow = 0, startCol = 0, endRow = 1, endCol = 9),
                selectedText = "abcdefghijklmnopqrst",
            ),
            emulator(listOf("abcdefghij", "klmnopqrst"), columns = 10),
        )
        assertEquals("abcdefghijklmnopqrst", out)
    }

    @Test
    fun `TUI border columns restrict copied text to the selected panel`() {
        // Three rows, each with a Unicode box-drawing border at column 6, and
        // the selection anchored on the left panel ("left  "). The right panel
        // ("right", "data", "more") must NOT appear in the copied text.
        val out = smartCopy(
            controller(SelectionRange(startRow = 0, startCol = 0, endRow = 2, endCol = 5)),
            emulator(
                listOf(
                    "left  │ right",
                    "line2 │ data ",
                    "line3 │ more ",
                ),
            ),
        )
        assertEquals("left\nline2\nline3", out)
    }

    @Test
    fun `ascii pipe columns do not trigger panel stripping (#581)`() {
        // Reporter's shape: a block of content lines whose column of '|' is
        // consistent (ls output, paths, tables). The old border check accepted
        // ASCII '|', so copying this pasted fragments cut at the pipe column.
        // The whole selection must now come through verbatim.
        val lines = listOf(
            "1705 | /usr/bin/gcc       | 2024-01-02 | 1.2M",
            "1706 | /usr/bin/g++       | 2024-01-02 | 1.2M",
            "1707 | /usr/bin/ld        | 2024-01-02 | 900K",
        )
        val out = smartCopy(
            controller(
                SelectionRange(startRow = 0, startCol = 0, endRow = 2, endCol = 44),
                selectedText = lines.joinToString("\n"),
            ),
            emulator(lines),
        )
        assertEquals(lines.joinToString("\n"), out)
    }

    @Test
    fun `selection starting past end of short line yields no contribution`() {
        // This is the shape that silently wrote `""` to the clipboard in
        // v5.19.x — selection columns point past where the line's real
        // content ends, so the controller has nothing selected. smartCopy
        // now returns null in that case (rather than ""), which
        // SmartTerminalClipboard.setText reads as "no contribution" and
        // keeps the caller's AnnotatedString instead of clobbering the
        // clipboard with empty text.
        val out = smartCopy(
            controller(
                SelectionRange(startRow = 0, startCol = 50, endRow = 0, endCol = 55),
                selectedText = "",
            ),
            emulator(listOf("short"), columns = 80),
        )
        assertNull(out)
    }

    @Test
    fun `null selection returns null`() {
        val out = smartCopy(
            controller(range = null),
            emulator(listOf("whatever")),
        )
        assertNull(out)
    }

    @Test
    fun `controller text is returned verbatim for full-width hard-broken rows`() {
        // Pair with the soft-wrap test above: same row layout (two rows
        // exactly filling the 10-col viewport), but the controller reports
        // them as hard-broken (libvterm's softWrapped=false). smartCopy
        // must trust the controller and preserve the newline.
        val out = smartCopy(
            controller(
                range = SelectionRange(startRow = 0, startCol = 0, endRow = 1, endCol = 9),
                selectedText = "abcdefghij\nklmnopqrst",
            ),
            emulator(listOf("abcdefghij", "klmnopqrst"), columns = 10),
        )
        assertEquals("abcdefghij\nklmnopqrst", out)
    }

    @Test
    fun `border-strip path bypasses controller getSelectedText`() {
        // When TUI borders are detected, the panel-extract path takes over
        // regardless of what the controller would have returned — the
        // border strip operates on full row text, not the logical
        // selection. Pass a deliberately wrong getSelectedText() to prove
        // it isn't consulted on this branch.
        val out = smartCopy(
            controller(
                range = SelectionRange(startRow = 0, startCol = 0, endRow = 2, endCol = 5),
                selectedText = "WRONG_VALUE_FROM_CONTROLLER",
            ),
            emulator(
                listOf(
                    "left  │ right",
                    "line2 │ data ",
                    "line3 │ more ",
                ),
            ),
        )
        assertEquals("left\nline2\nline3", out)
    }

    @Test
    fun `smartCopy reconstructs a hanging-indent wrapped URL without newline or indent`() {
        // Selection spans the URL row and the indented tail row. The rows are
        // hard-broken (not softWrapped), so getSelectedText would return them
        // with a "\n" + indent. smartCopy rejoins into the clean URL instead.
        val out = smartCopy(
            controller(
                SelectionRange(startRow = 0, startCol = 0, endRow = 1, endCol = 5),
                selectedText = "https://github.com/GlassOnTin/iSpindlePlotter.gi\n     t",
            ),
            emulator(
                listOf(
                    "https://github.com/GlassOnTin/iSpindlePlotter.gi",
                    "     t",
                ),
                columns = 61,
            ),
        )
        assertEquals("https://github.com/GlassOnTin/iSpindlePlotter.git", out)
    }

    @Test
    fun `smartCopy keeps multi-row prose verbatim`() {
        // A real multi-row prose selection does not rejoin into a single URL,
        // so smartCopy preserves the controller's newline-bearing text.
        val out = smartCopy(
            controller(
                SelectionRange(startRow = 0, startCol = 0, endRow = 1, endCol = 13),
                selectedText = "see https://example.com\nand more notes",
            ),
            emulator(
                listOf(
                    "see https://example.com",
                    "  and more notes",
                ),
                columns = 40,
            ),
        )
        assertEquals("see https://example.com\nand more notes", out)
    }

    // ---------- SmartTerminalClipboard.setText ----------

    private fun clipboard(
        controllerRange: SelectionRange?,
        lines: List<String>,
        columns: Int = 80,
        selectedText: String = "",
    ): Pair<SmartTerminalClipboard, androidx.compose.ui.platform.ClipboardManager> {
        val delegate = mockk<androidx.compose.ui.platform.ClipboardManager>(relaxed = true)
        val smart = SmartTerminalClipboard(
            delegate = delegate,
            getEmulator = { emulator(lines, columns = columns) },
            getController = { controller(controllerRange, selectedText = selectedText) },
        )
        return smart to delegate
    }

    @Test
    fun `setText uses smartCopy result when it contributes content`() {
        val (smart, delegate) = clipboard(
            controllerRange = SelectionRange(0, 6, 0, 10),
            lines = listOf("hello world"),
            selectedText = "world",
        )
        // Caller passes an unprocessed AnnotatedString; smartCopy returns
        // the controller's selected text ("world") and SmartTerminalClipboard
        // substitutes that for the caller's text.
        smart.setText(AnnotatedString("hello world"))

        val captured = slot<AnnotatedString>()
        verify { delegate.setText(capture(captured)) }
        assertEquals("world", captured.captured.text)
    }

    @Test
    fun `setText falls back to caller text when smartCopy has no contribution`() {
        // Selection is past the end of a short line → controller returns "",
        // smartCopy returns null. Without the fallback, the clipboard would
        // silently be cleared — the v5.19.x regression. With the fallback,
        // the caller's text (what SelectionManager extracted) is used.
        val (smart, delegate) = clipboard(
            controllerRange = SelectionRange(0, 50, 0, 55),
            lines = listOf("short"),
            selectedText = "",
        )
        smart.setText(AnnotatedString("short-from-selection"))

        val captured = slot<AnnotatedString>()
        verify { delegate.setText(capture(captured)) }
        assertEquals("short-from-selection", captured.captured.text)
    }

    @Test
    fun `setText falls back when controller is null`() {
        val delegate = mockk<androidx.compose.ui.platform.ClipboardManager>(relaxed = true)
        val smart = SmartTerminalClipboard(
            delegate = delegate,
            getEmulator = { emulator(listOf("text")) },
            getController = { null },
        )
        smart.setText(AnnotatedString("raw text"))

        val captured = slot<AnnotatedString>()
        verify { delegate.setText(capture(captured)) }
        assertEquals("raw text", captured.captured.text)
    }

    // ---------- expandAcrossUrlWrap ----------

    @Test
    fun `URL wrap walks forward across a scheme-prefixed continuation`() {
        // Tapped word "https://github.com/GlassOnTin/Haven/iss" sits at
        // row 0 cols 0..38 (the row's full trimmed width); the URL
        // continues as "ues/89" on row 1.
        val span = expandAcrossUrlWrap(
            lines = listOf(
                "https://github.com/GlassOnTin/Haven/iss",
                "ues/89",
                "more prose",
            ),
            row = 0,
            wordStartCol = 0,
            wordEndCol = 38,
        )
        assertNotNull(span)
        assertEquals(0, span!!.startRow)
        assertEquals(0, span.startCol)
        assertEquals(1, span.endRow)
        assertEquals(5, span.endCol)  // 'ues/89' ends at col 5
    }

    @Test
    fun `URL wrap walks backward from a continuation row into the scheme row`() {
        // Long-press on "ues/89" (row 1) should walk backward to pick up
        // the URL start on row 0.
        val span = expandAcrossUrlWrap(
            lines = listOf(
                "https://github.com/GlassOnTin/Haven/iss",
                "ues/89",
            ),
            row = 1,
            wordStartCol = 0,
            wordEndCol = 5,
        )
        assertNotNull(span)
        assertEquals(0, span!!.startRow)
        assertEquals(0, span.startCol)
        assertEquals(1, span.endRow)
        assertEquals(5, span.endCol)
    }

    @Test
    fun `URL wrap does not consume adjacent prose without a URL scheme`() {
        // Two long contiguous tokens on adjacent rows but no scheme in
        // the joined text — don't expand.
        val span = expandAcrossUrlWrap(
            lines = listOf(
                "abcdefghijklmnopqrstuvwxyzabcdefghijklmn",
                "opqrstuvwxyz",
            ),
            row = 0,
            wordStartCol = 0,
            wordEndCol = 39,
        )
        assertNull(span)
    }

    @Test
    fun `URL wrap returns null when word is mid-row`() {
        // Word in the middle of a row has whitespace on both sides — not
        // wrapped, nothing to do.
        val span = expandAcrossUrlWrap(
            lines = listOf(
                "  https://example.com  ",
                "  next line text  ",
            ),
            row = 0,
            wordStartCol = 2,
            wordEndCol = 20,
        )
        assertNull(span)
    }

    @Test
    fun `URL wrap stops on multi-word indented prose`() {
        // The next row is indented prose with multiple words — a line full of
        // text, not a hanging-indent wrap tail. Must not join (guards the
        // common false-positive).
        val span = expandAcrossUrlWrap(
            lines = listOf(
                "https://example.com/some/pa",
                "  indented continuation",
            ),
            row = 0,
            wordStartCol = 0,
            wordEndCol = 26,
            columns = 40,
        )
        assertNull(span)
    }

    @Test
    fun `URL wrap joins a hanging-indent single-char tail`() {
        // The exact iSpindle case: Claude Code wrapped the final 't' of
        // '.git' onto the next line behind a 5-space hanging indent. The
        // continuation is a single short run on an otherwise-blank line.
        val span = expandAcrossUrlWrap(
            lines = listOf(
                "https://github.com/GlassOnTin/iSpindlePlotter.gi",
                "     t",
            ),
            row = 0,
            wordStartCol = 0,
            wordEndCol = 47, // '.gi' ends at the row's trimmed edge
            columns = 61,
        )
        assertNotNull(span)
        assertEquals(0, span!!.startRow)
        assertEquals(0, span.startCol)
        assertEquals(1, span.endRow)
        assertEquals(5, span.endCol) // the 't' at col 5
    }

    @Test
    fun `URL wrap joins a hanging-indent multi-char tail`() {
        // Not just single characters: a short indented run like '/issues'
        // left alone on its line is a wrap tail too.
        val span = expandAcrossUrlWrap(
            lines = listOf(
                "https://github.com/GlassOnTin/Haven",
                "   /issues",
            ),
            row = 0,
            wordStartCol = 0,
            wordEndCol = 34,
            columns = 40,
        )
        assertNotNull(span)
        assertEquals(1, span!!.endRow)
        assertEquals(9, span.endCol) // '/issues' ends at col 9
    }

    @Test
    fun `URL wrap does not join an indented run that fills the line`() {
        // A long indented run is not the mostly-blank shape of a wrap tail —
        // more likely a reflowed paragraph. Don't join.
        val span = expandAcrossUrlWrap(
            lines = listOf(
                "https://example.com/aaaaaaaaaaaaaaaaaaaa",
                "   bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            ),
            row = 0,
            wordStartCol = 0,
            wordEndCol = 39,
            columns = 40,
        )
        assertNull(span)
    }

    // ---------- SmartTerminalClipboard.setText ----------

    @Test
    fun `setText falls back when smartCopy returns only whitespace`() {
        // The controller is allowed to return whitespace-only text (e.g. a
        // selection covering trailing padding on a row that
        // SelectionManager trims to spaces). isNullOrBlank() treats that as
        // no contribution, so we keep the caller's text.
        val (smart, delegate) = clipboard(
            controllerRange = SelectionRange(0, 5, 0, 8),
            lines = listOf("text    "),
            selectedText = "   ",
        )
        smart.setText(AnnotatedString("meaningful"))

        val captured = slot<AnnotatedString>()
        verify { delegate.setText(capture(captured)) }
        assertTrue(
            "expected caller text or non-blank smartCopy output, got \"${captured.captured.text}\"",
            captured.captured.text.isNotBlank(),
        )
    }
}
