package sh.haven.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.haven.core.terminal.HavenKeyboardMode
import sh.haven.core.terminal.defaultKeyboardModeForDevice

/** #684: Huawei's IME replaces the keyboard with a locked system keypad on
 * password-variation fields, so the Secure default has to drop that bit on
 * those devices while every other Secure flag survives. The guard is on the
 * manufacturer alone so unrelated devices keep the stock Secure recipe. */
class DefaultKeyboardModeForDeviceTest {

    @Test
    fun `huawei defaults adapt to Secure minus the password variation`() {
        for (mfr in listOf("HUAWEI", "Huawei", "huawei")) {
            val mode = defaultKeyboardModeForDevice(mfr)
            val flags = (mode as HavenKeyboardMode.Custom).flags
            assertEquals(false, flags.visiblePassword)
            assertEquals(true, flags.noSuggestions)
            assertEquals(false, flags.autoCorrect)
            assertEquals(false, flags.fullEditor)
            assertEquals(true, flags.noExtractUi)
            assertEquals(true, flags.noPersonalizedLearning)
        }
    }

    @Test
    fun `manufacturers without a report keep Secure (blank included)`() {
        assertEquals(HavenKeyboardMode.Secure, defaultKeyboardModeForDevice("samsung"))
        assertEquals(HavenKeyboardMode.Secure, defaultKeyboardModeForDevice("HONOR"))
        assertEquals(HavenKeyboardMode.Secure, defaultKeyboardModeForDevice("Google"))
        assertEquals(HavenKeyboardMode.Secure, defaultKeyboardModeForDevice(""))
    }
}