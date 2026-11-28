package sh.haven.feature.connections

import sh.haven.core.data.db.entities.ConnectionProfile

/**
 * The profile a connection is drawn under in the connections list: the SSH
 * profile that carries its tunnel — a jump-host chain, or a VNC/RDP/SMB-over-SSH
 * forward. VNC/SMB are gated by their forward flag (vncSshForward defaults
 * true) so a direct connection with a stale sshProfileId isn't wrongly nested.
 *
 * Null when the connection is top-level, including when the profile it points
 * at is not in [knownIds] (deleted, or filtered out of the list).
 */
internal fun dependentParentId(profile: ConnectionProfile, knownIds: Set<String>): String? {
    val parentId = profile.jumpProfileId
        ?: profile.vncSshProfileId?.takeIf { profile.vncSshForward }
        ?: profile.rdpSshProfileId
        ?: profile.smbSshProfileId?.takeIf { profile.smbSshForward }
    return parentId?.takeIf { it in knownIds }
}

/**
 * Children of each parent, in the order of [connections] — which is the
 * DAO's `sortOrder ASC, label ASC`, so a parent's children sort among
 * themselves by the same field the top-level list uses.
 */
internal fun groupDependents(
    connections: List<ConnectionProfile>,
): Map<String, List<ConnectionProfile>> {
    val knownIds = connections.mapTo(HashSet()) { it.id }
    return connections
        .mapNotNull { profile -> dependentParentId(profile, knownIds)?.let { it to profile } }
        .groupBy({ it.first }, { it.second })
}

/**
 * Apply a locally held sibling order on top of the stored one.
 *
 * [override] is what a drag produced and has not yet been echoed back by the
 * database. It only applies while it still names exactly the children the
 * parent has: once a child is added, removed or re-parented underneath it, the
 * override describes a list that no longer exists and the stored order wins.
 */
internal fun applySiblingOrder(
    stored: List<ConnectionProfile>,
    override: List<String>?,
): List<ConnectionProfile> {
    if (override == null || override.size != stored.size) return stored
    val byId = stored.associateBy { it.id }
    if (!byId.keys.containsAll(override) || override.toSet().size != override.size) return stored
    return override.map { byId.getValue(it) }
}

/**
 * Move [id] one place among its [siblings]. Returns null when it is not one of
 * them, or is already at the end it is being moved towards — a dependent never
 * leaves its parent by being dragged.
 */
internal fun moveSibling(siblings: List<String>, id: String, down: Boolean): List<String>? {
    val here = siblings.indexOf(id)
    val there = if (down) here + 1 else here - 1
    if (here < 0 || there !in siblings.indices) return null
    return siblings.toMutableList().apply {
        this[here] = siblings[there]
        this[there] = id
    }
}

/**
 * [rootId] followed by everything drawn beneath it, in list order. These rows
 * travel together when one of them is dragged, and together make up the block
 * a dragged sibling has to step over.
 *
 * Guards against a cycle in the parent links the same way the list does (#116).
 */
internal fun subtreeIds(rootId: String, childrenOf: (String) -> List<String>): List<String> {
    val out = mutableListOf<String>()
    val seen = HashSet<String>()
    val stack = ArrayDeque<String>().apply { addFirst(rootId) }
    while (stack.isNotEmpty()) {
        val id = stack.removeFirst()
        if (!seen.add(id)) continue
        out += id
        childrenOf(id).asReversed().forEach { stack.addFirst(it) }
    }
    return out
}

/**
 * The sortOrder a profile being saved should carry so that it lands *after*
 * its siblings, or null to leave its sortOrder alone.
 *
 * Only for a profile arriving under a parent — newly created there, duplicated
 * there, or moved there from somewhere else. Without this it arrives with the
 * default sortOrder 0 (or its source's), which drops it at the top of — or
 * into the middle of — an order the user arranged by hand.
 */
internal fun sortOrderAfterSiblings(
    profile: ConnectionProfile,
    existing: ConnectionProfile?,
    all: List<ConnectionProfile>,
): Int? {
    val others = all.filter { it.id != profile.id }
    val knownIds = others.mapTo(HashSet()) { it.id }
    val parentId = dependentParentId(profile, knownIds) ?: return null
    if (existing != null && dependentParentId(existing, knownIds) == parentId) return null
    val siblings = others.filter { dependentParentId(it, knownIds) == parentId }
    return (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1
}
