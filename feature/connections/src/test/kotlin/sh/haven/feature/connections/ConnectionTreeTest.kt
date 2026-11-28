package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile

/**
 * The connection list's tree: which profiles nest under which, and reordering
 * a parent's children among themselves.
 *
 * The fixture is a bastion with a jump-host child, a deeper chain under that
 * child, and a VNC profile tunnelled over the same bastion — the mix a real
 * list ends up with, since all of them are drawn as children of the bastion.
 */
class ConnectionTreeTest {

    private fun ssh(id: String, jump: String? = null, sortOrder: Int = 0) = ConnectionProfile(
        id = id, label = id, host = "$id.example", username = "u",
        jumpProfileId = jump, sortOrder = sortOrder,
    )

    private val bastion = ssh("bastion")
    private val win = ssh("win", jump = "bastion", sortOrder = 0)
    private val mac = ssh("mac", jump = "bastion", sortOrder = 1)
    private val pad = ssh("pad", jump = "mac")
    private val vnc = ConnectionProfile(
        id = "vnc", label = "vnc", host = "127.0.0.1", username = "",
        connectionType = "VNC", vncSshProfileId = "bastion", sortOrder = 2,
    )
    private val standalone = ssh("standalone")

    /** In DAO order: sortOrder, then label. */
    private val all = listOf(bastion, pad, standalone, win, mac, vnc)

    private fun childIds(parent: String, list: List<ConnectionProfile> = all) =
        groupDependents(list)[parent].orEmpty().map { it.id }

    // --- nesting ---------------------------------------------------------

    @Test fun jumpHostsAndSshForwardsNestUnderTheSameParent() {
        assertEquals(listOf("win", "mac", "vnc"), childIds("bastion"))
        assertEquals(listOf("pad"), childIds("mac"))
        assertEquals(emptyList<String>(), childIds("standalone"))
    }

    @Test fun aParentThatIsNotInTheListLeavesTheProfileTopLevel() {
        val orphan = ssh("orphan", jump = "deleted")
        assertNull(dependentParentId(orphan, setOf("orphan", "bastion")))
        assertEquals(emptyMap<String, List<ConnectionProfile>>(), groupDependents(listOf(orphan)))
    }

    @Test fun aDirectVncProfileWithAStaleSshProfileIsNotNested() {
        val direct = vnc.copy(vncSshForward = false)
        assertNull(dependentParentId(direct, setOf("bastion", "vnc")))
    }

    @Test fun smbOnlyNestsWhenItsForwardIsOn() {
        val smb = ConnectionProfile(
            id = "smb", label = "smb", host = "nas", username = "u",
            connectionType = "SMB", smbSshProfileId = "bastion",
        )
        assertNull(dependentParentId(smb, setOf("bastion", "smb")))
        assertEquals("bastion", dependentParentId(smb.copy(smbSshForward = true), setOf("bastion", "smb")))
    }

    // --- sibling order ---------------------------------------------------

    @Test fun moveSiblingSwapsWithTheNeighbour() {
        val siblings = listOf("win", "mac", "vnc")
        assertEquals(listOf("mac", "win", "vnc"), moveSibling(siblings, "win", down = true))
        assertEquals(listOf("win", "vnc", "mac"), moveSibling(siblings, "vnc", down = false))
    }

    @Test fun moveSiblingStopsAtEitherEndAndNeverLeavesTheParent() {
        val siblings = listOf("win", "mac", "vnc")
        assertNull(moveSibling(siblings, "win", down = false))
        assertNull(moveSibling(siblings, "vnc", down = true))
        assertNull(moveSibling(siblings, "standalone", down = true))
    }

    @Test fun anOverrideReordersTheStoredChildren() {
        val stored = listOf(win, mac, vnc)
        assertEquals(
            listOf("vnc", "win", "mac"),
            applySiblingOrder(stored, listOf("vnc", "win", "mac")).map { it.id },
        )
        assertEquals(stored, applySiblingOrder(stored, null))
    }

