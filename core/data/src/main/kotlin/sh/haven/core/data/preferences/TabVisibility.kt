package sh.haven.core.data.preferences

/**
 * Per-tab bottom-nav visibility preference (#navbar-visibility).
 *
 * Each screen (tab) is independently configurable:
 *  - [AUTO]: the default. Visibility follows the built-in usage rule —
 *    e.g. Terminal shows only when a terminal profile exists, Mail only
 *    while an email session is connected, Desktop/Keys/Files are always
 *    shown. This is the pre-existing behaviour.
 *  - [SHOW]: force the tab visible regardless of usage.
 *  - [HIDE]: force the tab hidden (removed from the nav bar and the pager).
 *
 * Lived in core/data (not core/ui) because it is a persisted preference and
 * core/data has no dependency on core/ui where [sh.haven.core.ui.navigation.Screen]
 * lives.
 */
enum class TabVisibility {
    AUTO,
    SHOW,
    HIDE;

    companion object {
        fun fromName(value: String): TabVisibility =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}
