package sh.haven.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Copy as one line" joins a TUI's self-wrapped rows. The cases mirror what a
 * plain copy of Claude Code's input box produces: one newline per visual row,
 * because the app — not the terminal — did the wrapping.
 */
class JoinWrappedLinesTest {

    @Test
    fun `Chinese rows wrapped by the TUI rejoin with no separator`() {
        val copied = "/grill-me 我想做一个面向海外用户的中文的app，帮我调研下哪些\n" +
            "国家，哪些语种还有用户，类似于学英语\n" +
            "生成相关学习内容"

        assertEquals(
            "/grill-me 我想做一个面向海外用户的中文的app，帮我调研下哪些国家，哪些语种还有用户，类似于学英语生成相关学习内容",
            joinWrappedLines(copied),
        )
    }

    @Test
    fun `English rows get back the space the wrap swallowed`() {
        assertEquals(
            "please summarise the build failure and propose a fix",
            joinWrappedLines("please summarise the build\nfailure and propose a fix"),
        )
    }

    @Test
    fun `a seam between Latin and Chinese takes no space`() {
        assertEquals("用 gradle构建", joinWrappedLines("用 gradle\n构建"))
        assertEquals("文件app", joinWrappedLines("文件\napp"))
    }

    @Test
    fun `fullwidth punctuation at the seam takes no space`() {
        assertEquals("你好，world", joinWrappedLines("你好，\nworld"))
    }

    @Test
    fun `Korean is spaced like Latin text`() {
        assertEquals("안녕하세요 세계", joinWrappedLines("안녕하세요\n세계"))
    }

    @Test
    fun `continuation indent and blank lines are dropped`() {
        assertEquals(
            "first line second line third",
            joinWrappedLines("  first line  \n    second line\n\n   third\n"),
        )
    }

    @Test
    fun `Windows line endings are handled`() {
        assertEquals("a b", joinWrappedLines("a\r\nb"))
    }

    @Test
    fun `a single line is only trimmed`() {
        assertEquals("ls -la", joinWrappedLines("  ls -la  "))
    }

    @Test
    fun `blank input yields an empty string`() {
        assertEquals("", joinWrappedLines(" \n \n"))
    }
}
