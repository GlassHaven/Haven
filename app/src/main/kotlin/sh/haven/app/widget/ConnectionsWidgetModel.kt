package sh.haven.app.widget

import android.content.Context
import sh.haven.core.data.db.entities.ConnectionProfile

enum class WidgetSortOrder {
    SAVED_ORDER,
    RECENT_FIRST,
}

data class WidgetConfig(
    val groupId: String? = null,
    val groupLabel: String? = null,
    val sortOrder: WidgetSortOrder = WidgetSortOrder.SAVED_ORDER,
)

data class WidgetConnectionRow(
    val profileId: String,
    val label: String,
    val badge: String,
    val subtitle: String,
)

object ConnectionsWidgetModel {

    private const val PREFS_NAME = "haven_connections_widget_prefs"

    fun loadConfig(context: Context, appWidgetId: Int): WidgetConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val groupId = prefs.getString("group_id_$appWidgetId", null)?.ifBlank { null }
        val groupLabel = prefs.getString("group_label_$appWidgetId", null)?.ifBlank { null }
        val rawSort = prefs.getString("sort_order_$appWidgetId", null)
        val sort = WidgetSortOrder.entries.firstOrNull { it.name == rawSort }
            ?: WidgetSortOrder.SAVED_ORDER
        return WidgetConfig(groupId = groupId, groupLabel = groupLabel, sortOrder = sort)
    }

    fun saveConfig(context: Context, appWidgetId: Int, config: WidgetConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            if (config.groupId != null) {
                putString("group_id_$appWidgetId", config.groupId)
            } else {
                remove("group_id_$appWidgetId")
            }
            if (config.groupLabel != null) {
                putString("group_label_$appWidgetId", config.groupLabel)
            } else {
                remove("group_label_$appWidgetId")
            }
            putString("sort_order_$appWidgetId", config.sortOrder.name)
            apply()
        }
    }

    fun deleteConfig(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            remove("group_id_$appWidgetId")
            remove("group_label_$appWidgetId")
            remove("sort_order_$appWidgetId")
            apply()
        }
    }

    /**
     * Pure selection + formatting for widget rows (#852).
     * Never reads or exposes credential columns on [ConnectionProfile].
     */
    fun buildRows(
        profiles: List<ConnectionProfile>,
        config: WidgetConfig = WidgetConfig(),
    ): List<WidgetConnectionRow> {
        val savedOrderComparator = compareBy<ConnectionProfile> { it.sortOrder }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }

        val eligible = profiles.asSequence()
            .filter { !it.ephemeral }
            .filter { it.connectionType != "OPENAI" && it.connectionType != "EMAIL" }
            .filter { config.groupId == null || it.groupId == config.groupId }
            .toList()

        val sorted = when (config.sortOrder) {
            WidgetSortOrder.SAVED_ORDER -> eligible.sortedWith(savedOrderComparator)
            WidgetSortOrder.RECENT_FIRST -> {
                val (connected, neverConnected) = eligible.partition { (it.lastConnected ?: 0L) > 0L }
                connected.sortedWith(
                    compareByDescending<ConnectionProfile> { it.lastConnected ?: 0L }
                        .then(savedOrderComparator),
                ) + neverConnected.sortedWith(savedOrderComparator)
            }
        }

        return sorted.map { p ->
            val (badge, subtitle) = formatBadgeAndSubtitle(p)
            WidgetConnectionRow(
                profileId = p.id,
                label = p.label,
                badge = badge,
                subtitle = subtitle,
            )
        }
    }

    private fun formatBadgeAndSubtitle(p: ConnectionProfile): Pair<String, String> =
        when (p.connectionType) {
            "SSH" -> {
                val badge = when {
                    p.useMosh -> "MOSH"
                    p.useEternalTerminal -> "ET"
                    else -> "SSH"
                }
                val port = if (p.useEternalTerminal) p.etPort else p.port
                val defPort = if (p.useEternalTerminal) 2022 else 22
                badge to formatUserHostPort(p.username, p.host, port, defaultPort = defPort)
            }
            "LOCAL" -> "LOCAL" to p.host.ifBlank { "Local shell" }
            "VNC" -> "VNC" to formatHostPort(p.host, p.vncPort ?: p.port, defaultPort = 5900)
            "RDP" -> "RDP" to formatUserHostPort(
                user = p.rdpUsername?.ifBlank { null } ?: p.username,
                host = p.host,
                port = p.rdpPort,
                defaultPort = 3389,
            )
            "SPICE" -> "SPICE" to formatHostPort(p.host, p.spicePort ?: p.port, defaultPort = 5900)
            "GLKVM" -> "KVM" to formatHostPort(p.host, p.glkvmPort ?: 443, defaultPort = 443)
            "RCLONE" -> {
                val sub = listOfNotNull(
                    p.rcloneProvider?.takeIf { it.isNotBlank() },
                    p.rcloneRemoteName?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifEmpty { p.host }
                "CLOUD" to sub
            }
            "SMB" -> {
                val sub = buildString {
                    append(p.host)
                    p.smbShare?.takeIf { it.isNotBlank() }?.let { share ->
                        append('/').append(share.trimStart('/'))
                    }
                }
                "SMB" to sub
            }
            "SAF" -> "FOLDER" to "Local folder"
            "RETICULUM" -> {
                val sub = p.destinationHash?.takeIf { it.isNotBlank() }?.let { h ->
                    if (h.length > 16) "${h.take(16)}…" else h
                } ?: p.host
                "RNS" to sub
            }
            else -> p.connectionType.take(6).uppercase() to p.host
        }

    private fun formatUserHostPort(user: String, host: String, port: Int, defaultPort: Int): String {
        val hostPart = formatHostPort(host, port, defaultPort)
        return if (user.isNotBlank() && hostPart.isNotBlank()) "$user@$hostPart" else hostPart
    }

    private fun formatHostPort(host: String, port: Int, defaultPort: Int): String {
        if (host.isBlank()) return ""
        return if (port > 0 && port != defaultPort) "$host:$port" else host
    }
}
