package sh.haven.app.agent

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.AgentPresentationManager
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ProotInstallLogRepository
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.proot.GuestAppCatalog
import sh.haven.core.local.proot.buildPackInstallScript
import sh.haven.core.mcp.McpError
import sh.haven.core.security.posixShellQuote
import java.io.File

/**
 * PRoot distribution lifecycle, custom bind mounts, guest command/file/pack
 * execution, guest service supervision, audio bridge, GL smoke testing, and
 * cage app-window presentation.
 */
internal class ProotAndGuestToolProvider(
    private val ctx: ToolContext,
    private val context: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val localSessionManager: LocalSessionManager,
    private val prootInstallLogRepository: ProotInstallLogRepository,
    private val presentationManager: AgentPresentationManager,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = toolMapOf(
        "present_app" to ToolHandler(
            description = "Show the user a LIVE, interactive single application window inline in Haven. Launches `command` as a Wayland app under a `cage` kiosk inside the active proot guest, exposes it over VNC, and embeds the live view in a bottom sheet over whatever screen the user is on (pinch-zoom, pan, drag and fullscreen all work; the user can interact). Use this to collaborate in a real GUI app — an image viewer, a media/audio player, a PDF/whiteboard tool — rather than pushing a static image with present_media. `command` is the guest shell command cage runs (e.g. 'imv /root/board.png', 'mpv /root/clip.mp4'); the app and any Wayland deps must already be installed in the guest. Returns { presented, sessionId, vncPort, state } once the window is up. Multiple app windows can run at once: each call launches another cage; the newest is shown full-overlay and any previous one is backgrounded to a draggable edge icon (tap to bring it back). The user backgrounds a window by tapping outside it (keeps it running) and tears it down with the Dismiss button or the edge-icon close. If the window comes up grey/blank or vanishes, read the app's own stdout/stderr with read_app_window_log (works even after it crashed) instead of wrapping the command in a logging script.",
            inputSchema = objectSchema {
                string("command", "Guest shell command for the GUI app cage runs, e.g. 'imv /root/x.png'.", required = true)
                string("caption", "Optional one-line caption shown above the window.")
                boolean("fullscreen", "Open the window filling the whole screen (immersive) instead of the bottom sheet. Default false.")
                string("resolution", "Cage display resolution: 'auto' (portrait, fills the screen — default) or a 'WxH' token like '1280x720'. Lower resolution = bigger fonts.")
                number("scale", "Output scale factor (wlroots HiDPI; foot/GTK honour it). 1.0 default; 1.5/2 enlarge fonts + UI.")
                boolean("runAsRoot", "Run the app as root via fakeroot-tcp (the cage compositor itself runs non-root, so system tools like package managers go read-only otherwise). Installs fakeroot if missing. APT distros only today. Default false.")
                boolean("multiWindow", "Float the app's windows instead of force-fullscreening them. Required for apps that open several toplevels (qmmp's skinned main/EQ/playlist deck) — under the default kiosk rule they stack and only the last-raised window is visible. Default false.")
                stringArray("swayRules", "Extra sway config lines appended to the kiosk config — per-title placement for multiWindow apps (sway centers every floating window, stacking a deck), e.g. 'for_window [title=\"^Playlist$\"] move position 20 136'.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                "Launch a GUI app window for the agent: ${args.optString("command")}"
            },
        ) { args -> presentApp(args) },

        "read_app_window_log" to ToolHandler(
            description = "Read the captured output log of a present_app cage window. The cage redirects BOTH the sway compositor AND the GUI app it runs (stdout+stderr merged) into one log, so this is how the agent SEES a present_app app's own output — startup errors, GL/Mesa diagnostics, a crash trace — without wrapping the command in a logging script. Pass the sessionId returned by present_app for a live window; OMIT it to read the most-recent app-window log, which still works after the app crashed or exited (the session is gone but the log survives on disk). Returns { sessionId?, display, bytes, truncated, log }. For a GUI app that came up then died (a grey/blank or vanished window), this is the first thing to read.",
            inputSchema = objectSchema {
                string("sessionId", "present_app sessionId for a live window. Omit to read the newest app-window log (survives a crashed/exited app).")
                integer("maxBytes", "Return at most the last N bytes of the log. Default 16384, clamped 256..262144.")
            },
            consentLevel = ConsentLevel.NEVER,
            summarise = { _ -> "Read a present_app window's output log" },
        ) { args -> readAppWindowLog(args) },

        "inspect_proot" to ToolHandler(
            description = "Single rich read of the proot subsystem: active distro id, every Distro (id, label, family, installed, sizeMb, bytesOnDisk, postExtractHookIds, and installedDesktops — the desktop ids installed on THAT distro's rootfs, so the cross-distro picture is visible without switching active distro), every DesktopEnvironment with per-family Stable/Experimental/Broken compatibility and Experimental notes, current osSetupState (phase / step / progress / errorPhase / errorMessage / errorTail), current desktopSetupState (phase / errorMessage / errorTail), and the last 50 install-log events. The single endpoint to drive issue #162 verification.",
            inputSchema = objectSchema {
                integer("eventLimit", "Max install-log events to return. Default 50.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> inspectProot(args) },

        "list_distros" to ToolHandler(
            description = "Slim distro-only read of inspect_proot. Returns each Distro with installed/active/sizeMb/family. Use this when you only need the catalog and not the live state or log events.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listDistros() },


        "set_active_distro" to ToolHandler(
            description = "Switch the active proot distro WITHOUT installing anything — the lightweight counterpart to install_distro (which downloads). The active distro is the rootfs that run_in_proot, install_desktop, start_desktop and the desktop/USB tools all operate on, so this is how you drive cross-distro work over MCP (e.g. run_in_proot inside Void instead of the current active distro). The distro must already be installed — call list_distros for installed ids, or install_distro to add one. Returns the new active distro id, its family, and the desktops installed on it.",
            inputSchema = objectSchema {
                string("distroId", "Installed distro id to make active (e.g. \"void\", \"archlinux\", \"alpine-3.21\"). See list_distros.", required = true)
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> setActiveDistroTool(args) },

        "install_distro" to ToolHandler(
            description = "Set the given distro as active and trigger installRootfs(). Returns immediately; poll `inspect_proot.osSetupState` for progress (Downloading → Extracting → BootstrapHook → Baseline → Ready, or Error with attribution). Idempotent: if the distro is already installed, just switches active.",
            inputSchema = objectSchema {
                string("distroId", "Distro id from DistroCatalog (e.g. \"alpine-3.21\", \"debian-trixie\", \"debian-bookworm\", \"ubuntu-resolute\", \"ubuntu-noble\", \"archlinux\", \"void\").", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("distroId")
                val d = sh.haven.core.local.proot.DistroCatalog.lookup(id)
                if (d != null) "Install Linux distribution \"${d.label}\" (≈${d.sizeEstimateMb} MB download)?"
                else "Install distro \"$id\"?"
            },
        ) { args -> installDistro(args) },

        "delete_distro" to ToolHandler(
            description = "Wipe a distro's rootfs and remove all installed DEs on it. Stops any running DEs first. Destructive — frees the disk space and is also the recovery path when an install lands in a broken state.",
            inputSchema = objectSchema {
                string("distroId", "Distro id to delete.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("distroId")
                val d = sh.haven.core.local.proot.DistroCatalog.lookup(id)
                if (d != null) "Delete \"${d.label}\" and all its data? This cannot be undone."
                else "Delete distro \"$id\" and all its data? This cannot be undone."
            },
        ) { args -> deleteDistroTool(args) },

        "import_distro" to ToolHandler(
            description = "Import or restore a rootfs tarball as a distro (#284, #793) — \"bring your own rootfs\" or restore a backup exported by `export_distro`. The tarball (http(s) URL or an on-device file path) is extracted to `proot/rootfs/<id>` with all filesystem attributes preserved (permissions + special bits, mtime, uid/gid/uname/gname, symlinks, hardlinks, and PAX xattrs). Can register a new custom distro id OR restore over an existing / built-in distro id. Raw mode: no baseline packages and no distro hooks run — the rootfs is used as shipped; `family` only routes later package installs (apk/apt/pacman/xbps). Returns immediately; poll inspect_proot.osSetupState (Downloading → Extracting → Ready/Error). Supported formats: .tar, .tar.gz, and .tar.xz.",
            inputSchema = objectSchema {
                string("id", "Target distro id (slug: lowercase letters/digits/.-_, e.g. \"ubuntu-trixie\", \"debian-bookworm\", or \"debian-test2\").", required = true)
                string("label", "Human label shown in the picker (e.g. \"Ubuntu 26.04 (imported)\").")
                string("family", "Package family for later installs: APK | APT | PACMAN | XBPS | NIX.", required = true)
                string("source", "Rootfs tarball: an http(s):// URL, or an absolute on-device file path.", required = true)
                string("format", "Optional format: TAR | TAR_GZ | TAR_XZ. Auto-detected from the source extension if omitted.")
                integer("stripComponents", "Optional leading path components to strip (proot-distro tarballs wrap in one dir → 1). Defaults to auto: tries 0, retries 1 if no bin/sh is found.")
                string("sha256", "Optional SHA-256 to verify the download against. Skipped if omitted.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Import rootfs into distro \"${args.optString("id")}\" from ${args.optString("source")}?"
            },
        ) { args -> importDistroTool(args) },

        "export_distro" to ToolHandler(
            description = "Export a proot distro's full rootfs directly from the Android host filesystem to a POSIX.1-2001 PAX/ustar archive (.tar.gz or .tar) with all filesystem attributes preserved (#793): permissions (including setuid/setgid/sticky and read-only directory modes), modification timestamps (including PAX sub-second mtime), ownership (uid/gid/uname/gname), symlinks, hardlinks (including PRoot link2symlink `.l2s.` chains), and PAX extended attributes (`SCHILY.xattr.*`). Runs host-side without invoking `proot` or the guest shell, so a distro whose `/bin/sh` or `libc` broke during an upgrade can still be backed up and rescued.",
            inputSchema = objectSchema {
                string("distroId", "Distro id to export; omit to export the active distro.")
                string("destPath", "Absolute on-device destination path (e.g. \"/sdcard/Download/debian-trixie-rootfs.tar.gz\"). Defaults to `<cacheDir>/<distroId>-rootfs.tar.gz`.")
                boolean("gzip", "Compress with gzip (.tar.gz). Defaults to true unless destPath ends with `.tar`.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("distroId").ifEmpty { prootManager.activeDistroId }
                val dst = args.optString("destPath").ifEmpty { "${context.cacheDir}/$id-rootfs.tar.gz" }
                "Export rootfs of distro \"$id\" to $dst?"
            },
        ) { args -> exportDistroTool(args) },

        "get_custom_binds" to ToolHandler(
            description = "List the user-defined extra proot bind mounts (#301) for a distro — the per-distro custom Android→guest mounts added on top of the fixed system binds. Pass distroId; omit to use the active distro. Returns each bind as {host, guest} plus its proot spec.",
            inputSchema = objectSchema {
                string("distroId", "Distro id; omit for the active distro.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> getCustomBindsTool(args) },

        "set_custom_binds" to ToolHandler(
            description = "Replace the user-defined extra proot bind mounts (#301) for a distro — exposes arbitrary Android paths inside that distro's guest (interactive shell, desktop, and run_in_proot all pick them up). proot binds are read-write. Pass distroId (omit for active) and `binds`, an array of {host, guest?} objects (guest blank = same path as host). This REPLACES the whole list; pass [] to clear. Takes effect on the NEXT session/command, not already-running ones.",
            inputSchema = objectSchema {
                string("distroId", "Distro id; omit for the active distro.")
                property(
                    "binds",
                    JSONObject()
                        .put("type", "array")
                        .put("description", "Full replacement list. Each item: {host: \"/abs/android/path\", guest: \"/abs/guest/path\" (optional)}.")
                        .put("items", JSONObject().put("type", "object")),
                    required = true,
                )
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val n = args.optJSONArray("binds")?.length() ?: 0
                "Set $n custom bind mount(s) for distro \"${args.optString("distroId").ifEmpty { "active" }}\"?"
            },
        ) { args -> setCustomBindsTool(args) },

        "gl_smoke_test" to ToolHandler(
            description = "Launch a GL app into a RUNNING desktop on the GPU PATH (venus/virpipe — NOT the llvmpipe software fallback that launch_app_in_desktop forces), screenshot it, and heuristically report whether the frame is non-blank. A regression check for the windowed-GL-present pipeline (a blank/white frame = GL didn't present). The verdict is reliable only for a FULL-FRAME GL app (a fullscreen / cage-kiosk GL test app like 'glxgears' or 'es2gears'); for a windowed app the 2D chrome masks a blank 3D pane, so rely on the returned image. Optionally writes the gpu_use_venus pref first. Returns the screenshot plus { passed, distinctColors, topColorFraction, gpuPath, windowId? }. Detects non-blank, not correctness.",
            inputSchema = objectSchema {
                string("deId", "Desktop environment id of a RUNNING desktop (prefer a fullscreen/kiosk GL surface for a clean verdict).", required = true)
                string("command", "GL app to launch, e.g. 'glxgears' or 'es2gears'.", required = true)
                boolean("gpuUseVenus", "If set, write the gpu_use_venus pref before launching (true = venus+zink, false = virpipe). Omit to leave it unchanged.")
                integer("timeoutMs", "Max ms to wait for the app window before capturing. Default 12000, clamped 0..60000.")
                integer("maxWidth", "Downscale the returned image to at most this width. Default 1024 (clamped 160..4096).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "GL smoke-test '${args.optString("command")}' on desktop '${args.optString("deId")}'" },
        ) { args -> glSmokeTest(args) },

        "view_file" to ToolHandler(
            description = "Render a file from the ACTIVE proot guest to an INLINE image the agent can see directly — no desktop, X server, VNC client, or GPU needed (fully headless / GL-free). Handles .kicad_sch and .kicad_pcb (via kicad-cli), .pdf (first page, or `page`), .svg, and raster images (png/jpg/jpeg/webp/bmp/gif). The result is downscaled to maxWidth and returned as an image content block, so it works even when no VNC desktop is running. Prefer this over capture_desktop for looking at design output (schematics, PCBs, PDFs, plots).",
            inputSchema = objectSchema {
                string("path", "Absolute path to the file inside the active proot guest (e.g. /root/proj/board.kicad_sch).", required = true)
                integer("maxWidth", "Downscale so the image is at most this many pixels wide. Default 1024 (clamped 160–4096).")
                string("format", "\"png\" (default, lossless) or \"jpeg\" (smaller over the tunnel).")
                integer("page", "For multi-page PDFs, the 1-based page to render. Default 1.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> viewFile(args) },

        "read_guest_file" to ToolHandler(
            description = "Read a small file from the ACTIVE proot guest and return its contents to the agent (UTF-8 text; set asBase64 only for small binary). The reliable agent⇄guest text channel. Reads are capped to maxBytes. For large or binary files prefer serve_file (streams over a loopback URL — no base64 through the agent); for images/PDFs/schematics use view_file (renders to an inline picture); for anything the USER should see use present_media.",
            inputSchema = objectSchema {
                string("path", "Absolute path to the file inside the active proot guest.", required = true)
                boolean("asBase64", "Return base64 instead of UTF-8 text — only for small binary files; prefer serve_file for larger binaries. Default false.")
                integer("maxBytes", "Read at most this many bytes. Default 262144 (256 KiB), clamped 1..8388608.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readGuestFile(args) },

        "write_guest_file" to ToolHandler(
            description = "Write a file into the ACTIVE proot guest. Supply `content` (UTF-8 text); for binary prefer upload_file (stages a device-cache file into the guest) over `contentBase64`. Parent directories are created by default. The reliable way to push agent-authored text files (scripts, generators, configs) into the guest without a terminal heredoc. For large files, send in ordered chunks: first chunk {append:false, final:false}, middle chunks {append:true, final:false}, last chunk {append:true, final:true} — the file lands in the guest only on the final chunk.",
            inputSchema = objectSchema {
                string("path", "Absolute destination path inside the active proot guest.", required = true)
                string("content", "UTF-8 text to write. Mutually exclusive with contentBase64.")
                string("contentBase64", "Base64-encoded bytes for small binary writes; prefer upload_file for larger/binary files. Mutually exclusive with content.")
                boolean("mkdirs", "Create parent directories if missing. Default true.")
                boolean("append", "Append this chunk to the buffered bytes for this path instead of starting fresh. Default false (truncate). Use true for chunks after the first when sending a large file in pieces.")
                boolean("final", "Copy the buffered bytes into the guest now. Default true (single-call write). Set false on every chunk except the last; the file isn't written into the guest until final=true.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Write a file to '${args.optString("path")}' in the guest" },
        ) { args -> writeGuestFile(args) },

        "get_proot_install_log" to ToolHandler(
            description = "Return install-log events from the Room-backed ProotInstallLog table. Survives logcat rotation and app restarts. Filter by distroId and/or sinceMs (millis since epoch) to poll incrementally. Each event: id, timestamp, distroId, phase, deId?, exit?, ok, message?, logTail?.",
            inputSchema = objectSchema {
                string("distroId", "Filter to a single distro. Omit for all distros.")
                integer("sinceMs", "Return only events with timestamp > sinceMs. Default 0 (return all).")
                integer("limit", "Max events. Default 100.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> getProotInstallLog(args) },

        "run_in_proot" to ToolHandler(
            description = "Run a shell command inside the ACTIVE distro's proot guest (the same rootfs the running desktop uses) and return its combined stdout+stderr. Distro-agnostic: invokes /bin/sh -lc in whatever distro is active (check inspect_proot.activeDistroId), so it works on Debian/Arch/Void, not just Alpine like open_local_shell. For long jobs (apt-get install, pip install) you can pass background:true to get a jobId immediately, then poll by calling again with that jobId — the response carries the accumulated output and, once finished, the exitCode. Even without background, a synchronous call that runs longer than ~30s is auto-backgrounded: it returns {jobId, status:\"running\", note, output:<partial>} instead of blocking past the MCP request timeout, and you poll it the same way. A quick command returns inline ({exitCode, output}). This is the agent's headless way to provision the guest (install packages, run kicad-cli ERC/DRC, etc.).",
            inputSchema = objectSchema {
                string("command", "Shell command to run via /bin/sh -lc in the active proot. Required unless polling via jobId.")
                boolean("background", "If true, start the command and return a jobId immediately instead of blocking. Poll with that jobId. Default false.")
                string("jobId", "Poll a previously started background job. When set, `command` is ignored and the current status + accumulated output are returned.")
                integer("maxOutputChars", "Cap the returned output to its last N chars. Default 8000.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val jid = args.optString("jobId").takeIf { it.isNotBlank() }
                if (jid != null) "Let the agent read output of guest job $jid"
                else "Let the agent run in the guest: ${args.optString("command").take(120)}"
            },
        ) { args -> runInProot(args) },

        "list_app_packs" to ToolHandler(
            description = "List the curated guest-app installer packs (#470) — one-tap recipes that collate everything a working cage/app-window app needs: guest packages per package family, config drops that fix silent-failure defaults (e.g. qmmp's ALSA-in-proot output), the audio-bridge requirement, the app-window def to register, and optional sha256-pinned assets (e.g. Winamp skins). Each entry reports whether it's compatible with the ACTIVE distro's family, whether it looks installed there (verify binary present), whether its app window is already registered, and which families are device-verified. Read-only; install with install_app_pack.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listAppPacks() },

        "install_app_pack" to ToolHandler(
            description = "Install a curated guest-app pack (from list_app_packs) into the ACTIVE distro: package install via the distro's package manager, idempotent config drops, optional pinned-asset downloads, then — app-side — a verify-binary check, app-window def registration (so the app appears in Installed Apps / present_app-launchable), and audio-bridge start if the pack needs it. Long-running (a package install): returns { jobId, status: \"running\" } immediately; poll by calling again with that jobId — the response carries accumulated output and, once finished, the exitCode (0 = installed AND registered; non-zero = a phase failed, see output tail). Requires an installed active distro whose family the pack supports.",
            inputSchema = objectSchema {
                string("id", "Pack id from list_app_packs (e.g. \"qmmp\"). Required unless polling via jobId.")
                boolean("includeAssets", "Also fetch the pack's optional pinned assets (skins, sample content). Default true.")
                string("jobId", "Poll a previously started install job. When set, `id` is ignored.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val jid = args.optString("jobId").takeIf { it.isNotBlank() }
                if (jid != null) "Let the agent read app-pack install progress"
                else "Install the '${args.optString("id")}' app pack into the guest"
            },
        ) { args -> installAppPack(args) },

        "register_guest_service" to ToolHandler(
            description = "Register a long-lived helper process to run inside the ACTIVE distro's proot guest — typically an app-native MCP server (KiCad/FreeCAD/OpenSCAD) the agent drives for structured control. Haven supervises it: starts it (if autostart) when the MCP endpoint comes up, re-launches it after an app restart, and — when an MCP reverse-tunnel endpoint is configured — multiplexes its loopback `port` back to the remote MCP client alongside Haven's own endpoint (no adb forward needed). The registry is persisted per-distro. Returns the generated service id. Use start_guest_service to launch it now.",
            inputSchema = objectSchema {
                string("label", "Human-readable name, e.g. \"KiCad MCP\".", required = true)
                string("command", "Shell command run via /bin/sh -lc in the guest, e.g. 'cd /root/kicad-mcp && UV_LINK_MODE=copy uv run python http_server.py'. Should run in the foreground (Haven owns the process).", required = true)
                integer("port", "Loopback TCP port the service listens on inside the guest (e.g. 8766). Multiplexed over the MCP reverse tunnel.", required = true)
                boolean("autostart", "Re-launch automatically when Haven's MCP endpoint comes up / after app restart. Default true.")
                boolean("isMcp", "True if this service is itself a streamable-HTTP MCP server. Haven then aggregates its tools into its own MCP surface, namespaced 'guest_<id>_<tool>', so you call them through Haven directly. Default false.")
                string("mcpPath", "HTTP path of the guest MCP endpoint when isMcp=true. Default '/mcp'.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Register guest service '${args.optString("label")}' (runs: ${args.optString("command").take(80)})" },
        ) { args -> registerGuestService(args) },

        "list_guest_services" to ToolHandler(
            description = "List guest services registered on the active distro with their live state (STOPPED/STARTING/RUNNING/ERROR), command, port, autostart flag, and last error/output tail. Read-only.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listGuestServices() },

        "start_guest_service" to ToolHandler(
            description = "Start a registered guest service by id (no-op if already running). Returns its state.",
            inputSchema = objectSchema {
                string("id", "Service id from register_guest_service / list_guest_services.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Start guest service ${args.optString("id")}" },
        ) { args -> startGuestService(args) },

        "stop_guest_service" to ToolHandler(
            description = "Stop a running guest service by id (leaves it registered).",
            inputSchema = objectSchema {
                string("id", "Service id.", required = true)
            },
        ) { args -> stopGuestService(args) },

        "unregister_guest_service" to ToolHandler(
            description = "Stop (if running) and remove a guest service from the registry by id.",
            inputSchema = objectSchema {
                string("id", "Service id.", required = true)
            },
        ) { args -> unregisterGuestService(args) },

        "start_audio_bridge" to ToolHandler(
            description = "Start the proot audio bridge (#257): launches a PulseAudio daemon in the active distro and plays its output through the Android speaker — output only, no mic. Guest apps reach it via PULSE_SERVER (written to /etc/profile.d/pulse.sh and exported into desktop sessions), so apps launched from a login shell / desktop get sound. Installs pulseaudio on first use. Idempotent. Returns { state, port }.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Start proot audio bridge" },
        ) { _ -> startAudioBridge() },

        "stop_audio_bridge" to ToolHandler(
            description = "Stop the proot audio bridge: kills the in-guest PulseAudio daemon and releases the Android AudioTrack.",
            inputSchema = emptyObjectSchema(),
            summarise = { _ -> "Stop proot audio bridge" },
        ) { _ -> stopAudioBridge() },

        "get_audio_bridge_status" to ToolHandler(
            description = "Proot audio bridge status: state (STOPPED/STARTING/RUNNING/ERROR), loopback PCM port, bytesStreamed so far, and any error. Read-only — a quick way to confirm guest audio is actually flowing.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> audioBridgeStatusJson() },
    )

    private suspend fun presentApp(args: JSONObject): JSONObject {
        val command = args.optString("command").ifEmpty {
            throw McpError(-32602, "command is required (the GUI app to run, e.g. 'imv /root/x.png')")
        }
        val caption = args.optString("caption", "").ifEmpty { null }
        val fullscreen = args.optBoolean("fullscreen", false)
        // null = use the global default; the persisted def keeps that choice.
        val resolutionArg = args.optString("resolution", "").ifEmpty { null }
        val scaleArg = if (args.has("scale")) args.optDouble("scale").toFloat() else null
        val runAsRoot = args.optBoolean("runAsRoot", false)
        val multiWindow = args.optBoolean("multiWindow", false)
        val swayRules = args.optJSONArray("swayRules")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).ifEmpty { null } }
        } ?: emptyList()
        val dm = localSessionManager.desktopManager
        if (!dm.isUmlCageAvailable && !prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }
        val resolution = resolutionArg ?: preferencesRepository.appWindowDefaultResolution.first()
        val scale = scaleArg ?: preferencesRepository.appWindowDefaultScale.first()
        // The cage runs the app as a single-app sway kiosk; install sway+wayvnc
        // on demand (slow + non-streaming) so present_app works on a fresh distro.
        if (!withContext(Dispatchers.IO) { dm.ensureCageRuntime() }) {
            throw McpError(-32603, "the cage runtime (sway/wayvnc) isn't installed for '${prootManager.activeDistroId}' and couldn't be installed automatically")
        }
        val rooted = if (runAsRoot) dm.ensureRunAsRoot() else false
        val session = withContext(Dispatchers.IO) { dm.startAppWindow(command, resolution, scale, runAsRoot = rooted, multiWindow = multiWindow, swayRules = swayRules) }
        if (session.state == sh.haven.core.local.DesktopManager.DesktopState.RUNNING) {
            presentationManager.presentAppWindow(
                host = "127.0.0.1",
                port = session.vncPort,
                sessionId = session.sessionId,
                caption = caption ?: "App: $command",
                fullscreen = fullscreen,
                scale = scale,
                resolution = resolution,
            )
            // Record the launch so the user can restart this window from
            // Desktop settings later. Fire-and-forget — never fail the tool
            // because persistence hiccuped.
            runCatching {
                preferencesRepository.upsertAppWindowDef(
                    label = caption ?: command,
                    command = command,
                    createdBy = sh.haven.core.data.preferences.AppWindowOrigin.AGENT,
                    fullscreen = fullscreen,
                    resolution = resolutionArg,
                    scale = scaleArg,
                    runAsRoot = if (runAsRoot) true else null,
                    multiWindow = if (multiWindow) true else null,
                    swayRules = swayRules.ifEmpty { null },
                )
            }
            return JSONObject().apply {
                put("presented", true)
                put("sessionId", session.sessionId)
                put("vncPort", session.vncPort)
                put("state", "running")
            }
        }
        // ERROR: capture the compositor log tail, then clean up the dead
        // session so it doesn't linger in the registry.
        val logTail = dm.appWindowCompositorLog(session.sessionId)?.takeLast(800)?.trim()
        withContext(Dispatchers.IO) { dm.stopAppWindow(session.sessionId) }
        throw McpError(
            -32603,
            buildString {
                append(session.errorMessage ?: "app window failed to start")
                append(". The app + its Wayland deps must be installed in the guest, ")
                append("and the Sway DE installed (provides sway + wayvnc; present_app runs the ")
                append("app as a single-app sway kiosk because cage 0.1.4 crashes on the headless backend).")
                if (!logTail.isNullOrBlank()) append(" Compositor log: ").append(logTail)
            },
        )
    }

    /**
     * Read a present_app window's captured output (compositor + app stdout/stderr,
     * merged by the cage). By sessionId for a live window, else the newest log —
     * which survives a crashed/exited app, the case where it's most needed.
     */
    private fun readAppWindowLog(args: JSONObject): JSONObject {
        val dm = localSessionManager.desktopManager
        val sessionId = args.optString("sessionId").ifEmpty { null }
        val maxBytes = args.optInt("maxBytes", 16384).coerceIn(256, 262144)
        val (display, text) = if (sessionId != null) {
            val log = dm.appWindowCompositorLog(sessionId)
                ?: throw McpError(
                    -32602,
                    "no live app window '$sessionId' (it may have exited — omit sessionId to read the newest log)",
                )
            (dm.appWindows.value[sessionId]?.displayNumber ?: -1) to log
        } else {
            val latest = dm.latestAppWindowLog()
                ?: throw McpError(-32603, "no app-window logs yet — launch one with present_app first")
            latest.first to latest.second.readText()
        }
        val truncated = text.length > maxBytes
        val tail = if (truncated) text.takeLast(maxBytes) else text
        return JSONObject().apply {
            if (sessionId != null) put("sessionId", sessionId)
            put("display", display)
            put("bytes", tail.toByteArray().size)
            put("truncated", truncated)
            put("log", tail)
        }
    }

    // --- proot / desktop environment tool implementations (issue #162 Phase 3b) ---

    private val prootManager get() = localSessionManager.prootManager

    /** Live background `run_in_proot` jobs, keyed by jobId. */
    private class ProotJob {
        val output = StringBuilder()
        @Volatile var running = true
        @Volatile var exitCode: Int? = null
    }
    private val prootJobs = java.util.concurrent.ConcurrentHashMap<String, ProotJob>()

    /**
     * A synchronous [runInProot] that hasn't finished within this window is
     * auto-converted to a background job and a pollable jobId returned,
     * rather than blocking the single HTTP response until the MCP client's
     * request timeout fires (`-32001`). Held well under the common 60s
     * client default so `apt-get install` / large-output commands hand back
     * a job instead of timing out (#279 / #240).
     */
    private val autoBackgroundMs = 30_000L

    /**
     * Start [command] in the active proot as a streaming background job and
     * return its jobId. Output is appended line-by-line into the job buffer;
     * [ProotJob.running] flips false and [ProotJob.exitCode] is set when it
     * exits. Shared by run_in_proot's explicit `background:true` path and the
     * auto-background fallback below.
     */
    private fun startProotJob(command: String): String {
        val jobId = java.util.UUID.randomUUID().toString()
        val job = ProotJob()
        prootJobs[jobId] = job
        ctx.backgroundScope.launch {
            try {
                val proc = prootManager.startCommandInProot(command)
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(job.output) { job.output.append(line).append('\n') }
                }
                job.exitCode = proc.waitFor()
            } catch (e: Exception) {
                synchronized(job.output) { job.output.append("\n[run_in_proot error] ${e.message}\n") }
                job.exitCode = -1
            } finally {
                job.running = false
            }
        }
        return jobId
    }

    // --- guest app packs (#470) ---

    /** Live background `install_app_pack` jobs, keyed by jobId. */
    private val packJobs = java.util.concurrent.ConcurrentHashMap<String, ProotJob>()

    private suspend fun listAppPacks(): JSONObject {
        val family = prootManager.activeDistro.family
        val defs = preferencesRepository.appWindowDefs.first().items
        val arr = JSONArray()
        for (p in GuestAppCatalog.PACKS) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("label", p.label)
                put("description", p.description)
                put("families", JSONArray().apply { p.packages.keys.forEach { put(it.name) } })
                put("verifiedFamilies", JSONArray().apply { p.verifiedFamilies.forEach { put(it.name) } })
                put("compatibleWithActive", p.packages.containsKey(family))
                put("installedOnActive", File(prootManager.activeRootfsDir, p.verifyBinary).exists())
                put("appWindowRegistered", defs.any { it.command == p.appCommand })
                put("needsAudioBridge", p.needsAudioBridge)
                put("assetCount", p.assets.size)
            })
        }
        return JSONObject().apply {
            put("activeDistroId", prootManager.activeDistroId)
            put("activeFamily", family.name)
            put("count", arr.length())
            put("packs", arr)
        }
    }

    private suspend fun installAppPack(args: JSONObject): JSONObject {
        val jid = args.optString("jobId").takeIf { it.isNotBlank() }
        if (jid != null) {
            val job = packJobs[jid] ?: throw McpError(-32602, "Unknown app-pack jobId: $jid")
            return JSONObject().apply {
                put("jobId", jid)
                put("status", if (job.running) "running" else "done")
                job.exitCode?.let { put("exitCode", it) }
                put("output", synchronized(job.output) { job.output.toString() }.takeLast(8000))
            }
        }
        val id = args.optString("id").ifEmpty { throw McpError(-32602, "Missing required argument: id") }
        val pack = GuestAppCatalog.byId(id)
            ?: throw McpError(-32602, "Unknown pack '$id' — see list_app_packs")
        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }
        val family = prootManager.activeDistro.family
        val pkgs = pack.packages[family] ?: throw McpError(
            -32602,
            "Pack '$id' has no package list for $family (active distro " +
                "'${prootManager.activeDistroId}') — compatible families: ${pack.packages.keys.joinToString()}",
        )
        val script = buildPackInstallScript(pack, pkgs, family, args.optBoolean("includeAssets", true))
        val jobId = java.util.UUID.randomUUID().toString()
        val job = ProotJob()
        packJobs[jobId] = job
        ctx.backgroundScope.launch {
            try {
                val proc = prootManager.startCommandInProot(script)
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(job.output) { job.output.append(line).append('\n') }
                }
                val code = proc.waitFor()
                if (code != 0) {
                    synchronized(job.output) { job.output.append("\n[pack] guest phase failed (exit $code)\n") }
                    job.exitCode = code
                    return@launch
                }
                if (!File(prootManager.activeRootfsDir, pack.verifyBinary).exists()) {
                    synchronized(job.output) {
                        job.output.append("\n[pack] verify failed: ${pack.verifyBinary} missing after install\n")
                    }
                    job.exitCode = 1
                    return@launch
                }
                preferencesRepository.upsertAppWindowDef(
                    label = pack.appLabel,
                    command = pack.appCommand,
                    createdBy = sh.haven.core.data.preferences.AppWindowOrigin.AGENT,
                    fullscreen = pack.fullscreen,
                    multiWindow = if (pack.multiWindow) true else null,
                    swayRules = pack.swayRules.ifEmpty { null },
                    resolution = pack.resolution,
                    scale = pack.scale,
                )
                if (pack.needsAudioBridge) localSessionManager.audioBridge.start()
                synchronized(job.output) {
                    job.output.append("\n[pack] ${pack.id} installed; app window '${pack.appLabel}' registered")
                    if (pack.needsAudioBridge) job.output.append("; audio bridge starting")
                    job.output.append('\n')
                }
                job.exitCode = 0
            } catch (e: Exception) {
                synchronized(job.output) { job.output.append("\n[pack] install error: ${e.message}\n") }
                job.exitCode = -1
            } finally {
                job.running = false
            }
        }
        return JSONObject().apply {
            put("jobId", jobId)
            put("status", "running")
            put("pack", pack.id)
            put("family", family.name)
            put("poll", "install_app_pack with jobId=$jobId")
        }
    }

    private fun distroToJson(
        d: sh.haven.core.local.proot.Distro,
        installed: Boolean,
        active: Boolean,
        bytesOnDisk: Long,
    ): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("label", d.label)
        put("family", d.family.name)
        put("installed", installed)
        put("active", active)
        put("sizeEstimateMb", d.sizeEstimateMb)
        put("bytesOnDisk", bytesOnDisk)
        put("baselinePackages", JSONArray().apply {
            d.baselinePackages.forEach { put(it) }
        })
        put("postExtractHookIds", JSONArray().apply {
            d.postExtractHooks.forEach { put(it.id) }
        })
        put("rootfsArches", JSONArray().apply {
            d.rootfsSources.keys.forEach { put(it.abi) }
        })
        // Which desktops are installed on THIS distro's rootfs — read
        // per-distro so the cross-distro picture is visible without
        // switching the active distro. Empty for non-installed distros.
        put("installedDesktops", JSONArray().apply {
            prootManager.installedDesktopsFor(d.id).forEach { put(it.spec.id) }
        })
        put("defaultLoginUser", prootManager.guestUsernameFor(d.id).ifEmpty { "root" })
    }

    private fun rootfsBytesOnDisk(distroId: String): Long {
        // Honor ProotManager's legacy-path fallback (alpine-3.21 → alpine).
        val dir = prootManager.rootfsDirFor(distroId)
        if (!dir.exists()) return 0L
        // Cheap: just the top-level directory size — accurate to within
        // the order of magnitude. Recursive du would block several
        // seconds for a 100-MB rootfs.
        return runCatching {
            java.nio.file.Files.walk(dir.toPath()).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                    .mapToLong {
                        runCatching { java.nio.file.Files.size(it) }.getOrDefault(0L)
                    }
                    .sum()
            }
        }.getOrDefault(0L)
    }


    private fun osSetupStateToJson(
        state: sh.haven.core.local.ProotManager.SetupState,
    ): JSONObject = JSONObject().apply {
        when (state) {
            is sh.haven.core.local.ProotManager.SetupState.NotInstalled -> {
                put("phase", "NotInstalled")
            }
            is sh.haven.core.local.ProotManager.SetupState.Downloading -> {
                put("phase", "Downloading")
                put("percent", state.progress)
            }
            is sh.haven.core.local.ProotManager.SetupState.Extracting -> {
                put("phase", "Extracting")
            }
            is sh.haven.core.local.ProotManager.SetupState.Initializing -> {
                put("phase", "Initializing")
                put("step", state.step)
                state.progress?.let { put("progress", (it * 100).toInt()) }
            }
            is sh.haven.core.local.ProotManager.SetupState.Ready -> {
                put("phase", "Ready")
            }
            is sh.haven.core.local.ProotManager.SetupState.Error -> {
                put("phase", "Error")
                put("errorPhase", state.phase.name)
                put("errorMessage", state.message)
                if (state.logTail.isNotEmpty()) put("errorTail", state.logTail)
            }
        }
    }

    private fun desktopSetupStateToJson(
        state: sh.haven.core.local.ProotManager.DesktopSetupState,
    ): JSONObject = JSONObject().apply {
        when (state) {
            is sh.haven.core.local.ProotManager.DesktopSetupState.Idle ->
                put("phase", "Idle")
            is sh.haven.core.local.ProotManager.DesktopSetupState.Installing -> {
                put("phase", "Installing")
                put("step", state.step)
                // Which desktop this work belongs to, absent for an add-on
                // install that belongs to none (#502). The state gained this so
                // the Manage screen could stop putting a spinner on every row;
                // reporting only "something is installing" left an agent
                // polling here with the same blind spot the UI had.
                state.de?.let { put("deId", it.spec.id) }
            }
            is sh.haven.core.local.ProotManager.DesktopSetupState.Complete ->
                put("phase", "Complete")
            is sh.haven.core.local.ProotManager.DesktopSetupState.Error -> {
                put("phase", "Error")
                put("errorPhase", state.phase.name)
                put("errorMessage", state.message)
                if (state.logTail.isNotEmpty()) put("errorTail", state.logTail)
            }
        }
    }

    private fun installLogEventToJson(
        e: sh.haven.core.data.db.entities.ProotInstallLog,
    ): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("timestamp", e.timestamp)
        put("distroId", e.distroId)
        put("phase", e.phase)
        e.deId?.let { put("deId", it) }
        if (e.exit != null) put("exit", e.exit) else put("exit", JSONObject.NULL)
        put("ok", e.ok)
        e.message?.let { put("message", it) }
        e.logTail?.let { put("logTail", it) }
    }

    private suspend fun inspectProot(args: JSONObject): JSONObject {
        val eventLimit = args.optInt("eventLimit", 50).coerceIn(1, 500)
        val pm = prootManager
        val active = pm.activeDistro
        val installed = pm.installedDistros.map { it.id }.toSet()
        val installedDes = pm.installedDesktops
        val runningDes = localSessionManager.desktopManager.desktops.value.keys

        val distrosJson = JSONArray().apply {
            sh.haven.core.local.proot.DistroCatalog.all.forEach { d ->
                put(
                    distroToJson(
                        d = d,
                        installed = d.id in installed,
                        active = d.id == active.id,
                        bytesOnDisk = if (d.id in installed) rootfsBytesOnDisk(d.id) else 0L,
                    ),
                )
            }
        }
        val desktopsJson = JSONArray().apply {
            sh.haven.core.local.ProotManager.DesktopEnvironment.entries
                .filter { !it.hidden }
                .filter { active.family in it.spec.packagesPerFamily.keys }
                .forEach { de ->
                    put(
                        desktopToJson(
                            de = de,
                            activeFamily = active.family,
                            installed = de in installedDes,
                            running = de in runningDes,
                        ),
                    )
                }
        }
        val events = prootInstallLogRepository.querySince(
            distroId = null,
            sinceMs = 0L,
            limit = eventLimit,
        )
        val eventsJson = JSONArray().apply {
            // querySince returns ASC; reverse so the most recent event is at index 0
            events.asReversed().forEach { put(installLogEventToJson(it)) }
        }

        return JSONObject().apply {
            put("activeDistroId", active.id)
            put("guestEngine", pm.guestEngine.id)
            put("chrootNgAvailable", pm.isChrootNgAvailable)
            put("distros", distrosJson)
            put("desktopEnvironments", desktopsJson)
            put("osSetupState", osSetupStateToJson(pm.state.value))
            put("desktopSetupState", desktopSetupStateToJson(pm.desktopState.value))
            put("recentInstallLog", eventsJson)
        }
    }

    private fun setActiveDistroTool(args: JSONObject): JSONObject {
        val pm = prootManager
        val id = args.optString("distroId").trim()
        if (id.isEmpty()) throw McpError(-32602, "distroId is required")
        val distro = sh.haven.core.local.proot.DistroCatalog.lookup(id)
            ?: throw McpError(-32602, "Unknown distro id '$id'. See list_distros for valid ids.")
        if (distro !in pm.installedDistros) {
            throw McpError(-32603, "Distro '$id' (${distro.label}) is not installed — use install_distro first.")
        }
        pm.setActiveDistroId(id)
        return JSONObject().apply {
            put("activeDistroId", pm.activeDistroId)
            put("label", distro.label)
            put("family", distro.family.name)
            put("installedDesktops", JSONArray(pm.installedDesktopsFor(id).map { it.id }))
        }
    }

    private fun listDistros(): JSONObject {
        val pm = prootManager
        val active = pm.activeDistro
        val installed = pm.installedDistros.map { it.id }.toSet()
        val arr = JSONArray().apply {
            sh.haven.core.local.proot.DistroCatalog.all.forEach { d ->
                put(
                    distroToJson(
                        d = d,
                        installed = d.id in installed,
                        active = d.id == active.id,
                        bytesOnDisk = if (d.id in installed) rootfsBytesOnDisk(d.id) else 0L,
                    ),
                )
            }
        }
        return JSONObject().apply {
            put("activeDistroId", active.id)
            put("count", arr.length())
            put("distros", arr)
        }
    }


    private suspend fun installDistro(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "distroId is required")
        val target = sh.haven.core.local.proot.DistroCatalog.lookup(distroId)
            ?: throw McpError(-32602, "Unknown distroId: $distroId")
        val pm = prootManager
        val wasInstalled = target.id in pm.installedDistros.map { it.id }
        if (pm.activeDistroId != target.id) {
            pm.setActiveDistroId(target.id)
        }
        // Launch the install on the IO dispatcher and return immediately.
        // Callers poll via inspect_proot.osSetupState — matches the UI
        // pattern of "tap Install, watch the progress block update".
        ctx.backgroundScope.launch {
            try {
                pm.installRootfs()
            } catch (_: Exception) {
                // The catch in installRootfs already pushes Error state.
            }
        }
        return JSONObject().apply {
            put("distroId", target.id)
            put("label", target.label)
            put("alreadyInstalled", wasInstalled)
            put("status", "started")
            put("poll", "inspect_proot.osSetupState")
        }
    }

    private suspend fun deleteDistroTool(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "distroId is required")
        val target = sh.haven.core.local.proot.DistroCatalog.lookup(distroId)
            ?: throw McpError(-32602, "Unknown distroId: $distroId")
        val pm = prootManager
        // Stop any DEs on this distro before yanking the rootfs.
        if (pm.activeDistroId == target.id) {
            localSessionManager.desktopManager.stopAll()
        }
        pm.deleteDistro(target.id)
        // Drop install-log rows for this distro too — the rootfs is gone,
        // its events would just be confusing on a re-install.
        prootInstallLogRepository.deleteForDistro(target.id)
        return JSONObject().apply {
            put("distroId", target.id)
            put("label", target.label)
            put("status", "deleted")
        }
    }

    private fun importDistroTool(args: JSONObject): JSONObject {
        val id = args.optString("id").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "id is required")
        val source = args.optString("source").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "source is required")
        val family = runCatching {
            sh.haven.core.local.proot.PackageFamily.valueOf(args.optString("family").uppercase())
        }.getOrNull() ?: throw McpError(-32602, "family must be one of APK, APT, PACMAN, XBPS, NIX")
        val label = args.optString("label").ifBlank { id }
        val cleanSource = source.substringBefore('?').lowercase()
        // Auto-detect compression from the source extension if not given.
        val format = when (val f = args.optString("format").uppercase()) {
            "TAR" -> sh.haven.core.local.proot.RootfsFormat.TAR
            "TAR_GZ" -> sh.haven.core.local.proot.RootfsFormat.TAR_GZ
            "TAR_XZ" -> sh.haven.core.local.proot.RootfsFormat.TAR_XZ
            "TAR_ZSTD" -> sh.haven.core.local.proot.RootfsFormat.TAR_ZSTD
            "" -> when {
                cleanSource.endsWith(".tar.xz") || cleanSource.endsWith(".txz") -> sh.haven.core.local.proot.RootfsFormat.TAR_XZ
                cleanSource.endsWith(".tar.zst") || cleanSource.endsWith(".tar.zstd") -> sh.haven.core.local.proot.RootfsFormat.TAR_ZSTD
                cleanSource.endsWith(".tar") -> sh.haven.core.local.proot.RootfsFormat.TAR
                else -> sh.haven.core.local.proot.RootfsFormat.TAR_GZ
            }
            else -> throw McpError(-32602, "Unknown format: $f")
        }
        val strip = if (args.has("stripComponents")) args.optInt("stripComponents", 0) else 0
        val sha = args.optString("sha256").takeIf { it.isNotEmpty() }
        ctx.backgroundScope.launch {
            try {
                prootManager.importRootfs(id, label, family, source, format, strip, sha)
            } catch (_: Exception) {
                // importRootfs's catch already pushes Error state.
            }
        }
        return JSONObject().apply {
            put("id", id)
            put("label", label)
            put("family", family.name)
            put("status", "started")
            put("poll", "inspect_proot.osSetupState")
        }
    }

    private suspend fun exportDistroTool(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() } ?: prootManager.activeDistroId
        val rootfsDir = prootManager.rootfsDirFor(distroId)
        if (!rootfsDir.isDirectory) {
            throw McpError(-32602, "Rootfs directory does not exist for distro '$distroId'")
        }
        val defaultExt = if (args.has("gzip") && !args.optBoolean("gzip", true)) "tar" else "tar.gz"
        val destPath = args.optString("destPath").takeIf { it.isNotEmpty() }
            ?: java.io.File(context.cacheDir, "$distroId-rootfs.$defaultExt").absolutePath
        val destFile = java.io.File(destPath)
        val gzip = if (args.has("gzip")) {
            args.optBoolean("gzip", true)
        } else {
            !destFile.name.endsWith(".tar", ignoreCase = true)
        }
        val stats = prootManager.exportRootfsToFile(distroId = distroId, destFile = destFile, gzip = gzip)
        return JSONObject().apply {
            put("distroId", distroId)
            put("destPath", destFile.absolutePath)
            put("gzip", gzip)
            put("fileCount", stats.fileCount)
            put("dirCount", stats.dirCount)
            put("symlinkCount", stats.symlinkCount)
            put("hardlinkCount", stats.hardlinkCount)
            put("bytesWritten", stats.bytesWritten)
            put("status", "exported")
        }
    }

    private fun getCustomBindsTool(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() } ?: prootManager.activeDistroId
        val binds = prootManager.customBinds(distroId)
        return JSONObject().apply {
            put("distroId", distroId)
            put("binds", JSONArray().apply {
                binds.forEach {
                    put(JSONObject().apply {
                        put("host", it.host)
                        put("guest", it.guest)
                        put("spec", it.spec())
                    })
                }
            })
        }
    }

    private fun setCustomBindsTool(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() } ?: prootManager.activeDistroId
        val arr = args.optJSONArray("binds") ?: throw McpError(-32602, "binds (array) is required")
        val binds = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val host = o.optString("host").trim()
            if (host.isEmpty()) return@mapNotNull null
            sh.haven.core.local.proot.CustomBind(host, o.optString("guest").trim())
        }
        prootManager.setCustomBinds(distroId, binds)
        return JSONObject().apply {
            put("distroId", distroId)
            put("count", binds.size)
            put("binds", JSONArray().apply { binds.forEach { put(it.spec()) } })
            put("note", "Takes effect on the next session/command for this distro.")
        }
    }

    /**
     * Launch a GL app on the GPU path, screenshot it, and heuristically judge
     * whether the frame is non-blank — a regression check for the windowed-GL
     * present pipeline (memory: project_3d_desktop_gl_prusaslicer_test, where a
     * blank canvas came back pure white). Reliable only for a full-frame GL app;
     * the returned image is the ground truth. Reuses [encodeCapture] and
     * returns a [ToolResult.Image] the transport renders as an MCP image block.
     */
    private suspend fun glSmokeTest(args: JSONObject): ToolResult {
        val deId = args.optString("deId").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "deId is required")
        val command = args.optString("command").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "command is required")
        val timeoutMs = args.optInt("timeoutMs", 12000).coerceIn(0, 60000)
        val maxWidth = args.optInt("maxWidth", 1024).coerceIn(160, 4096)
        val de = desktopByIdOrThrow(deId)
        val dm = localSessionManager.desktopManager

        if (args.has("gpuUseVenus")) {
            preferencesRepository.setGpuUseVenus(args.getBoolean("gpuUseVenus"))
        }

        val before: Set<String> = try {
            dm.listWindows(de).map { it.id }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val appId = dm.launchApp(de, command, gpu = true)

        var windowId: String? = null
        if (timeoutMs > 0) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(600)
                val fresh = try {
                    dm.listWindows(de).firstOrNull { it.id !in before }
                } catch (e: Exception) {
                    null
                }
                if (fresh != null) {
                    windowId = fresh.id
                    break
                }
            }
        }
        // Let the GL app paint a few frames before sampling.
        kotlinx.coroutines.delay(1500)

        val png = dm.capture(de)
        val (b64, w, h) = encodeCapture(png, null, maxWidth, "jpeg")

        // Analyse the FULL-resolution capture (not the downscaled JPEG) so the
        // subsample sees real pixels.
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw McpError(-32603, "Could not decode captured image")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val verdict = sh.haven.core.local.GlCanvasProbe.analyze(px, bmp.width, bmp.height)
        val venus = preferencesRepository.gpuUseVenus.first()

        return ToolResult.Image(
            base64 = b64,
            mimeType = "image/jpeg",
            structured = JSONObject().apply {
                put("deId", de.spec.id)
                put("command", command)
                put("appId", appId)
                put("gpuPath", if (venus) "venus+zink" else "virpipe")
                put("windowId", windowId ?: JSONObject.NULL)
                put("passed", verdict.nonBlank)
                put("distinctColors", verdict.distinctColors)
                put("topColorFraction", verdict.topColorFraction)
                put("sampled", verdict.sampled)
                put(
                    "note",
                    "non-blank heuristic over the whole frame; reliable for a full-frame GL app, " +
                        "not a correctness check — inspect the image",
                )
                put("width", w)
                put("height", h)
                put("format", "jpeg")
            },
        )
    }

    /**
     * Render a guest file to an inline image the agent can see, fully
     * headless (no X / no GL). The render runs inside the proot writing a PNG
     * to `/tmp` (bound to the app cacheDir), which we read back off the app's
     * filesystem and re-encode via [encodeCapture] — returning a
     * [ToolResult.Image] the transport renders as an MCP image block, the same
     * as [captureDesktop]. This is the reliable "show me the design" loop that
     * doesn't depend on a running VNC desktop.
     */
    private suspend fun viewFile(args: JSONObject): ToolResult {
        val path = args.optString("path").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "path is required (absolute guest path)")
        if (!path.startsWith("/")) throw McpError(-32602, "path must be an absolute guest path")
        val maxWidth = args.optInt("maxWidth", 1024).coerceIn(160, 4096)
        val format = if (args.optString("format", "png").lowercase() == "jpeg") "jpeg" else "png"
        val page = args.optInt("page", 1).coerceAtLeast(1)
        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }

        val ext = path.substringAfterLast('.', "").lowercase()
        val base = path.substringAfterLast('/').substringBeforeLast('.')
        val outName = "haven-view-${System.currentTimeMillis()}.png" // guest /tmp == app cacheDir
        val outGuest = "/tmp/$outName"
        val q = posixShellQuote(path)

        // Build the render pipeline + the binaries it needs. `\$` escapes keep
        // shell variables/substitutions literal; `$outGuest`/`$maxWidth`/etc.
        // are Kotlin interpolations baked in here.
        val needed: List<String>
        val renderCmd: String
        when (ext) {
            "png", "jpg", "jpeg", "webp", "bmp", "gif" -> {
                needed = emptyList()
                renderCmd = "cp $q $outGuest"
            }
            "svg" -> {
                needed = listOf("rsvg-convert")
                renderCmd = "rsvg-convert -w $maxWidth $q -o $outGuest"
            }
            "pdf" -> {
                needed = listOf("pdftoppm")
                // -singlefile drops pdftoppm's "-NN" page suffix so it lands on $outGuest.
                renderCmd = "pdftoppm -png -r 150 -f $page -l $page -singlefile $q ${outGuest.removeSuffix(".png")}"
            }
            "kicad_sch" -> {
                needed = listOf("rsvg-convert")
                renderCmd = "d=\$(mktemp -d) && kicad-cli sch export svg -o \$d $q && " +
                    "rsvg-convert -w $maxWidth \"\$d/$base.svg\" -o $outGuest"
            }
            "kicad_pcb" -> {
                needed = listOf("rsvg-convert")
                // KiCad 10 made --layers mandatory for `pcb export svg`; a sensible default
                // set (copper + silkscreen + fab + outline) renders a recognisable board.
                renderCmd = "d=\$(mktemp -d) && kicad-cli pcb export svg " +
                    "--layers F.Cu,B.Cu,F.SilkS,B.SilkS,F.Fab,Edge.Cuts -o \$d/pcb.svg $q && " +
                    "rsvg-convert -w $maxWidth \$d/pcb.svg -o $outGuest"
            }
            else -> throw McpError(
                -32602,
                "Unsupported file type '.$ext' — view_file handles kicad_sch, kicad_pcb, pdf, svg, png, jpg, jpeg, webp, bmp, gif",
            )
        }

        if (needed.isNotEmpty()) {
            val (ready, detail) = prootManager.ensureRenderTools(needed)
            if (!ready) throw McpError(-32603, "Render tools unavailable: $detail")
        }
        if (ext == "kicad_sch" || ext == "kicad_pcb") {
            // kicad-cli is heavy; never auto-install — surface a clear error if absent.
            val (probe, _) = prootManager.runCommandInProot(
                "command -v kicad-cli >/dev/null 2>&1 && echo HAVE || echo MISSING",
            )
            if (!probe.contains("HAVE")) {
                throw McpError(-32603, "kicad-cli not found in the guest — install KiCad to render .$ext files")
            }
        }

        val script = "rm -f $outGuest; { $renderCmd ; } > $outGuest.log 2>&1; echo EXIT:\$?"
        val (out, _) = prootManager.runCommandInProot(script)

        val outFile = File(context.cacheDir, outName)
        val logFile = File(context.cacheDir, "$outName.log")
        try {
            if (!outFile.exists() || outFile.length() == 0L) {
                val log = if (logFile.exists()) logFile.readText() else ""
                throw McpError(-32603, "Render produced no image for $path (${log.take(500).trim()}; $out)")
            }
            val bytes = outFile.readBytes()
            val (b64, w, h) = withContext(Dispatchers.Default) {
                encodeCapture(bytes, null, maxWidth, format)
            }
            return ToolResult.Image(
                base64 = b64,
                mimeType = if (format == "jpeg") "image/jpeg" else "image/png",
                structured = JSONObject().apply {
                    put("path", path)
                    put("rendered", "$ext → $format")
                    put("width", w)
                    put("height", h)
                    put("format", format)
                },
            )
        } finally {
            outFile.delete()
            logFile.delete()
        }
    }

    /**
     * Read a guest file's bytes back to the agent. Stages a copy under
     * `/tmp` (== app cacheDir) so we can read it app-side, capped to
     * [maxBytes]. Returns UTF-8 text or base64. The reliable agent⇄guest
     * read channel — no base64 hand-copying over run_in_proot output.
     */
    private suspend fun readGuestFile(args: JSONObject): JSONObject {
        val path = args.optString("path").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "path is required (absolute guest path)")
        if (!path.startsWith("/")) throw McpError(-32602, "path must be an absolute guest path")
        val asBase64 = args.optBoolean("asBase64", false)
        val maxBytes = args.optInt("maxBytes", 262144).coerceIn(1, 8 * 1024 * 1024)
        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }
        val q = posixShellQuote(path)
        val staged = "haven-read-${System.currentTimeMillis()}"
        val script = "rm -f /tmp/$staged; { [ -f $q ] && cp -- $q /tmp/$staged ; } > /tmp/$staged.log 2>&1; echo EXIT:\$?"
        val (out, _) = prootManager.runCommandInProot(script)
        val staging = File(context.cacheDir, staged)
        val logFile = File(context.cacheDir, "$staged.log")
        try {
            if (!staging.exists()) {
                val log = if (logFile.exists()) logFile.readText() else ""
                throw McpError(-32603, "Could not read $path (missing or not a regular file?) (${log.take(300).trim()}; $out)")
            }
            val total = staging.length()
            val bytes = staging.inputStream().use { ins ->
                val buf = ByteArray(maxBytes)
                var read = 0
                while (read < maxBytes) {
                    val n = ins.read(buf, read, maxBytes - read)
                    if (n < 0) break
                    read += n
                }
                buf.copyOf(read)
            }
            return JSONObject().apply {
                put("path", path)
                put("totalBytes", total)
                put("returnedBytes", bytes.size)
                put("truncated", total > bytes.size)
                if (asBase64) {
                    put("encoding", "base64")
                    put("contentBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                } else {
                    put("encoding", "utf8")
                    put("content", String(bytes, Charsets.UTF_8))
                }
            }
        } finally {
            staging.delete()
            logFile.delete()
        }
    }

    /**
     * Write agent-supplied bytes into the guest. Stages the bytes under
     * `/tmp` (== app cacheDir) then `cp`s them to [path] inside the proot,
     * creating parent dirs by default. Accepts UTF-8 `content` or binary
     * `contentBase64` (exactly one).
     *
     * Supports chunked transfer for large files so a single huge base64
     * payload doesn't have to fit one JSON-RPC request (#214):
     * - `append` (default false): false truncates/creates the per-path
     *   staging buffer; true appends this chunk to it.
     * - `final` (default true): true copies the buffer into the guest and
     *   reports `bytesWritten`; false only buffers and reports
     *   `bufferedBytes` without touching the guest yet.
     * Single-call usage (no `append`/`final`) is identical to before.
     * Chunks are assumed delivered in order over sequential calls.
     */
    private suspend fun writeGuestFile(args: JSONObject): JSONObject {
        val path = args.optString("path").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "path is required (absolute guest path)")
        if (!path.startsWith("/")) throw McpError(-32602, "path must be an absolute guest path")
        val hasText = args.has("content")
        val hasB64 = args.has("contentBase64")
        if (hasText == hasB64) throw McpError(-32602, "provide exactly one of content or contentBase64")
        val mkdirs = args.optBoolean("mkdirs", true)
        val append = args.optBoolean("append", false)
        val final = args.optBoolean("final", true)
        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }
        val bytes = if (hasB64) {
            try {
                Base64.decode(args.getString("contentBase64"), Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw McpError(-32602, "contentBase64 is not valid base64: ${e.message}")
            }
        } else {
            args.getString("content").toByteArray(Charsets.UTF_8)
        }
        // Staging buffer keyed deterministically by the target path so a
        // sequence of chunk calls reuses the same file. append=false
        // truncates (a fresh transfer self-heals over any abandoned
        // buffer); append=true accumulates.
        val staged = "haven-write-" + Integer.toHexString(path.hashCode())
        val staging = File(context.cacheDir, staged)
        val logFile = File(context.cacheDir, "$staged.log")
        if (append) staging.appendBytes(bytes) else staging.writeBytes(bytes)

        // Non-final chunk: buffered only, guest untouched.
        if (!final) {
            return JSONObject().apply {
                put("path", path)
                put("bufferedBytes", staging.length())
                put("status", "buffered")
            }
        }

        // Final (or single-call): copy the accumulated buffer into the guest.
        val q = posixShellQuote(path)
        try {
            val mk = if (mkdirs) "mkdir -p -- \"\$(dirname -- $q)\" && " else ""
            val script = "{ $mk cp -- /tmp/$staged $q ; } > /tmp/$staged.log 2>&1; echo EXIT:\$?"
            val (out, _) = prootManager.runCommandInProot(script)
            if (!out.contains("EXIT:0")) {
                val log = if (logFile.exists()) logFile.readText() else ""
                throw McpError(-32603, "Write failed for $path (${log.take(300).trim()}; $out)")
            }
            return JSONObject().apply {
                put("path", path)
                put("bytesWritten", staging.length())
                put("status", "written")
            }
        } finally {
            staging.delete()
            logFile.delete()
        }
    }

    private suspend fun runInProot(args: JSONObject): JSONObject {
        val maxOut = args.optInt("maxOutputChars", 8000).coerceIn(200, 200_000)
        fun tail(s: String) = if (s.length > maxOut) s.takeLast(maxOut) else s

        // Poll an existing background job.
        val pollId = args.optString("jobId").takeIf { it.isNotBlank() }
        if (pollId != null) {
            val job = prootJobs[pollId]
                ?: throw McpError(-32602, "Unknown jobId: $pollId (it may have been lost on app restart)")
            val out = synchronized(job.output) { job.output.toString() }
            return JSONObject().apply {
                put("jobId", pollId)
                put("status", if (job.running) "running" else "done")
                if (!job.running) put("exitCode", job.exitCode ?: -1)
                put("output", tail(out))
            }
        }

        val command = args.optString("command").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "command is required (or pass jobId to poll a background job)")

        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }

        // Background mode: stream the process output into a job buffer and
        // return immediately so long installs don't hold the request open.
        if (args.optBoolean("background", false)) {
            val jobId = startProotJob(command)
            return JSONObject().apply {
                put("jobId", jobId)
                put("status", "running")
                put("activeDistroId", prootManager.activeDistroId)
                put("poll", "run_in_proot with jobId=$jobId")
            }
        }

        // Synchronous mode — start the command as a job and wait up to
        // [autoBackgroundMs] for it to finish. A quick command returns its
        // full output + exitCode inline (and its job is dropped); a long one
        // (apt-get install, big output) is handed back as a pollable jobId
        // rather than blocking the HTTP response into a client `-32001`
        // request timeout (#279 / #240).
        val jobId = startProotJob(command)
        val job = prootJobs.getValue(jobId)
        val finished = withTimeoutOrNull(autoBackgroundMs) {
            while (job.running) delay(50)
            true
        } != null
        val out = synchronized(job.output) { job.output.toString() }
        if (finished) {
            prootJobs.remove(jobId)
            return JSONObject().apply {
                put("activeDistroId", prootManager.activeDistroId)
                put("exitCode", job.exitCode ?: -1)
                put("output", tail(out))
            }
        }
        return JSONObject().apply {
            put("jobId", jobId)
            put("status", "running")
            put("activeDistroId", prootManager.activeDistroId)
            put("output", tail(out))
            put(
                "note",
                "Command still running after ${autoBackgroundMs / 1000}s — auto-backgrounded to " +
                    "avoid a request timeout. Poll with run_in_proot(jobId=$jobId).",
            )
            put("poll", "run_in_proot with jobId=$jobId")
        }
    }

    private fun guestServiceToJson(
        inst: sh.haven.core.local.GuestServiceManager.GuestServiceInstance?,
        spec: sh.haven.core.local.GuestServiceManager.GuestServiceSpec,
    ): JSONObject = JSONObject().apply {
        put("id", spec.id)
        put("label", spec.label)
        put("command", spec.command)
        put("port", spec.port)
        put("autostart", spec.autostart)
        put("isMcp", spec.isMcp)
        if (spec.isMcp) put("mcpPath", spec.mcpPath)
        put("state", inst?.state?.name ?: "STOPPED")
        inst?.errorMessage?.let { put("errorMessage", it) }
    }

    private fun registerGuestService(args: JSONObject): JSONObject {
        val label = args.optString("label").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "label is required")
        val command = args.optString("command").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "command is required")
        val port = args.optInt("port", 0).takeIf { it in 1..65535 }
            ?: throw McpError(-32602, "port must be 1..65535")
        val autostart = if (args.has("autostart")) args.optBoolean("autostart") else true
        val isMcp = args.optBoolean("isMcp", false)
        val mcpPath = args.optString("mcpPath", "/mcp").ifBlank { "/mcp" }
        val gsm = localSessionManager.guestServiceManager
        val id = "gsvc-${System.currentTimeMillis().toString(36)}"
        val spec = sh.haven.core.local.GuestServiceManager.GuestServiceSpec(
            id = id, label = label, command = command, port = port, autostart = autostart,
            isMcp = isMcp, mcpPath = mcpPath,
        )
        gsm.register(spec)
        ctx.invalidateGuestTools()
        return JSONObject().apply {
            put("id", id)
            put("activeDistroId", prootManager.activeDistroId)
            put("status", "registered")
            put("isMcp", isMcp)
            put("hint", "call start_guest_service to launch it now")
        }
    }

    private fun listGuestServices(): JSONObject {
        val gsm = localSessionManager.guestServiceManager
        val running = gsm.services.value
        val arr = JSONArray()
        gsm.registered().forEach { spec -> arr.put(guestServiceToJson(running[spec.id], spec)) }
        return JSONObject().apply {
            put("activeDistroId", prootManager.activeDistroId)
            put("count", arr.length())
            put("services", arr)
        }
    }

    private fun guestSpecOrThrow(id: String): sh.haven.core.local.GuestServiceManager.GuestServiceSpec =
        localSessionManager.guestServiceManager.registered().firstOrNull { it.id == id }
            ?: throw McpError(-32602, "Unknown guest service id: $id")

    private fun startGuestService(args: JSONObject): JSONObject {
        val id = args.optString("id").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "id is required")
        val spec = guestSpecOrThrow(id)
        val gsm = localSessionManager.guestServiceManager
        gsm.start(id)
        ctx.invalidateGuestTools()
        // The MCP reverse tunnel re-homes to pick up the new service's port
        // via HavenApp's guest-service observer (no-op when no tunnel endpoint
        // is configured).
        return guestServiceToJson(gsm.services.value[id], spec).apply { put("status", "started") }
    }

    private fun stopGuestService(args: JSONObject): JSONObject {
        val id = args.optString("id").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "id is required")
        val spec = guestSpecOrThrow(id)
        localSessionManager.guestServiceManager.stop(id)
        ctx.invalidateGuestTools()
        return guestServiceToJson(localSessionManager.guestServiceManager.services.value[id], spec)
            .apply { put("status", "stopped") }
    }

    private fun unregisterGuestService(args: JSONObject): JSONObject {
        val id = args.optString("id").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "id is required")
        guestSpecOrThrow(id)
        localSessionManager.guestServiceManager.unregister(id)
        ctx.invalidateGuestTools()
        return JSONObject().apply { put("id", id); put("status", "unregistered") }
    }

    private suspend fun startAudioBridge(): JSONObject {
        localSessionManager.audioBridge.start()
        return audioBridgeStatusJson()
    }

    private fun stopAudioBridge(): JSONObject {
        localSessionManager.audioBridge.stop()
        return audioBridgeStatusJson()
    }

    private fun audioBridgeStatusJson(): JSONObject {
        val s = localSessionManager.audioBridge.statusNow()
        return JSONObject().apply {
            put("state", s.state.name)
            put("port", s.port)
            put("bytesStreamed", s.bytesStreamed)
            s.error?.let { put("error", it) }
        }
    }

    private suspend fun getProotInstallLog(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() }
        val sinceMs = args.optLong("sinceMs", 0L)
        val limit = args.optInt("limit", 100).coerceIn(1, 1000)
        val events = prootInstallLogRepository.querySince(
            distroId = distroId,
            sinceMs = sinceMs,
            limit = limit,
        )
        val arr = JSONArray().apply {
            events.forEach { put(installLogEventToJson(it)) }
        }
        return JSONObject().apply {
            put("count", events.size)
            distroId?.let { put("distroId", it) }
            put("sinceMs", sinceMs)
            put("events", arr)
        }
    }
}

