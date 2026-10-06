package sh.haven.feature.terminal

import android.content.Context

/**
 * XTVERSION identity Haven's emulators answer with (CSI > 0 q → `DCS >|Haven(<version>)`).
 *
 * Multiplexers auto-detect their outer terminal by XTVERSION probe (tmux compares
 * the reply against a prefix table), so the reply must name the hosting app, not
 * the vendored emulator (`libvterm(0.3)`). The version comes from the installed
 * package's versionName at first use, so it tracks the release instead of a
 * build-time compile constant. `null` (no versionName on the package) falls back
 * to libvterm's default reply, which keeps probe clients unaffected.
 */
object XtversionIdentity {
    @Volatile private var cached: String? = null

    fun of(context: Context): String? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val version = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: Exception) {
                null
            }
            val identity = version?.takeIf { it.isNotBlank() }?.let { "Haven($it)" }
            cached = identity
            return identity
        }
    }
}