package sh.haven.core.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version ordering for the #578 update check. The only thing standing between
 * a correct offer and telling every user on the newest build that they are out
 * of date is this comparison, so it is tested apart from the network and the
 * signature gate.
 */
class UpdateVersionCompareTest {

    @Test
    fun `tag prefix is stripped`() {
        assertEquals("5.87.50", UpdateChecker.normaliseVersion("v5.87.50"))
        assertEquals("5.87.50", UpdateChecker.normaliseVersion("  v5.87.50 "))
        assertEquals("5.87.50", UpdateChecker.normaliseVersion("5.87.50"))
    }

    @Test
    fun `newer patch is newer`() {
        assertTrue(UpdateChecker.isNewer("v5.87.51", "5.87.50"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(UpdateChecker.isNewer("v5.87.50", "5.87.50"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(UpdateChecker.isNewer("v5.87.49", "5.87.50"))
    }

    @Test
    fun `components compare numerically not lexically`() {
        // The bug this guards: "5.87.9" > "5.87.50" as strings.
        assertTrue(UpdateChecker.isNewer("v5.87.50", "5.87.9"))
        assertFalse(UpdateChecker.isNewer("v5.87.9", "5.87.50"))
        assertTrue(UpdateChecker.isNewer("v5.100.0", "5.87.50"))
    }

    @Test
    fun `minor and major dominate patch`() {
        assertTrue(UpdateChecker.isNewer("v5.88.0", "5.87.50"))
        assertTrue(UpdateChecker.isNewer("v6.0.0", "5.87.50"))
        assertFalse(UpdateChecker.isNewer("v4.99.99", "5.87.50"))
    }

    @Test
    fun `missing components count as zero`() {
        assertTrue(UpdateChecker.isNewer("v5.88", "5.87.50"))
        assertFalse(UpdateChecker.isNewer("v5.87", "5.87.50"))
        assertFalse(UpdateChecker.isNewer("v5.87.0", "5.87"))
    }

    @Test
    fun `a blank version never triggers an offer`() {
        assertFalse(UpdateChecker.isNewer("", "5.87.50"))
        assertFalse(UpdateChecker.isNewer("v5.87.51", ""))
    }

    @Test
    fun `a prerelease suffix does not out-rank the release`() {
        assertFalse(UpdateChecker.isNewer("v5.87.50-rc1", "5.87.50"))
        assertTrue(UpdateChecker.isNewer("v5.87.51-rc1", "5.87.50"))
    }

    @Test
    fun `an unparseable component does not trigger an offer`() {
        assertFalse(UpdateChecker.isNewer("vnightly", "5.87.50"))
        assertFalse(UpdateChecker.isNewer("v5.87.latest", "5.87.50"))
    }
}
