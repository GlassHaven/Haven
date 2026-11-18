package sh.haven.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TabTitleTest {
    @Test
    fun `pref off - program title wins over label`() {
        assertEquals("vim", resolveTabTitle("vim", "web", "web", false))
    }

    @Test
    fun `pref off - no program title falls back to label`() {
        assertEquals("web", resolveTabTitle(null, "web", "web", false))
    }

    @Test
    fun `pref off - blank program title falls back to label`() {
        assertEquals("web", resolveTabTitle("", "web", "web", false))
    }

    @Test
    fun `pref on - multiplexer tab keeps session name even with a live program title`() {
        assertEquals("web", resolveTabTitle("/home/user/src", "web", "web", true))
        assertEquals("web", resolveTabTitle("vim", "web", "web", true))
    }

    @Test
    fun `pref on - blank multiplexer name falls back to program title`() {
        assertEquals("vim", resolveTabTitle("vim", "srv", "", true))
        assertEquals("vim", resolveTabTitle("vim", "srv", "  ", true))
    }

    @Test
    fun `pref on - null multiplexer name falls back to program title then label`() {
        // Plain SSH / local shells: program titles still win.
        assertEquals("vim", resolveTabTitle("vim", "srv", null, true))
        assertEquals("srv", resolveTabTitle(null, "srv", null, true))
    }
}
class TabSubtitleTest {
    @Test
    fun `session-named tab shows its host`() {
        assertEquals("prod-1", resolveTabSubtitle("default", "prod-1"))
    }

    @Test
    fun `title that already is the host shows no subtitle`() {
        assertEquals(null, resolveTabSubtitle("prod-1", "prod-1"))
        assertEquals(null, resolveTabSubtitle("prod-1", " prod-1 "))
    }

    @Test
    fun `tab without a host shows no subtitle`() {
        assertEquals(null, resolveTabSubtitle("bash", null))
        assertEquals(null, resolveTabSubtitle("bash", "  "))
    }
}
