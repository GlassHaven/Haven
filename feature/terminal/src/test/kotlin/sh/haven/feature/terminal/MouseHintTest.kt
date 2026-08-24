package sh.haven.feature.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three rules behind the "an app wants the mouse" hint (#586).
 *
 * It exists because Haven used to do nothing and say nothing in this state,
 * which is indistinguishable from broken touch input — that silence cost the
 * #580 reporter a minimal reproducer, adb captures and two days. The opposite
 * failure matters too: a hint that reappears on every tab switch would be more
 * annoying than the bug it replaces.
 */
class MouseHintTest {

    @Test
    fun `shown when an app asks and the preference is off`() {
        assertTrue(
            shouldShowMouseHint(
                appRequestedMouse = true,
                preferenceEnabled = false,
                alreadyShown = false,
            ),
        )
    }

    @Test
    fun `never shown when no app has asked`() {
        // The whole point is not nagging people who are just using a shell.
        assertFalse(
            shouldShowMouseHint(
                appRequestedMouse = false,
                preferenceEnabled = false,
                alreadyShown = false,
            ),
        )
    }

    @Test
    fun `never shown when the preference is already on`() {
        assertFalse(
            shouldShowMouseHint(
                appRequestedMouse = true,
                preferenceEnabled = true,
                alreadyShown = false,
            ),
        )
    }

    @Test
    fun `shown at most once per session`() {
        assertFalse(
            "a second tab switch must not repeat it",
            shouldShowMouseHint(
                appRequestedMouse = true,
                preferenceEnabled = false,
                alreadyShown = true,
            ),
        )
    }
}