    /**
     * The override is a drag's result waiting for the database. If the
     * children changed in the meantime it describes a list that is gone, and
     * applying it would drop the new child or resurrect the deleted one.
     */
    @Test fun aStaleOverrideIsIgnored() {
        val stored = listOf(win, mac, vnc)
        assertEquals(stored, applySiblingOrder(stored, listOf("mac", "win")))
        assertEquals(stored, applySiblingOrder(stored, listOf("mac", "win", "gone")))
        assertEquals(stored, applySiblingOrder(stored, listOf("mac", "mac", "win")))
    }

    // --- blocks ----------------------------------------------------------

    @Test fun aSubtreeIsTheRowAndEverythingDrawnUnderItInListOrder() {
        val kids = groupDependents(all)
        fun childrenOf(id: String) = kids[id].orEmpty().map { it.id }
        assertEquals(listOf("bastion", "win", "mac", "pad", "vnc"), subtreeIds("bastion", ::childrenOf))
        assertEquals(listOf("mac", "pad"), subtreeIds("mac", ::childrenOf))
        assertEquals(listOf("win"), subtreeIds("win", ::childrenOf))
    }

    @Test fun aCycleInTheParentLinksTerminates() {
        val a = ssh("a", jump = "b")
        val b = ssh("b", jump = "a")
        val kids = groupDependents(listOf(a, b))
        assertEquals(listOf("a", "b"), subtreeIds("a") { id -> kids[id].orEmpty().map { it.id } })
    }

    // --- where a new child lands -----------------------------------------

    @Test fun aNewChildGoesAfterItsSiblings() {
        val fresh = ssh("fresh", jump = "bastion")
        assertEquals(3, sortOrderAfterSiblings(fresh, existing = null, all))
    }

    @Test fun theFirstChildOfAParentStartsAtZero() {
        val fresh = ssh("fresh", jump = "standalone")
        assertEquals(0, sortOrderAfterSiblings(fresh, existing = null, all))
    }

    /** A duplicate copies its source's sortOrder, which would tie with it. */
    @Test fun aDuplicatedChildGoesAfterItsSiblingsToo() {
        val copy = win.copy(id = "win-copy")
        assertEquals(3, sortOrderAfterSiblings(copy, existing = null, all))
    }

    @Test fun editingAChildInPlaceKeepsItsPosition() {
        assertNull(sortOrderAfterSiblings(win.copy(label = "renamed"), existing = win, all))
    }

    @Test fun movingAChildToAnotherParentAppendsItThere() {
        val moved = win.copy(jumpProfileId = "mac")
        assertEquals(1, sortOrderAfterSiblings(moved, existing = win, all))
    }

    @Test fun topLevelProfilesAreLeftAlone() {
        assertNull(sortOrderAfterSiblings(ssh("fresh"), existing = null, all))
        assertNull(sortOrderAfterSiblings(standalone.copy(sortOrder = 9), existing = standalone, all))
    }

    // --- search ----------------------------------------------------------

    @Test fun theFilterMatchesLabelHostAndUsernameIgnoringCase() {
        val p = ConnectionProfile(id = "p", label = "Work Mac", host = "mini.example", port = 2200, username = "ywr")
        assertTrue(matchesConnectionFilter(p, "mac"))
        assertTrue(matchesConnectionFilter(p, "MINI"))
        assertTrue(matchesConnectionFilter(p, "yw"))
        assertFalse(matchesConnectionFilter(p, "nas"))
    }

    /**
     * The field doubles as the quick-connect bar, so an address is a normal
     * thing to type into it. One that is already saved has to be found.
     */
    @Test fun theFilterMatchesAnAddressTypedTheWayTheRowShowsIt() {
        val p = ConnectionProfile(id = "p", label = "Work Mac", host = "mini.example", port = 2200, username = "ywr")
        assertTrue(matchesConnectionFilter(p, "ywr@mini.example"))
        assertTrue(matchesConnectionFilter(p, "mini.example:2200"))
        assertFalse(matchesConnectionFilter(p, "root@mini.example"))
    }

    @Test fun anEmptyFieldIsNotASearch() {
        assertFalse(matchesConnectionFilter(bastion, ""))
        assertFalse(matchesConnectionFilter(bastion, "   "))
    }
}
