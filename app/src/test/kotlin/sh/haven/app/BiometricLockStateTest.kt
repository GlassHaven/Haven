package sh.haven.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.security.BiometricAuthenticator

/**
 * C2 regression: BiometricLockScreen must NOT auto-unlock when biometric
 * authentication is unavailable. The previous bug fired
 * `LaunchedEffect(Unit) { onUnlocked() }` whenever
 * `checkAvailability() != AVAILABLE`, silently bypassing the app lock
 * any time fingerprint enrollment was removed.
 *
 * The fix extracted the gating decision into [biometricLockStateFor]
 * (a pure non-composable function) and a sealed [BiometricLockState]
 * type. These tests pin the invariant that no [BiometricAuthenticator.Availability]
 * value can map to a state that lets the lock screen auto-unlock.
 */
class BiometricLockStateTest {

    @Test
    fun `AVAILABLE with host activity maps to Prompt`() {
        val state = biometricLockStateFor(
            availability = BiometricAuthenticator.Availability.AVAILABLE,
            hasFragmentActivity = true,
        )
        assertEquals(BiometricLockState.Prompt, state)
    }

    @Test
    fun `NOT_ENROLLED maps to Blocked, not Prompt`() {
        val state = biometricLockStateFor(
            availability = BiometricAuthenticator.Availability.NOT_ENROLLED,
            hasFragmentActivity = true,
        )
        assertTrue("must be Blocked, was $state", state is BiometricLockState.Blocked)
        val blocked = state as BiometricLockState.Blocked
        assertTrue(
            "user-facing copy must explain how to recover",
            blocked.body.contains("screen lock", ignoreCase = true) ||
                blocked.body.contains("biometric", ignoreCase = true),
        )
        assertTrue(
            "NOT_ENROLLED must offer a Settings shortcut so the user can fix it",
            blocked.canOpenSettings,
        )
    }

    @Test
    fun `NO_HARDWARE maps to Blocked, not Prompt`() {
        val state = biometricLockStateFor(
            availability = BiometricAuthenticator.Availability.NO_HARDWARE,
            hasFragmentActivity = true,
        )
        assertTrue("must be Blocked, was $state", state is BiometricLockState.Blocked)
        assertTrue((state as BiometricLockState.Blocked).canOpenSettings)
    }

    @Test
    fun `no host activity blocks regardless of availability`() {
        for (availability in BiometricAuthenticator.Availability.values()) {
            val state = biometricLockStateFor(availability, hasFragmentActivity = false)
            assertTrue(
                "without a FragmentActivity the lock must Block (was $state for $availability)",
                state is BiometricLockState.Blocked,
            )
            // Settings shortcut needs an activity to actually do startActivity(),
            // so it must be disabled in this branch.
            assertFalse((state as BiometricLockState.Blocked).canOpenSettings)
        }
    }

    /**
     * Exhaustive sweep: for every defined [BiometricAuthenticator.Availability]
     * value, the only mapping that produces [BiometricLockState.Prompt] (i.e. the
     * only state in which BiometricLockScreen will ever invoke `onUnlocked`)
     * must be [Availability.AVAILABLE]. This is the load-bearing assertion of
     * the C2 fix — if a future refactor lets ANY other availability slip
     * through to Prompt, this test fails immediately.
     */
    @Test
    fun `only AVAILABLE plus host activity allows the prompt to run`() {
        val promptingAvailabilities = BiometricAuthenticator.Availability.values()
            .filter { biometricLockStateFor(it, hasFragmentActivity = true) is BiometricLockState.Prompt }

        assertEquals(
            "exactly one Availability value (AVAILABLE) may unlock the prompt",
            listOf(BiometricAuthenticator.Availability.AVAILABLE),
            promptingAvailabilities,
        )
    }

    @Test
    fun `Blocked state always carries non-empty title and body`() {
        val cases = listOf(
            BiometricAuthenticator.Availability.NOT_ENROLLED to true,
            BiometricAuthenticator.Availability.NO_HARDWARE to true,
            BiometricAuthenticator.Availability.AVAILABLE to false, // no activity → blocked
        )
        for ((availability, hasActivity) in cases) {
            val state = biometricLockStateFor(availability, hasFragmentActivity = hasActivity)
            assertNotNull(state)
            if (state is BiometricLockState.Blocked) {
                assertTrue("title for $availability/$hasActivity is blank", state.title.isNotBlank())
                assertTrue("body for $availability/$hasActivity is blank", state.body.isNotBlank())
            }
        }
    }
}
