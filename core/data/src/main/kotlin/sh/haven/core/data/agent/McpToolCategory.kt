package sh.haven.core.data.agent

/**
 * Grouping for the per-tool MCP switches screen (#755 follow-up): ~230 tools in
 * one flat list is as hard to steer as the `tools/list` dump the switches
 * exist to shrink, so Settings groups them by subsystem and lets a whole
 * category be switched off at once. [OTHER] is the fallback for any tool the
 * map below has not been updated for — it never disappears from the screen,
 * only loses its home.
 *
 * Membership is keyed by tool name in [of] rather than declared on the
 * handlers themselves: the registry lives in `:app` (which this module cannot
 * depend on) and the grouping is a presentation concern, so the name→category
 * map stays here where both the dispatcher's UI and its unit tests can see it.
 */
enum class McpToolCategory {
    TERMINAL,
    CONNECTIONS,
    FILES,
    DESKTOP,
    GUEST,
    USB,
    KEYS,
    MAIL,
    RCLONE,
    SENSES,
    AGENT,
    OTHER,
    ;

    companion object {
        private val byName: Map<String, McpToolCategory> = listOf(
            TERMINAL to listOf(
                "await_turn", "copy_selection", "drag_selection_to", "drag_terminal",
                "extend_selection", "feed_terminal_output", "focus_terminal_session",
                "get_selection", "list_sessions", "list_snippets", "open_local_shell",
                "queue_self_message", "queue_terminal_input", "read_clipboard",
                "read_exited_session", "read_last_turn", "read_terminal_scrollback",
                "read_terminal_snapshot", "scroll_terminal", "search_terminal",
                "send_terminal_input", "send_to_agent", "set_compose_mode", "set_snippet",
                "set_terminal_font_from_url", "start_selection", "tap_terminal",
                "write_clipboard",
            ),
            CONNECTIONS to listOf(
                "add_port_forward", "answer_auth_prompt", "answer_session_picker",
                "compose_workspace", "connect_profile", "create_connection",
                "create_tunnel", "delete_connection", "delete_tunnel",
                "disconnect_profile", "get_connection_log",
                "get_pending_auth_prompt", "get_pending_session_picker",
                "list_connections", "list_live_tunnels", "list_tunnels",
                "list_workspaces", "openai_chat", "openai_list_models",
                "remove_port_forward", "run_command",
                "save_workspace", "set_port_knock", "set_profile_routing", "set_spa",
                "test_port_knock", "test_spa", "update_connection",
            ),
            FILES to listOf(
                "convert_file", "delete_file", "delete_sftp_file", "list_directory",
                "list_sftp_directory", "mirror_directory_with_fallback",
                "navigate_sftp_browser", "open_convert_dialog_with_args",
                "open_file_in_editor", "read_watch", "serve_file", "stop_stream",
                "stop_watch_directory", "stream_sftp_file", "upload_file",
                "upload_file_to_sftp",
                "watch_directory",
            ),
            DESKTOP to listOf(
                "capture_desktop", "capture_desktop_tab", "drag_desktop_tab", "gl_smoke_test",
                "import_system_vm_image", "inspect_desktop_tab", "install_desktop",
                "launch_app_in_desktop", "list_desktop_environments", "list_desktop_sessions",
                "list_desktop_windows", "list_guest_apps", "list_system_vm_images",
                "move_desktop_mouse", "open_desktop_terminal", "read_desktop_log",
                "scroll_desktop_tab", "send_desktop_clipboard", "start_desktop",
                "start_system_vm", "stop_desktop", "stop_system_vm", "tap_desktop_tab",
                "tap_desktop_text", "type_desktop_text", "uninstall_desktop",
                "upgrade_system_vm_qemu",
            ),
            GUEST to listOf(
                "delete_distro", "delete_system_vm_image", "export_distro", "get_audio_bridge_status",
                "get_custom_binds", "get_proot_install_log", "import_distro",
                "inspect_proot", "install_app_pack", "install_distro", "list_app_packs",
                "list_distros", "list_guest_services", "read_guest_file",
                "register_guest_service", "run_in_proot", "set_active_distro",
                "set_custom_binds", "start_audio_bridge", "start_guest_service",
                "stop_audio_bridge", "stop_guest_service", "unregister_guest_service",
                "view_file", "write_guest_file",
            ),
            USB to listOf(
                "bridge_serial_to_tcp", "close_usb_drive", "delete_usb_appliance",
                "detach_from_guest", "list_bluetooth_devices", "list_bridges",
                "list_serial_bridges", "list_usb_devices", "list_usb_drives",
                "list_usb_exports", "open_usb_drive", "request_usb_permission",
                "start_usbip_export", "start_usbredir_export", "stop_serial_bridge",
                "stop_usbip_export", "stop_usbredir_export",
                "unlock_usb_drive_partition", "usb_attach_to_guest",
                "usb_bulk_transfer", "usb_control_transfer",
            ),
            KEYS to listOf(
                "add_trusted_host_ca", "create_age_identity", "create_totp_secret",
                "decrypt_file", "delete_ssh_key", "delete_totp_secret",
                "delete_trusted_host_ca",
                "deploy_key", "encrypt_file", "forget_known_host", "generate_ssh_key",
                "generate_totp_code", "import_ssh_key", "list_age_identities",
                "list_known_hosts", "list_ssh_keys", "list_totp_secrets",
                "list_trusted_host_cas", "set_ssh_key_option",
            ),
            MAIL to listOf(
                "create_mail_folder", "create_mail_rule", "delete_mail_folder",
                "delete_mail_rule", "get_mail_automation_status", "list_mail_folders",
                "list_mail_messages", "list_mail_rules", "modify_mail_message",
                "poke_mail_watch", "read_mail_message", "save_mail_attachment",
                "save_mail_draft", "search_mail", "send_mail",
            ),
            RCLONE to listOf(
                "cancel_rclone_sync", "configure_rclone_remote", "delete_rclone_remote",
                "delete_sync_profile", "get_rclone_stats", "get_rclone_sync_status",
                "import_rclone_config", "list_rclone_directory",
                "list_rclone_provider_options", "list_rclone_remotes",
                "list_saved_sync_profiles", "reset_rclone_stats", "save_sync_profile",
                "start_rclone_sync", "update_rclone_remote",
            ),
            SENSES to listOf(
                "attach_gps_to_guest", "capture_camera_frame", "detach_gps_from_guest",
                "get_device_state", "get_gps_status", "get_location",
                "get_location_precise", "list_gps_logs", "list_notifications",
                "raise_notification", "read_gps_log", "read_sensors", "start_gps_log",
                "start_ntp_service", "stop_gps_log", "stop_ntp_service",
            ),
            AGENT to listOf(
                "await_adb_pairing_code", "capture_haven_ui", "check_for_update",
                "create_standing_policy", "dump_haven_ui", "enable_wireless_adb",
                "expose_adb", "get_app_info", "get_native_crashes",
                "get_pending_consent", "get_preference", "get_process_exits",
                "install_apk_from_backend", "install_apk_from_url",
                "list_paired_clients", "list_standing_policies",
                "open_developer_settings", "play_file", "present_app",
                "present_media", "present_web", "read_app_window_log", "read_logcat",
                "restart_app", "start_adb_pairing",
                "revoke_standing_policy", "set_preference", "swipe_haven_ui",
                "tap_haven_ui", "unexpose_adb", "unpair_mcp_client",
            )
        ).flatMap { (category, names) -> names.map { it to category } }.toMap()

        fun of(name: String): McpToolCategory = byName[name] ?: OTHER

        /** Every tool name the map claims, for the coverage test. */
        val mappedToolNames: Set<String> get() = byName.keys
    }
}
