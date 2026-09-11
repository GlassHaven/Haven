package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Test

class TarEntryNamesTest {

    @Test
    fun `trailing slash-dot collapses to the bare directory path`() {
        // #546: the exact shape that broke the importer — a regular-file
        // entry named like a directory written as `dir/.`.
        assertEquals("gopd" to true, TarEntryNames.normalize("gopd/."))
        assertEquals("x/y/gopd" to true, TarEntryNames.normalize("x/y/gopd/."))
        assertEquals("./gopd" to true, TarEntryNames.normalize("./gopd/."))
    }

    @Test
    fun `trailing slash collapses the same way`() {
        assertEquals("gopd" to true, TarEntryNames.normalize("gopd/"))
        assertEquals("usr/share/doc" to true, TarEntryNames.normalize("usr/share/doc/"))
    }

    @Test
    fun `interior dot-slash is not touched`() {
        // Only the tail matters; `a/./` still collapses to `a`, and an
        // interior `./` in a file entry is left as-is.
        assertEquals("a" to true, TarEntryNames.normalize("a/./"))
        assertEquals("a/./b" to false, TarEntryNames.normalize("a/./b"))
    }

    @Test
    fun `repeated dir-dot markers collapse in one pass`() {
        assertEquals("a" to true, TarEntryNames.normalize("a/./."))
        assertEquals("a" to true, TarEntryNames.normalize("a/.//"))
    }

    @Test
    fun `plain names are returned unchanged`() {
        assertEquals("file.txt" to false, TarEntryNames.normalize("file.txt"))
        assertEquals("dir/file.txt" to false, TarEntryNames.normalize("dir/file.txt"))
    }

    @Test
    fun `bare dot is left alone for the caller to skip`() {
        // GNU tar with `-C dir .` writes the root as `.`; it must not
        // collapse to "" (the caller distinguishes empty = skip).
        assertEquals("." to false, TarEntryNames.normalize("."))
        assertEquals("." to true, TarEntryNames.normalize("./"))
        assertEquals("" to false, TarEntryNames.normalize(""))
    }
}