internal fun applyProotStringPreference(
    prootManager: sh.haven.core.local.ProotManager,
    key: String,
    rawValue: Any?,
) {
    when (key) {
        "proot_engine" -> {
            val raw = (rawValue as? String)?.trim()
                ?: throw McpError(-32602, "value must be one of proot|chroot-ng for $key")
            val engine = sh.haven.core.local.ProotManager.GuestEngine.entries
                .firstOrNull { it.id.equals(raw, ignoreCase = true) }
                ?: throw McpError(-32602, "Invalid guest engine \"$raw\" for $key (expected proot or chroot-ng)")
            prootManager.setGuestEngine(engine)
        }
        "proot_dns_mode" -> {
            val raw = (rawValue as? String)?.trim()
                ?: throw McpError(-32602, "value must be one of system|public|custom for $key")
            val mode = sh.haven.core.local.ProotDnsMode.entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
                ?: throw McpError(-32602, "Invalid DNS mode \"$raw\" for $key (expected system, public or custom)")
            prootManager.setDnsMode(mode)
        }
        "proot_dns_servers" -> {
            val raw = (rawValue as? String)
                ?: throw McpError(-32602, "value must be a string of IP or IP:port literals for $key")
            // Reject early rather than writing a resolv.conf that silently never
            // resolves - a hostname here cannot be resolved (#446, #838).
            if (
                raw.isNotBlank() &&
                (sh.haven.core.local.ProotDns.invalidTokens(raw).isNotEmpty() ||
                    sh.haven.core.local.ProotDns.parseServers(raw).isEmpty())
            ) {
                throw McpError(-32602, "Invalid DNS server entry in \"$raw\" for $key (expected IP or IP:port literals)")
            }
            prootManager.setDnsServers(raw)
        }
    }
}
