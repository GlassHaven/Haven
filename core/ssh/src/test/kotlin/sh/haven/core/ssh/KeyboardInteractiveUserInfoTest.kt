package sh.haven.core.ssh

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class KeyboardInteractiveUserInfoTest {

    // UIKeyboardInteractive contract: this method is invoked on JSch's
    // internal IO thread. Our bridge uses runBlocking to bridge into the
    // suspending prompter. Simulate that by calling from a non-main thread.

    @Test
    fun promptReturnsPrompterResponsesAsStringArray() {
        val observed = mutableListOf<KeyboardInteractiveChallenge>()
        val ui = KeyboardInteractiveUserInfo(
            destination = "alice@example.com:22",
            prompter = { challenge ->
                observed += challenge
                listOf("hunter2", "987654")
            },
        )

        val result = callOnBackgroundThread {
            ui.promptKeyboardInteractive(
                "alice@example.com:22",
                "Google Authenticator",
                "Please authenticate.",
                arrayOf("Password: ", "Verification code: "),
                booleanArrayOf(false, false),
            )
        }

        assertNotNull("prompter responses must round-trip to JSch", result)
        assertArrayEquals(arrayOf("hunter2", "987654"), result)
        assertEquals(1, observed.size)
        with(observed.single()) {
            assertEquals("alice@example.com:22", destination)
            assertEquals("Google Authenticator", name)
            assertEquals("Please authenticate.", instruction)
            assertEquals(2, prompts.size)
            assertEquals("Password: ", prompts[0].text)
            assertEquals(false, prompts[0].echo)
            assertEquals("Verification code: ", prompts[1].text)
            assertEquals(false, prompts[1].echo)
        }
    }

    @Test
    fun prompterReturningNullCancelsAuth() {
        val ui = KeyboardInteractiveUserInfo(
            destination = "alice@example.com:22",
            prompter = { null },
        )

        val result = callOnBackgroundThread {
            ui.promptKeyboardInteractive(
                "alice@example.com:22",
                null,
                null,
                arrayOf("Password: "),
                booleanArrayOf(false),
            )
        }

        assertNull("null from prompter must translate to null for JSch (cancel)", result)
    }

    @Test
    fun fallbackPasswordSatisfiesSinglePasswordPromptWithoutCallingPrompter() {
        var prompterCalled = false
        val ui = KeyboardInteractiveUserInfo(
            destination = "alice@example.com:22",
            prompter = {
                prompterCalled = true
                listOf("from-prompter")
            },
            fallbackPassword = "saved-password".toCharArray(),
        )

        val result = callOnBackgroundThread {
            ui.promptKeyboardInteractive(
                "alice@example.com:22",
                null,
                null,
                arrayOf("Password: "),
                booleanArrayOf(false),
            )
        }

        assertArrayEquals(arrayOf("saved-password"), result)
        assertTrue(
            "saved password must answer a single 'Password:' KI round silently",
            !prompterCalled,
        )
    }

    @Test
    fun fallbackPasswordIsNotUsedForMultiPromptChallenges() {
        var prompterCalled = false
        val ui = KeyboardInteractiveUserInfo(
            destination = "alice@example.com:22",
            prompter = { challenge ->
                prompterCalled = true
                List(challenge.prompts.size) { i -> "answer-$i" }
            },
            fallbackPassword = "saved-password".toCharArray(),
        )

        val result = callOnBackgroundThread {
            ui.promptKeyboardInteractive(
                "alice@example.com:22",
                null,
                null,
                arrayOf("Password: ", "Verification code: "),
                booleanArrayOf(false, false),
            )
        }

        assertTrue(
            "multi-prompt rounds must reach the UI prompter — fallback password " +
                "only covers the password-substitute-for-password-auth case",
            prompterCalled,
        )
        assertArrayEquals(arrayOf("answer-0", "answer-1"), result)
    }

    @Test
    fun fallbackPasswordIsNotUsedForEchoedPrompts() {
        // A prompt with echo=true isn't a password field — it's asking for
        // something like a username or server-provided OTP. Auto-filling a
        // saved password here would be wrong.
        var prompterCalled = false
        val ui = KeyboardInteractiveUserInfo(
            destination = "alice@example.com:22",
            prompter = {
                prompterCalled = true
                listOf("typed")
            },
            fallbackPassword = "saved-password".toCharArray(),
        )

        callOnBackgroundThread {
            ui.promptKeyboardInteractive(
                "alice@example.com:22",
                null,
                null,
                arrayOf("Username: "),
                booleanArrayOf(true),
            )
        }

        assertTrue("echo=true prompts must reach the prompter", prompterCalled)
    }

    @Test
    fun fallbackPasswordIsNotUsedForNonPasswordSinglePrompt() {
        // "Verification code:" (echo=false, single prompt) — MUST prompt,
        // even with a fallback password set. Fallback only applies to
        // prompts whose text actually mentions "password".
        var prompterCalled = false
        val ui = KeyboardInteractiveUserInfo(
            destination = "alice@example.com:22",
            prompter = {
                prompterCalled = true
                listOf("123456")
            },
            fallbackPassword = "saved-password".toCharArray(),
        )

        callOnBackgroundThread {
            ui.promptKeyboardInteractive(
                "alice@example.com:22",
                null,
                null,
                arrayOf("Verification code: "),
                booleanArrayOf(false),
            )
        }

        assertTrue(
            "a lone TOTP prompt must reach the UI — fallback password " +
                "must not silently answer with the saved password",
            prompterCalled,
        )
    }

    @Test
    fun totpProviderAutoSubmitsLoneOtpPromptWithoutPrompting() {
        var prompterCalled = false
        val ui = KeyboardInteractiveUserInfo(
            destination = "alice@example.com:22",
            prompter = { prompterCalled = true; listOf("typed") },
            totpCodeProvider = { "424242" },
            autoSubmit = true,
        )

        val result = callOnBackgroundThread {
            ui.promptKeyboardInteractive(
                "alice@example.com:22", "Google Authenticator", null,
                arrayOf("Verification code: "), booleanArrayOf(false),
            )
        }

        assertArrayEquals(arrayOf("424242"), result)
        assertTrue("auto-submit must not surface a dialog", !prompterCalled)
    }

    @Test
    fun totpAndPasswordRoundAutoSubmitsBothFromStoredSecrets() {
        var prompterCalled = false
        val ui = KeyboardInteractiveUserInfo(
            destination = "alice@example.com:22",
            prompter = { prompterCalled = true; null },
            fallbackPassword = "saved-password".toCharArray(),
            totpCodeProvider = { "424242" },
            autoSubmit = true,
        )

        val result = callOnBackgroundThread {
            ui.promptKeyboardInteractive(
                "alice@example.com:22", null, null,
                arrayOf("Password: ", "Verification code: "),
                booleanArrayOf(false, false),
            )
        }

        assertArrayEquals(arrayOf("saved-password", "424242"), result)
        assertTrue("a fully-answerable round must not prompt", !prompterCalled)
    }

    @Test
    fun confirmOtpPrefillsTheDialogInsteadOfAutoSubmitting() {
        var seen: KeyboardInteractiveChallenge? = null
        val ui = KeyboardInteractiveUserInfo(
            destination = "alice@example.com:22",
            prompter = { challenge -> seen = challenge; listOf("424242") },
            totpCodeProvider = { "424242" },
            autoSubmit = false,
        )

        val result = callOnBackgroundThread {
            ui.promptKeyboardInteractive(
                "alice@example.com:22", null, null,
                arrayOf("Verification code: "), booleanArrayOf(false),
            )
        }

        assertArrayEquals(arrayOf("424242"), result)
        assertNotNull("confirm-OTP must reach the dialog", seen)
        assertEquals(listOf("424242"), seen!!.prefilled)
    }

    @Test
    fun totpProviderIsNotUsedForEchoedOrNonOtpPrompts() {
        var seen: KeyboardInteractiveChallenge? = null
        val ui = KeyboardInteractiveUserInfo(
            destination = "alice@example.com:22",
            prompter = { challenge -> seen = challenge; listOf("typed") },
            totpCodeProvider = { "424242" },
            autoSubmit = true,
        )

        // An echo=true prompt is not a code field — must reach the dialog,
        // with no pre-fill.
        callOnBackgroundThread {
            ui.promptKeyboardInteractive(
                "alice@example.com:22", null, null,
                arrayOf("Answer the security question: "), booleanArrayOf(true),
            )
        }

        assertNotNull(seen)
        assertTrue("non-OTP prompt must not be pre-filled", seen!!.prefilled.isEmpty())
    }

    @Test
    fun userInfoMethodsNoOp() {
        val ui = KeyboardInteractiveUserInfo(
            destination = "",
            prompter = { null },
        )
        // UserInfo surface area — we deliberately return non-interactive
        // defaults because host-key / passphrase prompts go through
        // separate Haven paths, and we don't want JSch interpreting a
        // null as a reason to prompt for e.g. a passphrase.
        assertNull(ui.password)
        assertNull(ui.passphrase)
        assertEquals(false, ui.promptPassword("p"))
        assertEquals(false, ui.promptPassphrase("p"))
        assertEquals(false, ui.promptYesNo("p"))
        ui.showMessage("any") // default prompter onBanner is a no-op
    }

    @Test
    fun showMessageForwardsTrimmedAuthBannerToPrompterAndCallback() {
        val prompterBanners = mutableListOf<Pair<String, String>>()
        val callbackBanners = mutableListOf<String>()
        val prompter = object : KeyboardInteractivePrompter {
            override suspend fun prompt(challenge: KeyboardInteractiveChallenge): List<String>? = null
            override fun onBanner(destination: String, message: String) {
                prompterBanners += destination to message
            }
        }
        val ui = KeyboardInteractiveUserInfo(
            destination = "root@100.64.0.10:22",
            prompter = prompter,
            onBanner = { callbackBanners += it },
        )

        ui.showMessage(null)
        ui.showMessage("   \n  ")
        val tailscaleBanner = """
            # Tailscale SSH requires an additional check.
            # To authenticate, visit: https://login.tailscale.com/a/1a2b3c4d5e6f
        """.trimIndent()
        ui.showMessage("\n$tailscaleBanner\n")

        assertEquals(listOf(tailscaleBanner), callbackBanners)
        assertEquals(listOf("root@100.64.0.10:22" to tailscaleBanner), prompterBanners)
    }

    @Test
    fun extractAuthBannerUrlsParsesTailscaleAndPunctuatedLinks() {
        val banner = """
            # Tailscale SSH requires an additional check.
            # To authenticate, visit: https://login.tailscale.com/a/1a2b3c4d5e6f
            # Or open (http://100.100.100.100/check).
        """.trimIndent()

        assertEquals(
            listOf(
                "https://login.tailscale.com/a/1a2b3c4d5e6f",
                "http://100.100.100.100/check",
            ),
            extractAuthBannerUrls(banner),
        )
    }

    /**
     * JSch invokes [KeyboardInteractiveUserInfo.promptKeyboardInteractive]
     * on its own IO thread. The bridge uses `runBlocking` to turn the
     * suspend call into a blocking call on that thread. Simulating from
     * a separate thread ensures our code doesn't accidentally block the
     * test's main thread via `runBlocking` misuse.
     */
    private inline fun <T> callOnBackgroundThread(crossinline block: () -> T): T {
        val done = CompletableDeferred<Result<T>>()
        thread(name = "jsch-io-sim") {
            done.complete(
                try { Result.success(block()) } catch (t: Throwable) { Result.failure(t) },
            )
        }
        // runBlocking the outer Deferred so tests stay synchronous.
        return kotlinx.coroutines.runBlocking { done.await() }.getOrThrow()
    }

}
