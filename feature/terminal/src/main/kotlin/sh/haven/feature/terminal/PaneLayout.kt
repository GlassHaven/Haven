package sh.haven.feature.terminal

/**
 * Minimum available window width (dp) required to render two terminal panes side by side (#141).
 *
 * Matches the `WindowWidthSizeClass.Medium` (600 dp) threshold used by `HavenNavHost` for side
 * rail navigation: portrait phones stay single-pane with zero persistent split chrome, while
 * landscape tablets and wide foldables/desktops can split two cross-transport sessions cleanly.
 */
internal const val SPLIT_PANE_MIN_WIDTH_DP = 600

internal const val DEFAULT_SPLIT_FRACTION = 0.5f
internal const val MIN_SPLIT_FRACTION = 0.2f
internal const val MAX_SPLIT_FRACTION = 0.8f

enum class SplitSide {
    LEFT,
    RIGHT,
}

/**
 * Layout model for the terminal viewport (#141).
 *
 * Kept pure and keyed by `sessionId` rather than tab list index so tab reordering (`moveTab`)
 * never swaps or scrambles panes, and closing or disconnecting either session reconciles
 * deterministically back to [Single].
 */
sealed interface PaneLayout {
    data object Single : PaneLayout

    data class SplitH(
        val leftSessionId: String,
        val rightSessionId: String,
        val focusedSide: SplitSide = SplitSide.RIGHT,
        val splitFraction: Float = DEFAULT_SPLIT_FRACTION,
    ) : PaneLayout {
        init {
            require(leftSessionId.isNotBlank() && rightSessionId.isNotBlank()) {
                "SplitH requires non-blank session IDs"
            }
            require(leftSessionId != rightSessionId) {
                "SplitH requires two distinct sessions"
            }
        }

        val focusedSessionId: String
            get() = when (focusedSide) {
                SplitSide.LEFT -> leftSessionId
                SplitSide.RIGHT -> rightSessionId
            }

        fun contains(sessionId: String): Boolean =
            sessionId == leftSessionId || sessionId == rightSessionId
    }
}

/**
 * Enter or update a horizontal 2-pane split pairing the currently focused [activeSessionId]
 * with [targetSessionId].
 *
 * - Splitting a session with itself is a no-op.
 * - From [PaneLayout.Single], [activeSessionId] stays on the left and [targetSessionId] opens
 *   on the right with focus.
 * - From [PaneLayout.SplitH], if [targetSessionId] is already in the split, focus moves to its
 *   pane; otherwise [targetSessionId] replaces the unfocused pane alongside the currently
 *   focused session and receives focus.
 */
internal fun PaneLayout.splitWith(
    activeSessionId: String,
    targetSessionId: String,
): PaneLayout {
    if (activeSessionId.isBlank() || targetSessionId.isBlank()) return this
    if (activeSessionId == targetSessionId) return this
    return when (this) {
        PaneLayout.Single -> PaneLayout.SplitH(
            leftSessionId = activeSessionId,
            rightSessionId = targetSessionId,
            focusedSide = SplitSide.RIGHT,
            splitFraction = DEFAULT_SPLIT_FRACTION,
        )
        is PaneLayout.SplitH -> when (targetSessionId) {
            leftSessionId -> copy(focusedSide = SplitSide.LEFT)
            rightSessionId -> copy(focusedSide = SplitSide.RIGHT)
            else -> when (focusedSide) {
                SplitSide.LEFT -> copy(
                    rightSessionId = targetSessionId,
                    focusedSide = SplitSide.RIGHT,
                )
                SplitSide.RIGHT -> copy(
                    leftSessionId = targetSessionId,
                    focusedSide = SplitSide.LEFT,
                )
            }
        }
    }
}

internal fun PaneLayout.unsplit(): PaneLayout = PaneLayout.Single

internal fun PaneLayout.focusSide(side: SplitSide): PaneLayout = when (this) {
    PaneLayout.Single -> PaneLayout.Single
    is PaneLayout.SplitH -> if (focusedSide == side) this else copy(focusedSide = side)
}

internal fun PaneLayout.resizeSplit(fraction: Float): PaneLayout = when (this) {
    PaneLayout.Single -> PaneLayout.Single
    is PaneLayout.SplitH -> copy(
        splitFraction = fraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION),
    )
}

/**
 * Update split state when the user selects [selectedSessionId] from the tab strip.
 *
 * Selecting a tab already visible in the split focuses its side without disturbing the other
 * pane; selecting a third tab places it into the focused pane slot.
 */
internal fun PaneLayout.onSelectTab(selectedSessionId: String): PaneLayout = when (this) {
    PaneLayout.Single -> PaneLayout.Single
    is PaneLayout.SplitH -> when (selectedSessionId) {
        leftSessionId -> copy(focusedSide = SplitSide.LEFT)
        rightSessionId -> copy(focusedSide = SplitSide.RIGHT)
        else -> if (selectedSessionId.isBlank()) {
            this
        } else {
            when (focusedSide) {
                SplitSide.LEFT -> copy(leftSessionId = selectedSessionId)
                SplitSide.RIGHT -> copy(rightSessionId = selectedSessionId)
            }
        }
    }
}

/**
 * Reconcile the split against the set of [liveSessionIds] after tab close or transport disconnect.
 *
 * Returns the updated [PaneLayout] paired with the `sessionId` that should remain active/focused
 * (or `null` when [PaneLayout.Single] leaves active-tab selection to normal index clamping).
 */
internal fun PaneLayout.reconcile(liveSessionIds: Set<String>): Pair<PaneLayout, String?> =
    when (this) {
        PaneLayout.Single -> PaneLayout.Single to null
        is PaneLayout.SplitH -> {
            val leftAlive = leftSessionId in liveSessionIds
            val rightAlive = rightSessionId in liveSessionIds
            when {
                leftAlive && rightAlive -> this to focusedSessionId
                leftAlive -> PaneLayout.Single to leftSessionId
                rightAlive -> PaneLayout.Single to rightSessionId
                else -> PaneLayout.Single to null
            }
        }
    }

/**
 * Effective layout to render for [availableWidthDp]: below [SPLIT_PANE_MIN_WIDTH_DP] (portrait
 * phones), split mode auto-collapses to [PaneLayout.Single] showing the focused tab without
 * discarding the underlying [PaneLayout.SplitH] when rotating back to landscape.
 */
internal fun PaneLayout.effectiveForWidth(availableWidthDp: Int): PaneLayout =
    if (this is PaneLayout.SplitH && availableWidthDp >= SPLIT_PANE_MIN_WIDTH_DP) {
        this
    } else {
        PaneLayout.Single
    }
