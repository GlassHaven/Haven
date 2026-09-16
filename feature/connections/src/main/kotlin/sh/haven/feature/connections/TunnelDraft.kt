package sh.haven.feature.connections

/**
 * Pure state helpers for the "Tunnel through SSH" editor rows shared by
 * VNC / RDP / SPICE / SMB in [ConnectionEditDialog]. Each protocol keeps
 * its own flag/carrier-ID pair on ConnectionProfile; these helpers (plus
 * the SshTunnelBlock composable) centralise the behaviour so the four
 * rows can't drift.
 */

/**
 * Strict initial toggle state used by VNC and SPICE: enabled only when the
 * stored profile is actually of this [type], the stored [flag] is set, AND
 * a carrier profile is selected. Guards against stale rows — the flag
 * historically defaulted true, and a type-switched profile can still carry
 * another protocol's flag. RDP and SMB intentionally initialise from the
 * raw stored flag instead (a missing carrier there surfaces as the
 * "Select SSH connection" dropdown plus a blocked Save).
 */
internal fun strictTunnelInitialEnabled(
    storedType: String?,
    type: String,
    flag: Boolean,
    carrierId: String?,
): Boolean = storedType == type && flag && carrierId != null

/**
 * Host rewrite when the tunnel toggle flips. Enabling defaults a blank or
 * `localhost` host to `127.0.0.1` — the IPv4 loopback, so the remote sshd
 * doesn't resolve the IPv6 loopback first and fail against a server bound
 * to IPv4 only (VNC tunnel fix, v5.24.14). Disabling clears only the
 * loopback values the toggle itself put there; a custom host survives
 * round trips of the switch.
 */
internal fun tunnelHostOnToggle(enabled: Boolean, host: String): String = when {
    enabled && (host.isBlank() || host == "localhost") -> "127.0.0.1"
    !enabled && (host == "127.0.0.1" || host == "localhost") -> ""
    else -> host
}

/** Save is blocked while the tunnel is enabled but no carrier is picked. */
internal fun tunnelComplete(enabled: Boolean, carrierId: String?): Boolean =
    !enabled || carrierId != null

/** Persist the carrier ID only while the tunnel is enabled. */
internal fun tunnelCarrierForSave(enabled: Boolean, carrierId: String?): String? =
    if (enabled) carrierId else null

/**
 * AI-endpoint route carrier, the OPENAI counterpart of the four desktop
 * rows above. The editor speaks in display modes ("NONE" / "SSH" /
 * "RETICULUM"); the profile stores a single (type, carrier) pair, which is
 * self-exclusive — a saved profile can never carry two carriers, unlike
 * the desktop flag+id pairs that need [strictTunnelInitialEnabled] to
 * police staleness.
 */

/** Map the editor's mode to the stored columns; NONE means unrouted. */
internal fun aiRouteTypeForSave(mode: String): String? =
    if (mode == "NONE") null else mode

/**
 * Strict initial mode, mirroring [strictTunnelInitialEnabled]'s stale-row
 * guard: the stored pair counts only when both halves are present — a
 * type without a carrier (or the reverse) reads as Direct and the stale
 * half is dropped on the next save.
 */
internal fun aiRouteInitialMode(storedType: String?, carrierId: String?): String =
    if (storedType == "SSH" || storedType == "RETICULUM") {
        if (carrierId != null) storedType else "NONE"
    } else "NONE"

/** Save is blocked while a mode is picked but no carrier is. */
internal fun aiRouteComplete(mode: String, carrierId: String?): Boolean =
    mode == "NONE" || carrierId != null

/** Persist the carrier only while a route mode is set. */
internal fun aiRouteCarrierForSave(mode: String, carrierId: String?): String? =
    if (mode == "NONE") null else carrierId

/**
 * Apply the shared Route-through picker's state onto a profile being
 * saved. The picker (WireGuard/Tailscale tunnel or SOCKS/HTTP proxy,
 * mutually exclusive) is rendered for VNC / RDP / SPICE / SMB as well as
 * SSH, but only the SSH and EMAIL save branches ever persisted what it
 * wrote — for the other four a picked tunnel or proxy silently reverted
 * to "None (direct)" on save (#527). SSH keeps its own inline handling:
 * the Cloudflare transport interleaves with these fields there.
 */
internal fun sh.haven.core.data.db.entities.ConnectionProfile.withRoutingSelection(
    proxyType: String?,
    proxyHost: String,
    proxyPort: String,
    proxyUser: String,
    proxyPassword: String,
    tunnelConfigId: String?,
): sh.haven.core.data.db.entities.ConnectionProfile = copy(
    proxyType = proxyType,
    proxyHost = if (proxyType == null) null else proxyHost.ifBlank { null },
    proxyPort = proxyPort.toIntOrNull() ?: 1080,
    proxyUser = if (proxyType == null) null else proxyUser.ifBlank { null },
    proxyPassword = if (proxyType == null || proxyType == "SOCKS4") null
        else proxyPassword.ifBlank { null },
    tunnelConfigId = tunnelConfigId,
)
