package sh.haven.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import sh.haven.core.security.CredentialEncryption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {
    private val biometricEnabledKey = booleanPreferencesKey("biometric_enabled")
    private val terminalFontSizeKey = intPreferencesKey("terminal_font_size")
    // Mail message-list pinch-zoom factor, multiplied onto the terminal font size.
    private val mailFontScaleKey = floatPreferencesKey("mail_font_scale")
    private val terminalScrollbackRowsKey = intPreferencesKey("terminal_scrollback_rows")
    private val prootIdleTimeoutMinutesKey = intPreferencesKey("proot_idle_timeout_minutes")
    private val rdpDesktopWidthKey = intPreferencesKey("rdp_desktop_width")
    private val rdpDesktopHeightKey = intPreferencesKey("rdp_desktop_height")
    private val terminalTapToPositionCursorKey = booleanPreferencesKey("terminal_tap_to_position_cursor")
    // #418 debug: enable RemoteFX-Progressive WBT_TILE_UPGRADE refinement
    // decoding for RDP. Off by default while the upgrade path is verified.
    private val rdpProgressiveUpgradeKey = booleanPreferencesKey("rdp_progressive_upgrade")
    // #425: advertise EGFX H.264/AVC420 so H.264-only servers (KRDP) render.
    // Off by default while the MediaCodec decode path is verified on-device.
    private val rdpAvcEnabledKey = booleanPreferencesKey("rdp_avc_enabled")
    // Absolute path to a user-chosen Nerd Font (or any TTF/OTF). #123.
    private val terminalFontPathKey = stringPreferencesKey("terminal_font_path")
    // Extra trailing prompt characters (beyond $ # % > ❯) for command-on-attach
    // detection — supports custom prompts ending in e.g. » or 尺 (#280).
    private val terminalPromptCharsKey = stringPreferencesKey("terminal_prompt_chars")
    // LANG value exported into the local proot/Android shell (#282).
    private val terminalLocaleKey = stringPreferencesKey("terminal_locale")
    private val themeKey = stringPreferencesKey("theme")
    private val sessionManagerKey = stringPreferencesKey("session_manager")
    private val reticulumRpcKeyKey = stringPreferencesKey("reticulum_rpc_key")
    private val reticulumHostKey = stringPreferencesKey("reticulum_host")
    private val reticulumPortKey = intPreferencesKey("reticulum_port")
    private val terminalColorSchemeKey = stringPreferencesKey("terminal_color_scheme")
    private val terminalAutoSwitchSchemeKey = booleanPreferencesKey("terminal_auto_switch_scheme")
    private val terminalLightColorSchemeKey = stringPreferencesKey("terminal_light_color_scheme")
    private val terminalDarkColorSchemeKey = stringPreferencesKey("terminal_dark_color_scheme")
    private val terminalApplySchemePaletteKey = booleanPreferencesKey("terminal_apply_scheme_palette")
    private val terminalBackgroundOpacityKey = floatPreferencesKey("terminal_background_opacity")
    private val toolbarRowsKey = intPreferencesKey("toolbar_rows") // legacy
    private val toolbarRow1Key = stringPreferencesKey("toolbar_row1") // legacy
    private val toolbarRow2Key = stringPreferencesKey("toolbar_row2") // legacy
    private val toolbarLayoutKey = stringPreferencesKey("toolbar_layout")
    private val snippetLibraryKey = stringPreferencesKey("snippet_library")
    private val toolbarMinButtonWidthKey = intPreferencesKey("toolbar_min_button_width")
    private val appWindowDefsKey = stringPreferencesKey("app_window_defs")
    private val appWindowDefaultResolutionKey = stringPreferencesKey("app_window_default_resolution")
    private val appWindowDefaultScaleKey = floatPreferencesKey("app_window_default_scale")
    private val navBlockModeKey = stringPreferencesKey("nav_block_mode")
    private val toolbarUniformGridKey = booleanPreferencesKey("toolbar_uniform_grid")
    private val editModeControlsPlacementKey = stringPreferencesKey("edit_mode_controls_placement")
    private val desktopKeyPlacementKey = stringPreferencesKey("desktop_key_placement")
    private val fullscreenButtonCornerKey = stringPreferencesKey("fullscreen_button_corner")
    private val rdpChipAnchorKey = stringPreferencesKey("rdp_fullscreen_chip_anchor")
    private val sessionCommandOverrideKey = stringPreferencesKey("session_command_override")
    private val sftpSortModeKey = stringPreferencesKey("sftp_sort_mode")
    private val lockTimeoutKey = stringPreferencesKey("lock_timeout")
    private val screenSecurityKey = booleanPreferencesKey("screen_security")
    private val showSearchButtonKey = booleanPreferencesKey("show_search_button")
    private val showCopyOutputButtonKey = booleanPreferencesKey("show_copy_output_button")
    private val keepScreenOnInTerminalKey = booleanPreferencesKey("keep_screen_on_in_terminal")
    private val hideNavBarInTerminalKey = booleanPreferencesKey("hide_nav_bar_in_terminal")
    private val connectionLoggingEnabledKey = booleanPreferencesKey("connection_logging_enabled")
    private val excludeFromRecentsKey = booleanPreferencesKey("exclude_from_recents")
    private val backupSyncProfileIdKey = stringPreferencesKey("backup_sync_profile_id")
    private val backupSyncPathKey = stringPreferencesKey("backup_sync_path")
    private val backupAutoSyncEnabledKey = booleanPreferencesKey("backup_auto_sync_enabled")
    private val backupAutoPullEnabledKey = booleanPreferencesKey("backup_auto_pull_enabled")
    private val backupAutoPullIntervalMinutesKey = intPreferencesKey("backup_auto_pull_interval_minutes")
    // CredentialEncryption-wrapped backup passphrase for background pushes (#359).
    private val backupSyncPassphraseKey = stringPreferencesKey("backup_sync_passphrase")
    // Session command for the Custom (X11) desktop (#361).
    private val customDesktopCommandKey = stringPreferencesKey("custom_desktop_command")
    private val mailAutomationEnabledKey = booleanPreferencesKey("mail_automation_enabled")
    private val mailDeleteToBinKey = booleanPreferencesKey("mail_delete_to_bin")
    private val alwaysShowAllTabsKey = booleanPreferencesKey("always_show_all_tabs")
    private val usbGuestExposureEnabledKey = booleanPreferencesKey("usb_guest_exposure_enabled")
    private val remoteClipboardToLocalKey = booleanPreferencesKey("remote_clipboard_to_local")
    private val verboseLoggingEnabledKey = booleanPreferencesKey("verbose_logging_enabled")
    private val mouseInputEnabledKey = booleanPreferencesKey("mouse_input_enabled")
    private val swipeArrowsModeKey = booleanPreferencesKey("swipe_arrows_mode")
    private val terminalRightClickKey = booleanPreferencesKey("terminal_right_click")
    private val allowStandardKeyboardKey = booleanPreferencesKey("allow_standard_keyboard")
    private val rawKeyboardModeKey = booleanPreferencesKey("raw_keyboard_mode")
    // Custom keyboard mode (#115 follow-up) — when on, overrides the
    // Secure/Standard inferential logic in ImeInputView and uses the
    // ime_flag_* toggles below to assemble EditorInfo manually.
    private val keyboardCustomModeKey = booleanPreferencesKey("keyboard_custom_mode")
    private val imeFlagNoSuggestionsKey = booleanPreferencesKey("ime_flag_no_suggestions")
    private val imeFlagVisiblePasswordKey = booleanPreferencesKey("ime_flag_visible_password")
    private val imeFlagAutoCorrectKey = booleanPreferencesKey("ime_flag_auto_correct")
    private val imeFlagFullEditorKey = booleanPreferencesKey("ime_flag_full_editor")
    private val imeFlagNoExtractUiKey = booleanPreferencesKey("ime_flag_no_extract_ui")
    private val imeFlagNoPersonalizedLearningKey = booleanPreferencesKey("ime_flag_no_personalized_learning")
    private val interceptCtrlShiftVKey = booleanPreferencesKey("intercept_ctrl_shift_v")
    private val reflowTerminalOnKeyboardKey = booleanPreferencesKey("reflow_terminal_on_keyboard")
    private val showTerminalTabBarKey = booleanPreferencesKey("show_terminal_tab_bar")
    private val reorderHintShownKey = booleanPreferencesKey("reorder_hint_shown")
    private val screenOrderKey = stringPreferencesKey("screen_order")
    private val sshKeyOrderKey = stringPreferencesKey("ssh_key_order")
    private val keysSortModeKey = stringPreferencesKey("keys_sort_mode")
    private val keysCollapsedSectionsKey = stringPreferencesKey("keys_collapsed_sections")
    private val waylandShellCommandKey = stringPreferencesKey("wayland_shell_command")
    private val batteryPromptDismissedKey = booleanPreferencesKey("battery_prompt_dismissed")
    private val batteryPromptNeverAskKey = booleanPreferencesKey("battery_prompt_never_ask")
    private val batteryLastKnownExemptKey = booleanPreferencesKey("battery_last_known_exempt")
    private val showLinuxVmCardKey = booleanPreferencesKey("show_linux_vm_card")
    private val showDesktopsCardKey = booleanPreferencesKey("show_desktops_card")
    private val mediaExtensionsKey = stringPreferencesKey("media_extensions")
    private val desktopInputModeKey = stringPreferencesKey("desktop_input_mode")
    // Experimental GPU stack for accelerated Wayland/cage desktops. Off (default)
    // = virgl/virpipe (GL 2.1, present works on every path). On = venus
    // (Vulkan→Mali) + zink (modern GL, ~3.2 core) with the wl_shm CPU-copy WSI
    // present path. Native Vulkan apps present; GL-via-zink present is slow. See
    // DesktopManager.gpuPassthroughEnv + project_virgl_cage_gpu_accel memory note.
    private val gpuUseVenusKey = booleanPreferencesKey("gpu_use_venus")
    // Output-only audio bridge for proot apps (#257). On = a PulseAudio daemon
    // runs in the active distro and its monitor PCM is played through AudioTrack;
    // desktop launch scripts source /etc/profile.d/pulse.sh so apps get
    // PULSE_SERVER. Off (default) = no audio, no behaviour change.
    private val audioBridgeEnabledKey = booleanPreferencesKey("audio_bridge_enabled")
    private val bandwidthAutoSuggestKey = booleanPreferencesKey("bandwidth_auto_suggest")
    private val lastMediaServerPortKey = intPreferencesKey("last_media_server_port")
    private val mcpAgentEndpointEnabledKey = booleanPreferencesKey("mcp_agent_endpoint_enabled")
    private val lastViewedAgentAuditTimestampKey = longPreferencesKey("last_viewed_agent_audit_timestamp")
    private val requireAgentConsentForWritesKey = booleanPreferencesKey("require_agent_consent_for_writes")
    // Per-tool capability gate: when off, the MCP `serve_file` tool fails
    // fast with a JSON-RPC error before any consent prompt fires. Default
    // off — agent-driven raw-file reads are a separate, opt-in capability
    // on top of the endpoint toggle.
    private val agentAllowFileReadKey = booleanPreferencesKey("agent_allow_file_read")
    // Per-tool capability gate for `queue_terminal_input` (and its
    // deprecated alias `queue_self_message`): when off, the MCP tool
    // fails fast with a JSON-RPC error before any consent prompt.
    // Power-user feature — lets the agent inject text + ENTER into
    // any connected SSH session's terminal when a configurable
    // prompt pattern appears at the tail of the scrollback. Default
    // off. The DataStore key string is the original
    // `agent_allow_queue_self_message` so existing installs that
    // already enabled this don't lose their setting across the
    // queue_self_message → queue_terminal_input rename.
    private val agentAllowTerminalInputQueueKey = booleanPreferencesKey("agent_allow_queue_self_message")
    // MCP client allowlist — clientInfo.name values the user has approved
    // via the pairing prompt on first connect. Empty by default; the
    // McpServer rejects any initialize from a name not in this set.
    private val mcpAllowedClientsKey = stringSetPreferencesKey("mcp_allowed_clients")
    // SHA-256 hashes of the per-client pairing tokens minted at pairing
    // approval (#mcp-backbone Stage 3). Entry format "<hex-hash>:<name>"
    // (the hash is fixed-length hex, so the first ':' is unambiguous).
    // Possession of the token — not the self-asserted clientInfo.name — is
    // what authenticates a client from then on.
    private val mcpClientTokenHashesKey = stringSetPreferencesKey("mcp_client_token_hashes")
    // MCP clients the user has opted into auto-approval for — per-call
    // consent prompts are skipped for any name in this set. A persistent,
    // Settings-managed counterpart to the session-only "Allow all from X
    // until restart" checkbox. Empty by default; must always be a subset
    // of mcpAllowedClients (only a paired client can be auto-approved).
    private val mcpBypassConsentClientsKey = stringSetPreferencesKey("mcp_bypass_consent_clients")
    // Profile id of the SSH connection the MCP server tunnels its
    // loopback listener back to (a dedicated, headless `-R` reverse
    // forward). Null = no dedicated tunnel; the endpoint is then only
    // reachable on-device or via a manual `adb forward`. See
    // McpTunnelManager.
    private val mcpTunnelEndpointProfileIdKey = stringPreferencesKey("mcp_tunnel_endpoint_profile_id")
    // adb-over-TCP loopback port currently exposed through the MCP reverse
    // tunnel (expose_adb), or absent if adb is not exposed. Persisted so the
    // adb -R forward is re-armed on a full tunnel rebuild, not just an
    // in-memory reconnect. See McpTunnelManager.exposeAdbPort.
    private val mcpAdbExposedPortKey = intPreferencesKey("mcp_adb_exposed_port")
    // When on, the MCP server also binds a listener on whichever WireGuard
    // tunnel is currently up (stable netstack address, reachable by WG peers
    // across roams — no reverse forward). Off by default: a WG-reachable
    // listener is a wider surface than the loopback bind. See McpServer (#176).
    private val mcpWireguardEnabledKey = booleanPreferencesKey("mcp_wireguard_enabled")
    // Bind the MCP listener on the device's Wi-Fi/LAN address too, so a
    // same-network client reaches it directly (no WG, no reverse forward).
    // Off by default — a LAN-reachable listener is a wider surface than
    // loopback, though still gated by client pairing. See McpServer.
    private val mcpLanBindEnabledKey = booleanPreferencesKey("mcp_lan_bind_enabled")
    // Auto-trust MCP clients arriving on the loopback binder (127.0.0.1):
    // skip pairing + per-action consent for local agents (adb forward /
    // on-device). On by default — loopback is already the trusted boundary
    // in the v1 threat model. LAN / WireGuard clients always keep the gate.
    private val trustLoopbackMcpClientsKey = booleanPreferencesKey("trust_loopback_mcp_clients")
    // Tunnel config id of the WireGuard tunnel to keep up as the MCP
    // carrier. When set (and mcp_wireguard_enabled), the MCP server
    // actively (re)connects this tunnel rather than passively attaching to
    // whatever WG tunnel happens to be live — so the WG-exposed endpoint
    // survives app restart / re-foreground. Empty/unset preserves the old
    // attach-to-first-live behaviour.
    private val mcpWireguardTunnelConfigIdKey = stringPreferencesKey("mcp_wireguard_tunnel_config_id")

    val biometricEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[biometricEnabledKey] ?: false
    }

    /** Prevent screenshots and screen recording (FLAG_SECURE). */
    val screenSecurity: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[screenSecurityKey] ?: false
    }

    suspend fun setScreenSecurity(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[screenSecurityKey] = enabled
        }
    }

    /** Show search button in terminal tab bar. Sends session manager's native search keys. */
    val showSearchButton: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showSearchButtonKey] ?: false
    }

    suspend fun setShowSearchButton(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showSearchButtonKey] = enabled
        }
    }

    /** Show copy-last-output button in terminal tab bar. Requires shell OSC 133 integration. */
    val showCopyOutputButton: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showCopyOutputButtonKey] ?: false
    }

    suspend fun setShowCopyOutputButton(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showCopyOutputButtonKey] = enabled
        }
    }

    /**
     * #418 debug: decode RemoteFX-Progressive WBT_TILE_UPGRADE refinement
     * tiles over RDP. Off by default — the upgrade decode is not yet verified
     * against real Windows streams, so a mis-decode could paint garbage. Meant
     * for capture verification before it becomes the default. Bridged to the
     * native decoder via `RdpDebugToggles` in HavenApp.
     */
    // #496: on by default since v5.86.40. Windows sends 500+ refinement tiles
    // per 15s even on an idle desktop, and dropping them is what produced the
    // ringing around text reported there.
    val rdpProgressiveUpgrade: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[rdpProgressiveUpgradeKey] ?: true
    }

    suspend fun setRdpProgressiveUpgrade(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rdpProgressiveUpgradeKey] = enabled
        }
    }

    /**
     * #425: advertise EGFX H.264/AVC420. Needed for KRDP (KDE's RDP server),
     * which only encodes H.264 and won't fall back to ClearCodec/RemoteFX.
     * Bridged to the native negotiation + MediaCodec decoder via
     * `RdpDebugToggles` in HavenApp. Off by default while it's verified.
     */
    val rdpAvcEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[rdpAvcEnabledKey] ?: true
    }

    suspend fun setRdpAvcEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rdpAvcEnabledKey] = enabled
        }
    }

    /** Keep the screen on while a terminal tab is foregrounded. Off by default. */
    val keepScreenOnInTerminal: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keepScreenOnInTerminalKey] ?: false
    }

    suspend fun setKeepScreenOnInTerminal(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[keepScreenOnInTerminalKey] = enabled
        }
    }

    /**
     * Hide the app's own bottom tab bar while the Terminal screen is selected
     * (#521). Only Haven's chrome: the system status and navigation bars stay
     * exactly as they are, unlike the fullscreen toggle which hides both.
     * Off by default.
     */
    val hideNavBarInTerminal: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[hideNavBarInTerminalKey] ?: false
    }

    suspend fun setHideNavBarInTerminal(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[hideNavBarInTerminalKey] = enabled
        }
    }

    /** Record connection events (connect, disconnect, errors). Off by default. */
    val connectionLoggingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[connectionLoggingEnabledKey] ?: false
    }

    suspend fun setConnectionLoggingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[connectionLoggingEnabledKey] = enabled
        }
    }

    /**
     * Hide Haven's task card from the recents screen (#239). Off by default —
     * sessions keep running either way; this only affects the recents UI
     * (ActivityManager.AppTask.setExcludeFromRecents, applied by MainActivity).
     */
    val excludeFromRecents: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[excludeFromRecentsKey] ?: false
    }

    suspend fun setExcludeFromRecents(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[excludeFromRecentsKey] = enabled
        }
    }

    /**
     * Destination for encrypted backup push/pull (#323): the id of an existing
     * connection profile, and the file path on it. Null profile = not configured.
     * The path defaults to `haven-backup.enc` when unset.
     */
    val backupSyncProfileId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[backupSyncProfileIdKey]
    }

    val backupSyncPath: Flow<String> = dataStore.data.map { prefs ->
        prefs[backupSyncPathKey] ?: "haven-backup.enc"
    }

    suspend fun setBackupSyncDestination(profileId: String?, path: String) {
        dataStore.edit { prefs ->
            if (profileId == null) prefs.remove(backupSyncProfileIdKey)
            else prefs[backupSyncProfileIdKey] = profileId
            prefs[backupSyncPathKey] = path.ifBlank { "haven-backup.enc" }
        }
    }

    /**
     * Automatic backup push (#359): when on, a background job re-pushes the
     * encrypted backup to the configured remote shortly after config changes
     * (plus a daily catch-up). Requires keeping the backup passphrase on the
     * device — stored [CredentialEncryption]-wrapped, same as connection
     * passwords — because a background job can't prompt for it. Disabling
     * deletes the stored passphrase.
     */
    val backupAutoSyncEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[backupAutoSyncEnabledKey] ?: false
    }

    suspend fun setBackupAutoSync(enabled: Boolean, passphrase: String?) {
        val encrypted = if (enabled) passphrase?.let { CredentialEncryption.encrypt(context, it) } else null
        dataStore.edit { prefs ->
            prefs[backupAutoSyncEnabledKey] = enabled
            if (enabled) {
                if (encrypted != null) {
                    prefs[backupSyncPassphraseKey] = encrypted
                }
            } else {
                val autoPullEnabled = prefs[backupAutoPullEnabledKey] ?: false
                if (!autoPullEnabled) {
                    prefs.remove(backupSyncPassphraseKey)
                }
            }
        }
    }

    val backupAutoPullEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[backupAutoPullEnabledKey] ?: false
    }

    val backupAutoPullIntervalMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[backupAutoPullIntervalMinutesKey] ?: 1440
    }

    suspend fun setBackupAutoPull(enabled: Boolean, passphrase: String?) {
        val encrypted = if (enabled) passphrase?.let { CredentialEncryption.encrypt(context, it) } else null
        dataStore.edit { prefs ->
            prefs[backupAutoPullEnabledKey] = enabled
            if (enabled) {
                if (encrypted != null) {
                    prefs[backupSyncPassphraseKey] = encrypted
                }
            } else {
                val autoSyncEnabled = prefs[backupAutoSyncEnabledKey] ?: false
                if (!autoSyncEnabled) {
                    prefs.remove(backupSyncPassphraseKey)
                }
            }
        }
    }

    suspend fun setBackupAutoPullInterval(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[backupAutoPullIntervalMinutesKey] = minutes
        }
    }

    suspend fun saveBackupSyncPassphrase(passphrase: String) {
        val encrypted = CredentialEncryption.encrypt(context, passphrase)
        dataStore.edit { prefs ->
            prefs[backupSyncPassphraseKey] = encrypted
        }
    }

    suspend fun clearBackupSyncPassphrase() {
        dataStore.edit { prefs ->
            val autoSyncEnabled = prefs[backupAutoSyncEnabledKey] ?: false
            val autoPullEnabled = prefs[backupAutoPullEnabledKey] ?: false
            if (!autoSyncEnabled && !autoPullEnabled) {
                prefs.remove(backupSyncPassphraseKey)
            }
        }
    }

    val backupSyncPassphraseFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[backupSyncPassphraseKey]?.let { decryptSyncPassphraseOrNull(it) }
    }

    /** The stored auto-sync passphrase, decrypted; null when auto-sync is off or the
     *  stored ciphertext can no longer be decrypted (e.g. the Android Keystore key
     *  didn't survive an app reinstall / device restore — Keystore keys are excluded
     *  from Android's own backup mechanism by design, so a restored DataStore file
     *  can carry ciphertext its own Keystore will never decrypt again). */
    suspend fun backupSyncPassphrase(): String? =
        dataStore.data.first()[backupSyncPassphraseKey]?.let { decryptSyncPassphraseOrNull(it) }

    private fun decryptSyncPassphraseOrNull(encrypted: String): String? =
        try {
            CredentialEncryption.decrypt(context, encrypted)
        } catch (_: Exception) {
            // Broad on purpose: a lost Keystore key surfaces as
            // GeneralSecurityException, a corrupt stored value as
            // IllegalArgumentException (Base64), and an unparseable restored
            // Tink keyset as IOException — all mean the same thing here, and
            // any of them uncaught is the same launch crash-loop. Mirrors
            // CredentialEncryption.isEncrypted's catch.
            null
        }

    /**
     * Emits the current state on collect and again on every preferences write —
     * the auto-push change signal (#359). Exposed as Flow<Unit> so callers
     * outside core/data don't need the DataStore types on their classpath.
     */
    val preferenceChanges: Flow<Unit> = dataStore.data.map { }

    /**
     * User-supplied session command for the "Custom command (X11)" desktop
     * (#361) — what runs against the Haven-owned Xvnc display in place of a
     * catalog DE's fixed startCommands (e.g. `dbus-launch startxfce4`).
     * Blank = not configured; the launch path refuses to start on blank.
     */
    val customDesktopCommand: Flow<String> = dataStore.data.map { prefs ->
        prefs[customDesktopCommandKey] ?: ""
    }

    suspend fun setCustomDesktopCommand(command: String) {
        dataStore.edit { prefs -> prefs[customDesktopCommandKey] = command.trim() }
    }

    /**
     * Master switch for inbound-email automation (Mail Rules). Off by default. While on
     * (and ≥1 enabled rule exists) Haven runs a foreground watch that polls for new mail —
     * a persistent notification + some battery cost — so it stays opt-in and one tap away
     * from off.
     */
    val mailAutomationEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mailAutomationEnabledKey] ?: false
    }

    suspend fun setMailAutomationEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[mailAutomationEnabledKey] = enabled
        }
    }

    /**
     * Whether deleting an email moves it to the Bin/Trash folder (recoverable) rather than
     * expunging it. On by default. On Gmail a plain delete already lands in Trash; this
     * matters most for plain IMAP servers where an expunge is permanent.
     */
    val mailDeleteToBin: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mailDeleteToBinKey] ?: true
    }

    suspend fun setMailDeleteToBin(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[mailDeleteToBinKey] = enabled
        }
    }

    /**
     * Show every bottom-nav tab regardless of whether the corresponding
     * resource type has any data (issue #160 follow-up). Off by default:
     * fresh installs see Connections + Settings only, and the other tabs
     * fade in as the relevant profiles / keys / sessions appear. Power
     * users with single-purpose installs can pin all tabs by turning
     * this on.
     */
    val alwaysShowAllTabs: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[alwaysShowAllTabsKey] ?: false
    }

    suspend fun setAlwaysShowAllTabs(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[alwaysShowAllTabsKey] = enabled
        }
    }

    /**
     * Master opt-in for exposing the phone's USB devices to the proot Linux
     * guest (the haven-usb proxy + shim). Default OFF: the per-call consent
     * prompt still gates each attach, but this gives the user a single
     * deliberate switch for the whole capability — guest USB lets *any* guest
     * app reach the brokered device, so it stays off until explicitly enabled.
     * Does NOT affect the direct agent USB tools (list/permission/transfer),
     * which are consent-gated on their own.
     */
    val usbGuestExposureEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[usbGuestExposureEnabledKey] ?: false
    }

    suspend fun setUsbGuestExposureEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[usbGuestExposureEnabledKey] = enabled
        }
    }

    /**
     * Whether a remote desktop's clipboard is pushed to the device clipboard.
     * Off by default: an unattended remote can otherwise inject content into
     * the phone's clipboard (security-review #15).
     */
    val remoteClipboardToLocalEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[remoteClipboardToLocalKey] ?: false
    }

    suspend fun setRemoteClipboardToLocalEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[remoteClipboardToLocalKey] = enabled
        }
    }


    /** Capture SSH protocol details (key exchange, auth, ciphers). Off by default. */
    val verboseLoggingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[verboseLoggingEnabledKey] ?: false
    }

    suspend fun setVerboseLoggingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[verboseLoggingEnabledKey] = enabled
        }
    }

    /**
     * VNC/RDP desktop input style. "DIRECT" (default) — finger position is
     * the pointer position. "TOUCHPAD" — drag moves a remote cursor
     * relatively (laptop-trackpad style), tap clicks at the *cursor*
     * position. The cursor is also auto-followed by the viewport when
     * zoomed.
     */
    val desktopInputMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[desktopInputModeKey] ?: "DIRECT"
    }

    suspend fun setDesktopInputMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[desktopInputModeKey] = mode
        }
    }

    /**
     * When true (default), VNC sessions on slow connections will surface a
     * banner suggesting a colour-depth downshift (#107). The user picks
     * "switch" or "dismiss"; nothing happens automatically beyond the
     * suggestion. Disable to silence the banner entirely.
     */
    val bandwidthAutoSuggest: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[bandwidthAutoSuggestKey] ?: true
    }

    suspend fun setBandwidthAutoSuggest(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[bandwidthAutoSuggestKey] = enabled
        }
    }

    /** Forward taps/long-press as mouse clicks to TUI apps (htop, mc, vim). */
    val mouseInputEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mouseInputEnabledKey] ?: true
    }

    suspend fun setMouseInputEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[mouseInputEnabledKey] = enabled
        }
    }

    /**
     * #524: swipes send arrow keys everywhere, not only on the alternate
     * screen — command history at a shell prompt without arrow keys on the
     * toolbar. Toggled from the toolbar's Swipe key; persisted because the
     * point is to permanently replace the four arrow-key slots.
     */
    val swipeArrowsMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[swipeArrowsModeKey] ?: false
    }

    suspend fun setSwipeArrowsMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[swipeArrowsModeKey] = enabled
        }
    }

    /** Send long-press as right-click to TUI apps instead of starting text selection. */
    val terminalRightClick: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[terminalRightClickKey] ?: false
    }

    suspend fun setTerminalRightClick(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[terminalRightClickKey] = enabled
        }
    }

    /** Use standard keyboard (voice, swipe, autocomplete) instead of secure password-style input. */
    val allowStandardKeyboard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[allowStandardKeyboardKey] ?: false
    }

    suspend fun setAllowStandardKeyboard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[allowStandardKeyboardKey] = enabled
            // Raw, Standard, and Custom are mutually exclusive; turning
            // one on automatically turns the others off so the toolbar
            // state stays consistent with the IME behaviour.
            if (enabled) {
                prefs[rawKeyboardModeKey] = false
                prefs[keyboardCustomModeKey] = false
            }
        }
    }

    /**
     * When true, the terminal returns no InputConnection at all — Gboard
     * has nothing to decorate, so its mic, suggestion strip, and AI Core
     * writing assist cannot appear. Physical keyboard input still flows
     * through `View.dispatchKeyEvent`. Soft-keyboard input comes through as
     * raw key events only; no IME composition (so no CJK).
     */
    val rawKeyboardMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[rawKeyboardModeKey] ?: false
    }

    suspend fun setRawKeyboardMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rawKeyboardModeKey] = enabled
            if (enabled) {
                prefs[allowStandardKeyboardKey] = false
                prefs[keyboardCustomModeKey] = false
            }
        }
    }

    /**
     * When on, ImeInputView ignores the Secure/Standard preset logic
     * and instead reads the six ime_flag_* toggles below to assemble
     * the EditorInfo it returns. Mutually exclusive with Standard and
     * Raw modes; turning it on clears the others.
     */
    val keyboardCustomMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keyboardCustomModeKey] ?: false
    }

    suspend fun setKeyboardCustomMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[keyboardCustomModeKey] = enabled
            if (enabled) {
                prefs[allowStandardKeyboardKey] = false
                prefs[rawKeyboardModeKey] = false
            }
        }
    }

    /**
     * Custom IME flag toggles. Defaults match the Secure preset so a
     * user flipping into Custom mode without further changes preserves
     * their current behaviour. Each flow exposes the live value; each
     * setter persists it. Effects only fire while Custom mode is on.
     */
    val imeFlagNoSuggestions: Flow<Boolean> = dataStore.data.map { it[imeFlagNoSuggestionsKey] ?: true }
    val imeFlagVisiblePassword: Flow<Boolean> = dataStore.data.map { it[imeFlagVisiblePasswordKey] ?: true }
    val imeFlagAutoCorrect: Flow<Boolean> = dataStore.data.map { it[imeFlagAutoCorrectKey] ?: false }
    val imeFlagFullEditor: Flow<Boolean> = dataStore.data.map { it[imeFlagFullEditorKey] ?: false }
    val imeFlagNoExtractUi: Flow<Boolean> = dataStore.data.map { it[imeFlagNoExtractUiKey] ?: true }
    val imeFlagNoPersonalizedLearning: Flow<Boolean> = dataStore.data.map { it[imeFlagNoPersonalizedLearningKey] ?: true }

    suspend fun setImeFlagNoSuggestions(v: Boolean) { dataStore.edit { it[imeFlagNoSuggestionsKey] = v } }
    suspend fun setImeFlagVisiblePassword(v: Boolean) { dataStore.edit { it[imeFlagVisiblePasswordKey] = v } }
    suspend fun setImeFlagAutoCorrect(v: Boolean) { dataStore.edit { it[imeFlagAutoCorrectKey] = v } }
    suspend fun setImeFlagFullEditor(v: Boolean) { dataStore.edit { it[imeFlagFullEditorKey] = v } }
    suspend fun setImeFlagNoExtractUi(v: Boolean) { dataStore.edit { it[imeFlagNoExtractUiKey] = v } }
    suspend fun setImeFlagNoPersonalizedLearning(v: Boolean) { dataStore.edit { it[imeFlagNoPersonalizedLearningKey] = v } }

    /**
     * Intercept Ctrl+Shift+V from a hardware keyboard as "paste from Android
     * clipboard". When off, the key combo is forwarded to the remote shell
     * unchanged — useful if a remote app binds Ctrl+Shift+V itself.
     */
    val interceptCtrlShiftV: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[interceptCtrlShiftVKey] ?: true
    }

    suspend fun setInterceptCtrlShiftV(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[interceptCtrlShiftVKey] = enabled
        }
    }

    /**
     * Resize the terminal (reflow / SIGWINCH) to fit above the soft keyboard
     * when it opens, instead of keeping the row count and render-shifting the
     * view up (#206). Off by default — the render-shift keeps a plain shell
     * stable when the keyboard toggles. Turn it on for a full-screen TUI that
     * draws a status/header line at the *top*: without the resize, the
     * render-shift pushes that top row off-screen (#242). The alternate screen
     * and mouse-tracking apps reflow regardless; this extends reflow to plain
     * primary-buffer TUIs the heuristics can't detect.
     */
    val reflowTerminalOnKeyboard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[reflowTerminalOnKeyboardKey] ?: false
    }

    suspend fun setReflowTerminalOnKeyboard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[reflowTerminalOnKeyboardKey] = enabled
        }
    }

    val gpuUseVenus: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[gpuUseVenusKey] ?: false
    }

    suspend fun setGpuUseVenus(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[gpuUseVenusKey] = enabled
        }
    }

    val audioBridgeEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[audioBridgeEnabledKey] ?: false
    }

    suspend fun setAudioBridgeEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[audioBridgeEnabledKey] = enabled
        }
    }

    /** Whether the terminal session tab bar is shown above the terminal. */
    val showTerminalTabBar: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showTerminalTabBarKey] ?: true
    }

    suspend fun setShowTerminalTabBar(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showTerminalTabBarKey] = enabled
        }
    }

    val reorderHintShown: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[reorderHintShownKey] ?: false
    }

    suspend fun setReorderHintShown() {
        dataStore.edit { prefs ->
            prefs[reorderHintShownKey] = true
        }
    }

    /** Comma-separated route names defining bottom navigation tab order. */
    val screenOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[screenOrderKey]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun setScreenOrder(routes: List<String>) {
        dataStore.edit { prefs ->
            prefs[screenOrderKey] = routes.joinToString(",")
        }
    }

    /** User-defined SSH-key display order as a list of key IDs. (#238) */
    val sshKeyOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[sshKeyOrderKey]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun setSshKeyOrder(ids: List<String>) {
        dataStore.edit { prefs ->
            prefs[sshKeyOrderKey] = ids.joinToString(",")
        }
    }

    /**
     * How the Keys tab sorts the SSH-key list — a `KeySort` enum name, with
     * the interpretation (including the fallback for an unknown value) owned
     * by `feature:keys`. Stored as the bare name so this module needs no
     * dependency on that enum. (#460)
     */
    val keysSortMode: Flow<String?> = dataStore.data.map { prefs ->
        prefs[keysSortModeKey]
    }

    suspend fun setKeysSortMode(name: String) {
        dataStore.edit { prefs ->
            prefs[keysSortModeKey] = name
        }
    }

    /**
     * Section ids the user has collapsed on the Keys tab (#460). Persisted
     * because the whole point is reclaiming vertical space on a small phone,
     * and a collapse that resets on every visit to the tab does not.
     */
    /**
     * Which Keys-screen sections are collapsed, or **null when the user has never touched
     * one** — which is not the same as "none collapsed" and must not be conflated with it.
     * The caller supplies the first-run default; once anything is toggled, an empty set here
     * means the user deliberately expanded everything and that choice has to survive.
     */
    val keysCollapsedSections: Flow<Set<String>?> = dataStore.data.map { prefs ->
        prefs[keysCollapsedSectionsKey]?.split(",")?.filter { it.isNotBlank() }?.toSet()
    }

    suspend fun setKeysCollapsedSections(ids: Set<String>) {
        dataStore.edit { prefs ->
            prefs[keysCollapsedSectionsKey] = ids.joinToString(",")
        }
    }

    /**
     * Whether the user has dismissed the battery optimization prompt.
     * EPISODIC (#494): cleared again when a granted exemption is detected as
     * dropped (ROMs reset it on app update), so the offer can return with an
     * explanation. [batteryPromptNeverAsk] is the permanent opt-out.
     */
    val batteryPromptDismissed: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[batteryPromptDismissedKey] ?: false
    }

    suspend fun setBatteryPromptDismissed(dismissed: Boolean = true) {
        dataStore.edit { prefs ->
            prefs[batteryPromptDismissedKey] = dismissed
        }
    }

    /** Permanent "don't ask again" for the battery prompt — survives drops (#494). */
    val batteryPromptNeverAsk: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[batteryPromptNeverAskKey] ?: false
    }

    suspend fun setBatteryPromptNeverAsk() {
        dataStore.edit { prefs ->
            prefs[batteryPromptNeverAskKey] = true
        }
    }

    /**
     * Last observed `isIgnoringBatteryOptimizations` value; null until first
     * observed. A stored true against a current false = the exemption was
     * dropped out from under us (#494).
     */
    val batteryLastKnownExempt: Flow<Boolean?> = dataStore.data.map { prefs ->
        prefs[batteryLastKnownExemptKey]
    }

    suspend fun setBatteryLastKnownExempt(exempt: Boolean) {
        dataStore.edit { prefs ->
            prefs[batteryLastKnownExemptKey] = exempt
        }
    }

    /** Whether the Linux VM card is shown on the Connections screen. */
    val showLinuxVmCard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showLinuxVmCardKey] ?: true
    }

    suspend fun setShowLinuxVmCard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showLinuxVmCardKey] = enabled
        }
    }

    /** Whether the Desktops card is shown on the Connections screen. */
    val showDesktopsCard: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showDesktopsCardKey] ?: true
    }

    suspend fun setShowDesktopsCard(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showDesktopsCardKey] = enabled
        }
    }

    /** Shell command to run in the Wayland desktop (default: /bin/sh -l). */
    val waylandShellCommand: Flow<String> = dataStore.data.map { prefs ->
        prefs[waylandShellCommandKey] ?: "/bin/sh -l"
    }

    suspend fun setWaylandShellCommand(command: String) {
        dataStore.edit { prefs ->
            prefs[waylandShellCommandKey] = command
        }
    }

    /** Space-separated file extensions to treat as streamable media (e.g. "mp3 mp4 flac"). */
    val mediaExtensions: Flow<String> = dataStore.data.map { prefs ->
        prefs[mediaExtensionsKey] ?: DEFAULT_MEDIA_EXTENSIONS
    }

    suspend fun setMediaExtensions(extensions: String) {
        dataStore.edit { prefs ->
            prefs[mediaExtensionsKey] = extensions
        }
    }

    /** Last port used by the media streaming server (for reconnection after restart). */
    val lastMediaServerPort: Flow<Int> = dataStore.data.map { prefs ->
        prefs[lastMediaServerPortKey] ?: 0
    }

    suspend fun setLastMediaServerPort(port: Int) {
        dataStore.edit { prefs ->
            prefs[lastMediaServerPortKey] = port
        }
    }

    /**
     * Whether the MCP agent endpoint server is enabled. Defaults to **false** —
     * the agent transport gives programmatic access to state an AI agent
     * or any local process can read, and must be an explicit opt-in.
     */
    val mcpAgentEndpointEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mcpAgentEndpointEnabledKey] ?: false
    }

    suspend fun setMcpAgentEndpointEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[mcpAgentEndpointEnabledKey] = enabled
        }
    }

    /**
     * Id of the SSH [sh.haven.core.data.db.entities.ConnectionProfile]
     * that the MCP server uses for its dedicated reverse tunnel. Null
     * (the default) means no dedicated tunnel is brought up when the MCP
     * endpoint is enabled. See McpTunnelManager.
     */
    val mcpTunnelEndpointProfileId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[mcpTunnelEndpointProfileIdKey]
    }

    suspend fun setMcpTunnelEndpointProfileId(profileId: String?) {
        dataStore.edit { prefs ->
            if (profileId == null) {
                prefs.remove(mcpTunnelEndpointProfileIdKey)
            } else {
                prefs[mcpTunnelEndpointProfileIdKey] = profileId
            }
        }
    }

    /**
     * Loopback adb-over-TCP port exposed through the MCP reverse tunnel, or
     * null when adb is not exposed. Set by the `expose_adb` MCP tool; read by
     * [McpTunnelManager] to re-arm the `-R` forward across tunnel rebuilds.
     */
    val mcpAdbExposedPort: Flow<Int?> = dataStore.data.map { prefs ->
        prefs[mcpAdbExposedPortKey]
    }

    suspend fun setMcpAdbExposedPort(port: Int?) {
        dataStore.edit { prefs ->
            if (port == null) {
                prefs.remove(mcpAdbExposedPortKey)
            } else {
                prefs[mcpAdbExposedPortKey] = port
            }
        }
    }

    /**
     * Whether the MCP server also exposes itself on the active WireGuard
     * tunnel's interface address. Default **true** — when a WireGuard tunnel
     * is up it is the preferred MCP transport (stable across roams, no SSH
     * `-R` re-bind collisions), so the SSH reverse tunnel stands down to a
     * fallback. Harmless with no WG tunnel up (the binder idles). Reach stays
     * gated by client pairing; widening to every WG peer is the trade for the
     * roaming-proof path.
     */
    val mcpWireguardEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mcpWireguardEnabledKey] ?: true
    }

    suspend fun setMcpWireguardEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[mcpWireguardEnabledKey] = enabled
        }
    }

    /**
     * Whether the MCP listener also binds the device's Wi-Fi/LAN address
     * (in addition to loopback), so a same-LAN client reaches it directly.
     * Off by default. Reach stays gated by client pairing.
     */
    val mcpLanBindEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mcpLanBindEnabledKey] ?: false
    }

    suspend fun setMcpLanBindEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[mcpLanBindEnabledKey] = enabled
        }
    }

    /**
     * Whether loopback (127.0.0.1) MCP clients auto-trust: skip both the
     * pairing prompt and per-action consent. OFF by default (#mcp-backbone
     * Stage 2): on stock Android any co-resident app with the INTERNET
     * permission can dial another app's loopback listener, so loopback
     * reachability alone is not proof of being this device's user. An
     * explicit opt-in for users whose on-device agents need frictionless
     * access. LAN / WireGuard / reverse-tunneled clients are unaffected and
     * always run the full gate. (#214)
     */
    val trustLoopbackMcpClients: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[trustLoopbackMcpClientsKey] ?: false
    }

    suspend fun setTrustLoopbackMcpClients(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[trustLoopbackMcpClientsKey] = enabled
        }
    }

    /**
     * Tunnel config id of the WireGuard tunnel to keep up as the MCP
     * carrier, or null when unset (attach to whatever WG tunnel is live).
     */
    val mcpWireguardTunnelConfigId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[mcpWireguardTunnelConfigIdKey]?.ifBlank { null }
    }

    suspend fun setMcpWireguardTunnelConfigId(configId: String?) {
        dataStore.edit { prefs ->
            val v = configId?.ifBlank { null }
            if (v == null) prefs.remove(mcpWireguardTunnelConfigIdKey)
            else prefs[mcpWireguardTunnelConfigIdKey] = v
        }
    }

    /**
     * Wall-clock timestamp of the most recent visit to the agent
     * activity log. The Settings badge compares this against the
     * latest event in [sh.haven.core.data.db.AgentAuditEventDao] to
     * decide whether to show an "unseen" dot.
     */
    val lastViewedAgentAuditTimestamp: Flow<Long> = dataStore.data.map { prefs ->
        prefs[lastViewedAgentAuditTimestampKey] ?: 0L
    }

    suspend fun setLastViewedAgentAuditTimestamp(timestamp: Long) {
        dataStore.edit { prefs ->
            prefs[lastViewedAgentAuditTimestampKey] = timestamp
        }
    }

    /**
     * Whether destructive agent actions (writes, deletes, uploads,
     * disconnects) must be confirmed by the user. Default **true** —
     * the §85 rule from VISION.md says the user always keeps the
     * wheel, and that means an explicit per-action gate by default.
     * No mutating MCP tools exist yet; the toggle is in place so the
     * first one that does inherits the right default.
     */
    val requireAgentConsentForWrites: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[requireAgentConsentForWritesKey] ?: true
    }

    suspend fun setRequireAgentConsentForWrites(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[requireAgentConsentForWritesKey] = enabled
        }
    }

    /**
     * Whether the MCP `serve_file` tool is enabled. Off by default —
     * the toggle is the second factor above per-call consent: even with
     * the agent endpoint enabled, raw file reads stay disabled until
     * the user explicitly opts in. The toggle gates the dispatcher
     * before any consent prompt, so a disabled call fails immediately
     * with a clear "enable in Settings" error rather than flashing a
     * deny-then-error prompt.
     */
    val agentAllowFileRead: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[agentAllowFileReadKey] ?: false
    }

    suspend fun setAgentAllowFileRead(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[agentAllowFileReadKey] = enabled
        }
    }

    /**
     * Whether the MCP `queue_terminal_input` tool is enabled (also
     * covers its deprecated alias `queue_self_message`). Off by
     * default — gives the agent a way to type text + ENTER into any
     * connected SSH session at the next matching prompt. Power-user;
     * off until the user explicitly opts in.
     */
    val agentAllowTerminalInputQueue: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[agentAllowTerminalInputQueueKey] ?: false
    }

    suspend fun setAgentAllowTerminalInputQueue(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[agentAllowTerminalInputQueueKey] = enabled
        }
    }

    /**
     * MCP clients the user has paired via the first-connect prompt
     * fired from [sh.haven.app.agent.McpServer.handleInitialize].
     * Stored by `clientInfo.name` exactly as the client sent it.
     *
     * Empty default — a fresh Haven install rejects every MCP client
     * until one is paired. The pairing decision is the security
     * boundary; `clientInfo.name` is not cryptographically unforgeable,
     * but local-app sandboxing on Android plus the explicit user-tap
     * confirmation is the threat model this set addresses.
     */
    val mcpAllowedClients: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[mcpAllowedClientsKey] ?: emptySet()
    }

    suspend fun addMcpAllowedClient(name: String) {
        if (name.isBlank()) return
        dataStore.edit { prefs ->
            prefs[mcpAllowedClientsKey] = (prefs[mcpAllowedClientsKey] ?: emptySet()) + name
        }
    }

    suspend fun removeMcpAllowedClient(name: String) {
        dataStore.edit { prefs ->
            prefs[mcpAllowedClientsKey] = (prefs[mcpAllowedClientsKey] ?: emptySet()) - name
            // Un-pairing revokes any standing auto-approval too — a client
            // that has to re-pair must re-earn the bypass.
            prefs[mcpBypassConsentClientsKey] = (prefs[mcpBypassConsentClientsKey] ?: emptySet()) - name
            // ...and its pairing token: a removed client must re-pair, not
            // walk back in with the old credential (#mcp-backbone Stage 3).
            prefs[mcpClientTokenHashesKey] = (prefs[mcpClientTokenHashesKey] ?: emptySet())
                .filterNot { it.substringAfter(':') == name }.toSet()
        }
    }

    suspend fun clearMcpAllowedClients() {
        dataStore.edit { prefs ->
            prefs.remove(mcpAllowedClientsKey)
            prefs.remove(mcpBypassConsentClientsKey)
            prefs.remove(mcpClientTokenHashesKey)
        }
    }

    /**
     * SHA-256 hex hashes of the per-client pairing tokens, keyed by client
     * name (#mcp-backbone Stage 3). Minted by the MCP server when the user
     * approves a pairing; a client proves its identity on later requests by
     * presenting the raw token (`Authorization: Bearer …`), which the server
     * hashes and matches against this map. Only the hash is ever persisted.
     */
    val mcpClientTokenHashes: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        (prefs[mcpClientTokenHashesKey] ?: emptySet()).associate { entry ->
            entry.substringAfter(':') to entry.substringBefore(':')
        }
    }

    /** Store (or rotate) the pairing-token hash for [name]. Blank name is ignored. */
    suspend fun setMcpClientTokenHash(name: String, hash: String) {
        if (name.isBlank()) return
        dataStore.edit { prefs ->
            val kept = (prefs[mcpClientTokenHashesKey] ?: emptySet())
                .filterNot { it.substringAfter(':') == name }
            prefs[mcpClientTokenHashesKey] = (kept + "$hash:$name").toSet()
        }
    }

    /**
     * Clients the user has opted into auto-approval for (Settings →
     * Agent endpoint → Paired clients → "Skip approval prompts"). Tool
     * calls from a name in this set bypass the per-call consent sheet.
     * Empty default — every paired client still prompts until the user
     * explicitly opts it in. The persistent sibling of the consent
     * sheet's session-only "Allow all from X until restart" checkbox.
     */
    val mcpBypassConsentClients: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[mcpBypassConsentClientsKey] ?: emptySet()
    }

    /**
     * Enable/disable persistent auto-approval for a paired client. A
     * blank name is ignored. Enabling a name not in [mcpAllowedClients]
     * is harmless — the dispatch path only consults this for clients
     * that already passed the pairing gate.
     */
    suspend fun setMcpClientConsentBypass(name: String, enabled: Boolean) {
        if (name.isBlank()) return
        dataStore.edit { prefs ->
            val current = prefs[mcpBypassConsentClientsKey] ?: emptySet()
            prefs[mcpBypassConsentClientsKey] = if (enabled) current + name else current - name
        }
    }

    suspend fun clearMcpBypassConsentClients() {
        dataStore.edit { prefs ->
            prefs.remove(mcpBypassConsentClientsKey)
        }
    }

    val terminalFontSize: Flow<Int> = dataStore.data.map { prefs ->
        prefs[terminalFontSizeKey] ?: DEFAULT_FONT_SIZE
    }

    /**
     * Minimum width (dp) every terminal-toolbar key is stretched to. 0 = each
     * key hugs its content; larger values give bigger, more tappable keys at
     * the cost of fitting fewer per row. Applied uniformly by the toolbar's
     * shared key primitive.
     */
    val toolbarMinButtonWidth: Flow<Int> = dataStore.data.map { prefs ->
        prefs[toolbarMinButtonWidthKey] ?: DEFAULT_TOOLBAR_MIN_BUTTON_WIDTH
    }

    /**
     * Maximum number of lines retained in each tab's scrollback ring (#151).
     * The emulator reads this once at construction; changing it affects
     * newly created tabs, not existing ones. Larger values cost roughly
     * 2 KB per line at typical column widths, multiplied by the number of
     * open tabs.
     */
    val terminalScrollbackRows: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[terminalScrollbackRowsKey] ?: DEFAULT_SCROLLBACK_ROWS)
            .coerceIn(MIN_SCROLLBACK_ROWS, MAX_SCROLLBACK_ROWS)
    }

    /**
     * Desktop size Haven asks an RDP server for.
     *
     * This was hardcoded to 1920x1080 with no way to change it, which is fine
     * until the server draws something else. A VirtualBox VM defaults to
     * 2560x1600 and simply paints at that size without announcing a resize, so
     * every update outside 1920x1080 was silently discarded and the screen went
     * stale (#422). Matching the two is the workaround; letting the client
     * follow the server is the better fix and is a separate piece of work.
     */
    val rdpDesktopWidth: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[rdpDesktopWidthKey] ?: DEFAULT_RDP_WIDTH)
            .coerceIn(MIN_RDP_DIMENSION, MAX_RDP_DIMENSION)
    }

    val rdpDesktopHeight: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[rdpDesktopHeightKey] ?: DEFAULT_RDP_HEIGHT)
            .coerceIn(MIN_RDP_DIMENSION, MAX_RDP_DIMENSION)
    }

    suspend fun setRdpDesktopSize(width: Int, height: Int) {
        dataStore.edit { prefs ->
            prefs[rdpDesktopWidthKey] = width.coerceIn(MIN_RDP_DIMENSION, MAX_RDP_DIMENSION)
            prefs[rdpDesktopHeightKey] = height.coerceIn(MIN_RDP_DIMENSION, MAX_RDP_DIMENSION)
        }
    }

    suspend fun setTerminalScrollbackRows(rows: Int) {
        dataStore.edit { prefs ->
            prefs[terminalScrollbackRowsKey] = rows.coerceIn(MIN_SCROLLBACK_ROWS, MAX_SCROLLBACK_ROWS)
        }
    }

    /**
     * Optional idle auto-stop for the local PRoot Linux guest (#409). When the
     * app has been backgrounded this many minutes, Haven stops the interactive
     * guest (local terminal sessions + desktops) to reclaim resources — unless a
     * guest service is running (an explicit "keep it up" choice). 0 = off
     * (default). Opt-in: a long job in a backgrounded terminal would be stopped
     * too, so it stays off unless the user wants idle reclamation.
     */
    val prootIdleTimeoutMinutes: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[prootIdleTimeoutMinutesKey] ?: 0).coerceAtLeast(0)
    }

    suspend fun setProotIdleTimeoutMinutes(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[prootIdleTimeoutMinutesKey] = minutes.coerceAtLeast(0)
        }
    }

    /**
     * Tap on a shell prompt's input line moves the readline cursor to the
     * tapped column by synthesising arrow-key dispatches. Requires the
     * shell to emit OSC 133 prompt markers (starship, fish 3.6+, recent
     * bash/zsh shell-integration setups). Default off — surfaces in
     * Settings → Terminal so users opt in only when their shell supports
     * it.
     */
    val terminalTapToPositionCursor: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[terminalTapToPositionCursorKey] ?: false
    }

    suspend fun setTerminalTapToPositionCursor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[terminalTapToPositionCursorKey] = enabled
        }
    }

    /**
     * Absolute path to a user-chosen TTF/OTF font that the terminal
     * should use in place of the bundled Hack font. Empty/null means
     * "use the default". The picker copies the chosen font into the
     * app's private files dir so we own the lifecycle and survive a
     * source-document permission revocation.
     */
    val terminalFontPath: Flow<String?> = dataStore.data.map { prefs ->
        prefs[terminalFontPathKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setTerminalFontPath(path: String?) {
        dataStore.edit { prefs ->
            if (path.isNullOrBlank()) prefs.remove(terminalFontPathKey)
            else prefs[terminalFontPathKey] = path
        }
    }

    /**
     * Extra characters the user treats as a shell-prompt terminator, added to
     * the built-in `$ # % > ❯` set used to detect when a queued command may be
     * sent on (re)attach (#280). Whitespace is ignored; e.g. "»尺". Empty
     * (default) keeps only the built-ins.
     */
    val terminalPromptChars: Flow<String> = dataStore.data.map { prefs ->
        prefs[terminalPromptCharsKey] ?: ""
    }

    suspend fun setTerminalPromptChars(chars: String) {
        dataStore.edit { prefs ->
            prefs[terminalPromptCharsKey] = chars
        }
    }

    /**
     * Locale exported as `LANG` into the local Linux (proot) shell and the
     * Android fallback shell (#282). Default `en_US.UTF-8`. `C.UTF-8` always
     * works without locale-gen; other locales may need generating in the guest
     * (`locale-gen`) before programs honour them. Blank falls back to the
     * default.
     */
    val terminalLocale: Flow<String> = dataStore.data.map { prefs ->
        prefs[terminalLocaleKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_TERMINAL_LOCALE
    }

    suspend fun setTerminalLocale(locale: String) {
        dataStore.edit { prefs ->
            prefs[terminalLocaleKey] = locale.trim()
        }
    }

    val sessionManager: Flow<SessionManager> = dataStore.data.map { prefs ->
        SessionManager.fromString(prefs[sessionManagerKey])
    }

    suspend fun setSessionManager(manager: SessionManager) {
        dataStore.edit { prefs ->
            prefs[sessionManagerKey] = manager.name
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[biometricEnabledKey] = enabled
        }
    }

    val theme: Flow<ThemeMode> = dataStore.data.map { prefs ->
        ThemeMode.fromString(prefs[themeKey])
    }

    suspend fun setTerminalFontSize(sizeSp: Int) {
        dataStore.edit { prefs ->
            prefs[terminalFontSizeKey] = sizeSp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        }
    }

    /**
     * Read a Float pref that a pre-v5.68.27 backup restore may have corrupted to
     * a non-Float type. That restore stored Float prefs under an Int key (#323),
     * so `prefs[floatKey]` throws ClassCastException and crash-loops the app on
     * launch. A wrong stored type returns null here (→ caller's default) instead
     * of throwing, so an already-corrupted store self-heals; the fix also
     * corrects the import so it can't recur.
     */
    private fun Preferences.floatSafe(key: Preferences.Key<Float>): Float? =
        runCatching { this[key] }.getOrNull()

    /** Mail message-list pinch-zoom factor (× the terminal font size). 1.0 = match terminal. */
    val mailFontScale: Flow<Float> = dataStore.data.map { prefs ->
        (prefs.floatSafe(mailFontScaleKey) ?: DEFAULT_MAIL_FONT_SCALE)
            .coerceIn(MIN_MAIL_FONT_SCALE, MAX_MAIL_FONT_SCALE)
    }

    suspend fun setMailFontScale(scale: Float) {
        dataStore.edit { prefs ->
            prefs[mailFontScaleKey] = scale.coerceIn(MIN_MAIL_FONT_SCALE, MAX_MAIL_FONT_SCALE)
        }
    }

    suspend fun setToolbarMinButtonWidth(dp: Int) {
        dataStore.edit { prefs ->
            prefs[toolbarMinButtonWidthKey] =
                dp.coerceIn(MIN_TOOLBAR_MIN_BUTTON_WIDTH, MAX_TOOLBAR_MIN_BUTTON_WIDTH)
        }
    }

    suspend fun setTheme(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[themeKey] = mode.name
        }
    }

    val reticulumRpcKey: Flow<String?> = dataStore.data.map { prefs ->
        prefs[reticulumRpcKeyKey]
    }

    val reticulumHost: Flow<String> = dataStore.data.map { prefs ->
        prefs[reticulumHostKey] ?: DEFAULT_RETICULUM_HOST
    }

    val reticulumPort: Flow<Int> = dataStore.data.map { prefs ->
        prefs[reticulumPortKey] ?: DEFAULT_RETICULUM_PORT
    }

    val reticulumConfigured: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[reticulumRpcKeyKey] != null
    }

    suspend fun setReticulumConfig(rpcKey: String, host: String, port: Int) {
        dataStore.edit { prefs ->
            prefs[reticulumRpcKeyKey] = rpcKey
            prefs[reticulumHostKey] = host
            prefs[reticulumPortKey] = port
        }
    }

    suspend fun clearReticulumConfig() {
        dataStore.edit { prefs ->
            prefs.remove(reticulumRpcKeyKey)
        }
    }

    /**
     * Toolbar layout as a [ToolbarLayout]. Migrates from legacy row1/row2
     * comma-separated format on first read if needed.
     */
    val toolbarLayout: Flow<ToolbarLayout> = dataStore.data.map { prefs ->
        val json = prefs[toolbarLayoutKey]
        if (json != null) {
            ToolbarLayout.fromJson(json)
        } else {
            // Migrate from legacy formats
            val row1 = prefs[toolbarRow1Key]
            val row2 = prefs[toolbarRow2Key]
            if (row1 != null || row2 != null) {
                ToolbarLayout.fromLegacy(
                    row1 ?: DEFAULT_TOOLBAR_ROW1,
                    row2 ?: DEFAULT_TOOLBAR_ROW2,
                )
            } else {
                ToolbarLayout.DEFAULT
            }
        }
    }

    val toolbarLayoutJson: Flow<String> = dataStore.data.map { prefs ->
        prefs[toolbarLayoutKey] ?: ToolbarLayout.DEFAULT.toJson()
    }

    suspend fun setToolbarLayout(layout: ToolbarLayout) {
        dataStore.edit { prefs ->
            prefs[toolbarLayoutKey] = layout.toJson()
            // Clear legacy keys
            prefs.remove(toolbarRow1Key)
            prefs.remove(toolbarRow2Key)
            prefs.remove(toolbarRowsKey)
        }
    }

    suspend fun setToolbarLayoutJson(json: String) {
        dataStore.edit { prefs ->
            prefs[toolbarLayoutKey] = json
            prefs.remove(toolbarRow1Key)
            prefs.remove(toolbarRow2Key)
            prefs.remove(toolbarRowsKey)
        }
    }

    /**
     * Off-toolbar snippet library (#244): custom send-key macros that exist
     * and show in the scissors sheet but have no dedicated toolbar button.
     * Separate from [toolbarLayout] so toggling a snippet "Off" in settings
     * keeps it here instead of discarding it.
     */
    val snippetLibrary: Flow<List<ToolbarItem.Custom>> = dataStore.data.map { prefs ->
        SnippetOps.libraryFromJson(prefs[snippetLibraryKey] ?: "")
    }

    suspend fun setSnippetLibrary(items: List<ToolbarItem.Custom>) {
        dataStore.edit { prefs ->
            prefs[snippetLibraryKey] = SnippetOps.libraryToJson(items)
        }
    }

    // --- Saved app windows (single-app cage kiosks; see AppWindowDefList) ---
    // Shared by the Desktop-settings UI (user-defined) and McpTools.present_app
    // (records the agent's launches so they're restartable). Both already
    // inject this repository, so a DataStore-JSON list keeps it in one place.

    val appWindowDefs: Flow<AppWindowDefList> = dataStore.data.map { prefs ->
        prefs[appWindowDefsKey]?.let { AppWindowDefList.fromJson(it) } ?: AppWindowDefList.EMPTY
    }

    /**
     * Add a saved app window, or — if one with the same [command] exists —
     * bump its `lastUsed` (and refresh its label when [label] is non-blank).
     * The existing entry's [AppWindowOrigin] is preserved, so re-launching a
     * user entry doesn't demote it to "agent" and vice-versa. Read-modify-
     * write inside a single `edit` so concurrent upserts don't clobber.
     */
    suspend fun upsertAppWindowDef(
        label: String,
        command: String,
        createdBy: AppWindowOrigin,
        fullscreen: Boolean = false,
        resolution: String? = null,
        scale: Float? = null,
        runAsRoot: Boolean? = null,
        multiWindow: Boolean? = null,
        swayRules: List<String>? = null,
    ) {
        dataStore.edit { prefs ->
            val current = prefs[appWindowDefsKey]?.let { AppWindowDefList.fromJson(it) }
                ?: AppWindowDefList.EMPTY
            val now = System.currentTimeMillis()
            val items = if (current.items.any { it.command == command }) {
                // Preserve an existing entry's resolution/scale/runAsRoot unless
                // explicitly given (launching shouldn't bake defaults into the def).
                current.items.map {
                    if (it.command == command) {
                        it.copy(
                            lastUsed = now,
                            label = label.ifBlank { it.label },
                            fullscreen = fullscreen,
                            resolution = resolution ?: it.resolution,
                            scale = scale ?: it.scale,
                            runAsRoot = runAsRoot ?: it.runAsRoot,
                            multiWindow = multiWindow ?: it.multiWindow,
                            swayRules = swayRules ?: it.swayRules,
                        )
                    } else it
                }
            } else {
                current.items + AppWindowDef(
                    label = label.ifBlank { command },
                    command = command,
                    createdBy = createdBy,
                    lastUsed = now,
                    fullscreen = fullscreen,
                    resolution = resolution,
                    scale = scale,
                    runAsRoot = runAsRoot ?: false,
                    multiWindow = multiWindow ?: false,
                    swayRules = swayRules ?: emptyList(),
                )
            }
            prefs[appWindowDefsKey] = AppWindowDefList(items).toJson()
        }
    }

    suspend fun deleteAppWindowDef(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[appWindowDefsKey]?.let { AppWindowDefList.fromJson(it) }
                ?: AppWindowDefList.EMPTY
            prefs[appWindowDefsKey] =
                AppWindowDefList(current.items.filterNot { it.id == id }).toJson()
        }
    }

    /**
     * Edit an existing entry's [label] and [command] in place, keyed by [id].
     * Unlike [upsertAppWindowDef] (which matches by command and so can't change
     * one), this targets the id, so the user can rename both fields. The
     * entry's [AppWindowOrigin] is preserved; [lastUsed] is refreshed. No-op
     * if no entry has that id.
     */
    suspend fun updateAppWindowDef(
        id: String,
        label: String,
        command: String,
        fullscreen: Boolean = false,
        resolution: String? = null,
        scale: Float? = null,
        runAsRoot: Boolean = false,
    ) {
        dataStore.edit { prefs ->
            val current = prefs[appWindowDefsKey]?.let { AppWindowDefList.fromJson(it) }
                ?: AppWindowDefList.EMPTY
            val now = System.currentTimeMillis()
            // Full replace from the edit dialog: resolution/scale null = "use the
            // global default" (a real value), so these are set directly, not merged.
            val items = current.items.map {
                if (it.id == id) {
                    it.copy(
                        label = label.ifBlank { command },
                        command = command,
                        lastUsed = now,
                        fullscreen = fullscreen,
                        resolution = resolution,
                        scale = scale,
                        runAsRoot = runAsRoot,
                    )
                } else it
            }
            prefs[appWindowDefsKey] = AppWindowDefList(items).toJson()
        }
    }

    /** Global default cage resolution for app windows that don't set their own. */
    val appWindowDefaultResolution: Flow<String> = dataStore.data.map { prefs ->
        prefs[appWindowDefaultResolutionKey] ?: "auto"
    }

    suspend fun setAppWindowDefaultResolution(resolution: String) {
        dataStore.edit { it[appWindowDefaultResolutionKey] = resolution }
    }

    /** Global default cage output scale for app windows that don't set their own. */
    val appWindowDefaultScale: Flow<Float> = dataStore.data.map { prefs ->
        prefs.floatSafe(appWindowDefaultScaleKey) ?: 1f
    }

    suspend fun setAppWindowDefaultScale(scale: Float) {
        dataStore.edit { it[appWindowDefaultScaleKey] = scale }
    }

    val navBlockMode: Flow<NavBlockMode> = dataStore.data.map { prefs ->
        prefs[navBlockModeKey]?.let { NavBlockMode.fromId(it) } ?: NavBlockMode.ALIGNED
    }

    suspend fun setNavBlockMode(mode: NavBlockMode) {
        dataStore.edit { prefs ->
            prefs[navBlockModeKey] = mode.id
        }
    }

    /**
     * Termux-style uniform key grid (#372): every toolbar key occupies an
     * equal-width cell of the screen (no horizontal scroll); long labels wrap
     * inside their cell. Off = the classic natural-width scrolling rows.
     */
    val toolbarUniformGrid: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[toolbarUniformGridKey] ?: false
    }

    suspend fun setToolbarUniformGrid(enabled: Boolean) {
        dataStore.edit { it[toolbarUniformGridKey] = enabled }
    }

    /** Where the fixed (non-draggable) controls sit in toolbar edit mode. (#224) */
    val editModeControlsPlacement: Flow<EditModeControlsPlacement> = dataStore.data.map { prefs ->
        prefs[editModeControlsPlacementKey]?.let { EditModeControlsPlacement.fromId(it) }
            ?: EditModeControlsPlacement.LEFT
    }

    suspend fun setEditModeControlsPlacement(placement: EditModeControlsPlacement) {
        dataStore.edit { prefs ->
            prefs[editModeControlsPlacementKey] = placement.id
        }
    }

    /** Where the auto-shown desktop (VNC/RDP) key sits, or whether it's hidden. (#245) */
    val desktopKeyPlacement: Flow<DesktopKeyPlacement> = dataStore.data.map { prefs ->
        prefs[desktopKeyPlacementKey]?.let { DesktopKeyPlacement.fromId(it) }
            ?: DesktopKeyPlacement.LEFT
    }

    suspend fun setDesktopKeyPlacement(placement: DesktopKeyPlacement) {
        dataStore.edit { prefs ->
            prefs[desktopKeyPlacementKey] = placement.id
        }
    }

    /** Corner of the terminal view holding the fullscreen toggle (#445). */
    val fullscreenButtonCorner: Flow<FullscreenButtonCorner> = dataStore.data.map { prefs ->
        prefs[fullscreenButtonCornerKey]?.let { FullscreenButtonCorner.fromId(it) }
            ?: FullscreenButtonCorner.DEFAULT
    }

    suspend fun setFullscreenButtonCorner(corner: FullscreenButtonCorner) {
        dataStore.edit { prefs ->
            prefs[fullscreenButtonCornerKey] = corner.id
        }
    }

    /**
     * Anchor of the fullscreen session-menu chip in remote-desktop sessions
     * (#528 follow-up) — hold-and-drag to move it, same idiom as the terminal
     * fullscreen button above, persisted across sessions.
     */
    val rdpChipAnchor: Flow<RdpChipAnchor> = dataStore.data.map { prefs ->
        prefs[rdpChipAnchorKey]?.let { RdpChipAnchor.fromId(it) } ?: RdpChipAnchor.DEFAULT
    }

    suspend fun setRdpChipAnchor(anchor: RdpChipAnchor) {
        dataStore.edit { prefs ->
            prefs[rdpChipAnchorKey] = anchor.id
        }
    }

    /**
     * User override for the session manager command template.
     * If non-null, replaces the built-in command. Use {name} for session name.
     */
    val sessionCommandOverride: Flow<String?> = dataStore.data.map { prefs ->
        prefs[sessionCommandOverrideKey]
    }

    suspend fun setSessionCommandOverride(command: String?) {
        dataStore.edit { prefs ->
            if (command.isNullOrBlank()) {
                prefs.remove(sessionCommandOverrideKey)
            } else {
                prefs[sessionCommandOverrideKey] = command
            }
        }
    }

    val sftpSortMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[sftpSortModeKey] ?: "NAME_ASC"
    }

    suspend fun setSftpSortMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[sftpSortModeKey] = mode
        }
    }

    val terminalColorScheme: Flow<TerminalColorScheme> = dataStore.data.map { prefs ->
        TerminalColorScheme.fromString(prefs[terminalColorSchemeKey])
    }

    suspend fun setTerminalColorScheme(scheme: TerminalColorScheme) {
        dataStore.edit { prefs ->
            prefs[terminalColorSchemeKey] = scheme.name
        }
    }

    /**
     * When true, the active terminal scheme follows the system light/dark
     * mode — [terminalLightColorScheme] in light, [terminalDarkColorScheme]
     * in dark. The plain [terminalColorScheme] pref is used otherwise.
     */
    val terminalAutoSwitchScheme: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[terminalAutoSwitchSchemeKey] ?: false
    }

    suspend fun setTerminalAutoSwitchScheme(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[terminalAutoSwitchSchemeKey] = enabled
        }
    }

    val terminalLightColorScheme: Flow<TerminalColorScheme> = dataStore.data.map { prefs ->
        prefs[terminalLightColorSchemeKey]?.let { name ->
            TerminalColorScheme.entries.find { it.name == name }
        } ?: TerminalColorScheme.LIGHT
    }

    suspend fun setTerminalLightColorScheme(scheme: TerminalColorScheme) {
        dataStore.edit { prefs ->
            prefs[terminalLightColorSchemeKey] = scheme.name
        }
    }

    val terminalDarkColorScheme: Flow<TerminalColorScheme> = dataStore.data.map { prefs ->
        prefs[terminalDarkColorSchemeKey]?.let { name ->
            TerminalColorScheme.entries.find { it.name == name }
        } ?: TerminalColorScheme.HAVEN
    }

    suspend fun setTerminalDarkColorScheme(scheme: TerminalColorScheme) {
        dataStore.edit { prefs ->
            prefs[terminalDarkColorSchemeKey] = scheme.name
        }
    }

    /**
     * When true, the active scheme's 16-entry [TerminalColorScheme.ansi]
     * palette is pushed to the emulator, so SGR-coloured text tracks the
     * theme. Off by default (#407): overriding the stock ANSI palette
     * remaps the colours full-screen TUIs like mutt rely on (e.g. ANSI
     * white → a scheme "cream"), which reads as a regression. Off keeps
     * libvterm's canonical palette; only the default fg/bg are themed.
     */
    val terminalApplySchemePalette: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[terminalApplySchemePaletteKey] ?: false
    }

    suspend fun setTerminalApplySchemePalette(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[terminalApplySchemePaletteKey] = enabled
        }
    }

    /**
     * Global terminal background opacity (0.0–1.0). 1.0 = opaque (default).
     * Below 1.0 the terminal renders semi-transparently over the device
     * wallpaper. A per-profile override may supersede this (see
     * `ConnectionProfile.terminalBackgroundOpacity`).
     */
    val terminalBackgroundOpacity: Flow<Float> = dataStore.data.map { prefs ->
        (prefs.floatSafe(terminalBackgroundOpacityKey) ?: 1f).coerceIn(0f, 1f)
    }

    suspend fun setTerminalBackgroundOpacity(opacity: Float) {
        dataStore.edit { prefs ->
            prefs[terminalBackgroundOpacityKey] = opacity.coerceIn(0f, 1f)
        }
    }

    enum class TerminalColorScheme(
        val label: String,
        val background: Long,
        val foreground: Long,
        /**
         * 16-entry ANSI palette in libvterm order:
         *   0..7  = normal  black, red, green, yellow, blue, magenta, cyan, white
         *   8..15 = bright  black, red, green, yellow, blue, magenta, cyan, white
         * Passed to the emulator via `applyColorScheme` so SGR-coloured text
         * (most prompts) tracks the scheme, not libvterm's stock palette.
         */
        val ansi: LongArray,
    ) {
        /**
         * Sentinel scheme — actual fg/bg come from the live
         * `MaterialTheme.colorScheme` (surface / onSurface). The fixed
         * longs here are sane fallbacks for code paths that can't reach
         * MaterialTheme (e.g. emulator construction in
         * `TerminalViewModel`); the Compose layer overrides immediately
         * via `setDefaultColors`. Call sites that *can* reach the
         * theme should check [isDynamic] and use the live colours.
         *
         * The palette is a static neutral fallback (Catppuccin Mocha); a
         * future improvement could derive it from the live Material 3
         * tonal palettes.
         */
        MATERIAL_YOU(
            "Material You", 0xFF1A1A1A, 0xFFE0E0E0,
            longArrayOf(
                0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
                0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFBAC2DE,
                0xFF585B70, 0xFFEBA0AC, 0xFFB1E3AB, 0xFFFCEAB5,
                0xFF96BDFD, 0xFFF7CEEC, 0xFF9EE9DC, 0xFFA6ADC8,
            ),
        ),
        HAVEN(
            "Haven", 0xFF1A1A2E, 0xFF00E676,
            longArrayOf(
                0xFF1A1A2E, 0xFFFF5C8A, 0xFF00E676, 0xFFFFD54F,
                0xFF4FC3F7, 0xFFCE93D8, 0xFF80DEEA, 0xFFB0BEC5,
                0xFF424242, 0xFFFF8A80, 0xFF69F0AE, 0xFFFFE082,
                0xFF82B1FF, 0xFFE1BEE7, 0xFFA7FFEB, 0xFFECEFF1,
            ),
        ),
        CLASSIC_GREEN(
            "Classic Green", 0xFF000000, 0xFF00FF00,
            longArrayOf(
                0xFF000000, 0xFF008800, 0xFF00BB00, 0xFF00CC00,
                0xFF005500, 0xFF007733, 0xFF009966, 0xFF00BB00,
                0xFF004400, 0xFF00CC00, 0xFF00FF00, 0xFF55FF55,
                0xFF007722, 0xFF00CC66, 0xFF00FFAA, 0xFF66FF66,
            ),
        ),
        LIGHT(
            "Light", 0xFFFFFFFF, 0xFF1A1A1A,
            longArrayOf(
                0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
                0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
                0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
                0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
            ),
        ),
        SOLARIZED_DARK(
            "Solarized Dark", 0xFF002B36, 0xFF839496,
            longArrayOf(
                0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
                0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
                0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
                0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
            ),
        ),
        DRACULA(
            "Dracula", 0xFF282A36, 0xFFF8F8F2,
            longArrayOf(
                0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C,
                0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
                0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5,
                0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF,
            ),
        ),
        MONOKAI(
            "Monokai", 0xFF272822, 0xFFF8F8F2,
            longArrayOf(
                0xFF272822, 0xFFF92672, 0xFFA6E22E, 0xFFF4BF75,
                0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF8F8F2,
                0xFF75715E, 0xFFF92672, 0xFFA6E22E, 0xFFE6DB74,
                0xFF66D9EF, 0xFFAE81FF, 0xFFA1EFE4, 0xFFF9F8F5,
            ),
        ),
        NORD(
            "Nord", 0xFF2E3440, 0xFFD8DEE9,
            longArrayOf(
                0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
                0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
                0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
                0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4,
            ),
        ),
        GRUVBOX(
            "Gruvbox", 0xFF282828, 0xFFEBDBB2,
            longArrayOf(
                0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921,
                0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
                0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F,
                0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2,
            ),
        ),
        TOKYO_NIGHT(
            "Tokyo Night", 0xFF1A1B26, 0xFFA9B1D6,
            longArrayOf(
                0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
                0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
                0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
                0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5,
            ),
        ),
        QBASIC(
            "QBasic", 0xFF0000AA, 0xFFAAAAAA,
            longArrayOf(
                0xFF000000, 0xFFAA0000, 0xFF00AA00, 0xFFAA5500,
                0xFF0000AA, 0xFFAA00AA, 0xFF00AAAA, 0xFFAAAAAA,
                0xFF555555, 0xFFFF5555, 0xFF55FF55, 0xFFFFFF55,
                0xFF5555FF, 0xFFFF55FF, 0xFF55FFFF, 0xFFFFFFFF,
            ),
        ),
        AMBER(
            "Amber", 0xFF1A1000, 0xFFFFB000,
            longArrayOf(
                0xFF1A1000, 0xFF884400, 0xFFAA6600, 0xFFCC7700,
                0xFF553300, 0xFF884422, 0xFFAA5511, 0xFFCC8833,
                0xFF443300, 0xFFCC6600, 0xFFDD8822, 0xFFFFB000,
                0xFF775500, 0xFFCC7733, 0xFFDD9944, 0xFFFFCC66,
            ),
        ),
        PINK(
            "Pink", 0xFF2D001E, 0xFFFF9EC6,
            longArrayOf(
                0xFF2D001E, 0xFFFF4081, 0xFFFFAB91, 0xFFFFD180,
                0xFFCE93D8, 0xFFFF80AB, 0xFFFFB2DD, 0xFFFFCCDD,
                0xFF550022, 0xFFFF80AB, 0xFFFFCCBC, 0xFFFFE0B2,
                0xFFE1BEE7, 0xFFFFB6C1, 0xFFFFD8E8, 0xFFFFE6F0,
            ),
        ),
        LAVENDER(
            "Lavender", 0xFF1E1629, 0xFFCDB4DB,
            longArrayOf(
                0xFF1E1629, 0xFFE57373, 0xFFB39DDB, 0xFFFFCC80,
                0xFF9575CD, 0xFFCE93D8, 0xFFB0BEC5, 0xFFCDB4DB,
                0xFF4A3F61, 0xFFFF8A80, 0xFFD1C4E9, 0xFFFFE082,
                0xFFB39DDB, 0xFFE1BEE7, 0xFFCFD8DC, 0xFFEDE7F6,
            ),
        ),
        OCEAN(
            "Ocean", 0xFF0A192F, 0xFF64FFDA,
            longArrayOf(
                0xFF0A192F, 0xFFFF6E6E, 0xFF64FFDA, 0xFFFFD180,
                0xFF82B1FF, 0xFF80D8FF, 0xFF18FFFF, 0xFFCFD8DC,
                0xFF1F3A5F, 0xFFFF8A80, 0xFFA7FFEB, 0xFFFFE082,
                0xFF8C9EFF, 0xFFB388FF, 0xFF84FFFF, 0xFFECEFF1,
            ),
        ),

        // #516: three schemes whose default foreground is a plain grey rather
        // than a tinted one. Palettes transcribed from upstream, not eyeballed:
        // Campbell from microsoft/terminal's TerminalSettingsModel/defaults.json,
        // the two Modern ones from microsoft/vscode's terminalColorRegistry.ts
        // (`ansiColorMap` light/dark defaults) with fg/bg from the matching
        // light_modern.json / dark_modern.json.
        CAMPBELL(
            "Campbell", 0xFF0C0C0C, 0xFFCCCCCC,
            longArrayOf(
                0xFF0C0C0C, 0xFFC50F1F, 0xFF13A10E, 0xFFC19C00,
                0xFF0037DA, 0xFF881798, 0xFF3A96DD, 0xFFCCCCCC,
                0xFF767676, 0xFFE74856, 0xFF16C60C, 0xFFF9F1A5,
                0xFF3B78FF, 0xFFB4009E, 0xFF61D6D6, 0xFFF2F2F2,
            ),
        ),
        MODERN_DARK(
            "Modern Dark", 0xFF1F1F1F, 0xFFCCCCCC,
            longArrayOf(
                0xFF000000, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510,
                0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD, 0xFFE5E5E5,
                0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543,
                0xFF3B8EEA, 0xFFD670D6, 0xFF29B8DB, 0xFFE5E5E5,
            ),
        ),
        MODERN_LIGHT(
            "Modern Light", 0xFFFFFFFF, 0xFF3B3B3B,
            longArrayOf(
                0xFF000000, 0xFFCD3131, 0xFF107C10, 0xFF949800,
                0xFF0451A5, 0xFFBC05BC, 0xFF0598BC, 0xFF555555,
                0xFF666666, 0xFFCD3131, 0xFF14CE14, 0xFFB5BA00,
                0xFF0451A5, 0xFFBC05BC, 0xFF0598BC, 0xFFA5A5A5,
            ),
        );

        /** True when fg/bg should be sourced from the live system theme rather than the enum's static longs. */
        val isDynamic: Boolean get() = this == MATERIAL_YOU

        /** Snapshot of [ansi] as a 16-entry ARGB IntArray, ready for `applyColorScheme`. */
        fun ansiPaletteArgb(): IntArray = IntArray(16) { ansi[it].toInt() }

        companion object {
            fun fromString(value: String?): TerminalColorScheme =
                entries.find { it.name == value } ?: HAVEN
        }
    }

    val lockTimeout: Flow<LockTimeout> = dataStore.data.map { prefs ->
        LockTimeout.fromString(prefs[lockTimeoutKey])
    }

    suspend fun setLockTimeout(timeout: LockTimeout) {
        dataStore.edit { prefs ->
            prefs[lockTimeoutKey] = timeout.name
        }
    }

    enum class LockTimeout(val label: String, val seconds: Long) {
        IMMEDIATE("Immediately", 0),
        THIRTY_SECONDS("30 seconds", 30),
        ONE_MINUTE("1 minute", 60),
        FIVE_MINUTES("5 minutes", 300),
        NEVER("Never", Long.MAX_VALUE);

        companion object {
            fun fromString(value: String?): LockTimeout =
                entries.find { it.name == value } ?: IMMEDIATE
        }
    }

    enum class ThemeMode(val label: String) {
        SYSTEM("System default"),
        LIGHT("Light"),
        DARK("Dark");

        companion object {
            fun fromString(value: String?): ThemeMode =
                entries.find { it.name == value } ?: SYSTEM
        }
    }

    enum class SessionManager(
        val label: String,
        val url: String?,
        val command: ((String) -> String)?,
        val supportsScrollback: Boolean = true,
    ) {
        NONE("None", null, null, supportsScrollback = false),
        TMUX("tmux", "https://github.com/tmux/tmux/wiki", { name -> "tmux new-session -A -s $name \\; set -gq allow-passthrough on \\; set -gq mouse on" }),
        ZELLIJ("zellij", "https://zellij.dev", { name -> "zellij attach $name --create" }),
        SCREEN("screen", "https://www.gnu.org/software/screen/", { name -> "screen -dRR $name" }, supportsScrollback = false),
        BYOBU("byobu", "https://www.byobu.org", { name -> "byobu new-session -A -s $name \\; set -gq mouse on" });

        companion object {
            fun fromString(value: String?): SessionManager =
                entries.find { it.name == value } ?: NONE
        }
    }

    companion object {
        const val DEFAULT_FONT_SIZE = 14
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 32
        // Mail list pinch-zoom: 75% of the terminal font size up to 300%.
        const val DEFAULT_MAIL_FONT_SCALE = 1.0f
        const val MIN_MAIL_FONT_SCALE = 0.75f
        const val MAX_MAIL_FONT_SCALE = 3.0f
        // Terminal toolbar minimum key width (dp). Default 0 = keys hug content.
        const val DEFAULT_TOOLBAR_MIN_BUTTON_WIDTH = 0
        const val MIN_TOOLBAR_MIN_BUTTON_WIDTH = 0
        const val MAX_TOOLBAR_MIN_BUTTON_WIDTH = 64
        /** What Haven asked RDP servers for before it was configurable (#422). */
        const val DEFAULT_RDP_WIDTH = 1920
        const val DEFAULT_RDP_HEIGHT = 1080

        /** RDP carries the desktop size as a u16, and a 1x1 desktop is not useful. */
        const val MIN_RDP_DIMENSION = 640
        const val MAX_RDP_DIMENSION = 8192

        const val DEFAULT_SCROLLBACK_ROWS = 1000
        const val MIN_SCROLLBACK_ROWS = 100
        const val MAX_SCROLLBACK_ROWS = 25000
        /** Suggested presets surfaced by the Settings UI. */
        val SCROLLBACK_ROWS_PRESETS = listOf(1000, 5000, 10000, 25000)
        const val DEFAULT_TOOLBAR_ROWS = 2 // legacy
        const val DEFAULT_RETICULUM_HOST = "127.0.0.1"
        const val DEFAULT_RETICULUM_PORT = 37428
        const val DEFAULT_TOOLBAR_ROW1 = "keyboard,esc,tab,shift,ctrl,alt" // legacy
        const val DEFAULT_TOOLBAR_ROW2 = "arrow_left,arrow_up,arrow_down,arrow_right,sym_pipe,sym_tilde,sym_slash,sym_backslash,sym_backtick" // legacy
        const val DEFAULT_MEDIA_EXTENSIONS = "mp3 flac ogg opus m4a aac wma wav aiff alac ape mka mp4 mkv avi mov wmv flv webm m4v ts mpg mpeg 3gp"
        const val DEFAULT_TERMINAL_LOCALE = "en_US.UTF-8"
    }
}
