package sh.haven.feature.terminal

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.connectbot.terminal.InlineImageProtocolType
import org.connectbot.terminal.InlineImageRequest
import org.connectbot.terminal.InlineImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.preferences.UserPreferencesRepository

/**
 * #583 — the consent bridge between the emulator's ASK policy and the dialog.
 * Off/On/Ask shape mapping, prompt park + answer, the per-tab always-allow
 * memory, the stale-prompt overwrite, and teardown denial.
 */
class InlineImageConsentRegistryTest {

    private fun request() = InlineImageRequest(
        protocol = InlineImageProtocolType.KITTY,
        action = "put",
        name = "pic.png",
        pixelWidth = 320,
        pixelHeight = 200,
    )

    @Test
    fun policyShapeFollowsPreference() {
        val registry = InlineImageConsentRegistry()
        val session = registry.state("s")
        assertTrue(inlineImagesPolicy(UserPreferencesRepository.TerminalInlineImages.OFF, session) is InlineImages.Off)
        assertTrue(inlineImagesPolicy(UserPreferencesRepository.TerminalInlineImages.ALWAYS, session) is InlineImages.On)
        assertTrue(inlineImagesPolicy(UserPreferencesRepository.TerminalInlineImages.ASK, session) is InlineImages.Ask)
    }

    @Test
    fun askParksPromptAndResolvesOnAnswer() = runTest {
        val registry = InlineImageConsentRegistry()
        val session = registry.state("tab1")
        val ask = inlineImagesPolicy(UserPreferencesRepository.TerminalInlineImages.ASK, session) as InlineImages.Ask

        var allowed: Boolean? = null
        val job = launch { allowed = ask.confirm(request()) }
        advanceUntilIdle()

        val prompt = session.prompt.value
        assertNotNull("the dialog must see a pending prompt", prompt)
        assertEquals("pic.png", prompt?.request?.name)

        registry.answer("tab1", true, false)
        job.join()
        assertEquals(true, allowed)
        assertNull("answered prompt must clear", session.prompt.value)
    }

    @Test
    fun alwaysInTabSkipsLaterPrompts() = runTest {
        val registry = InlineImageConsentRegistry()
        val session = registry.state("tab1")
        val ask = inlineImagesPolicy(UserPreferencesRepository.TerminalInlineImages.ASK, session) as InlineImages.Ask

        var first = false
        val job1 = launch { first = ask.confirm(request()) }
        advanceUntilIdle()
        registry.answer("tab1", true, true)
        job1.join()
        assertTrue(first)

        // No prompt for the next image in the same tab; straight Allow.
        assertNull(session.prompt.value)
        val second = ask.confirm(request())
        assertEquals(true, second)
    }

    @Test
    fun newerRequestDeniesTheParkedPrompt() = runTest {
        val registry = InlineImageConsentRegistry()
        val session = registry.state("tab1")
        val ask = inlineImagesPolicy(UserPreferencesRepository.TerminalInlineImages.ASK, session) as InlineImages.Ask

        var first: Boolean? = null
        val job1 = launch { first = ask.confirm(request()) }
        advanceUntilIdle()
        assertNotNull(session.prompt.value)

        var second: Boolean? = null
        val job2 = launch { second = ask.confirm(request()) }
        advanceUntilIdle()

        // The second request took the single prompt slot; the first parked
        // confirm must be denied rather than left hanging.
        job1.join()
        assertEquals(false, first)
        assertNotNull("the newer request now holds the slot", session.prompt.value)

        registry.answer("tab1", true, false)
        job2.join()
        assertEquals(true, second)
    }

    @Test
    fun dropDeniesPendingPrompt() = runTest {
        val registry = InlineImageConsentRegistry()
        val session = registry.state("tab1")
        val ask = inlineImagesPolicy(UserPreferencesRepository.TerminalInlineImages.ASK, session) as InlineImages.Ask

        var first: Boolean? = null
        val job = launch { first = ask.confirm(request()) }
        advanceUntilIdle()
        assertNotNull(session.prompt.value)

        registry.drop("tab1")
        job.join()
        assertFalse(first!!)
        assertNull(session.prompt.value)
    }
}