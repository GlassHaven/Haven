package sh.haven.app.agent

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.desktop.DesktopInputHandle
import sh.haven.core.data.desktop.DesktopSessionRegistry
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mcp.McpError

/**
 * The desktop MCP tools (#mcp-backbone Stage 5, Layer E): desktop-environment
 * lifecycle (list/install/uninstall/start/stop DEs on the active distro via
 * [LocalSessionManager]'s ProotManager/DesktopManager), running-session +
 * window listings and screen capture / tab tap-scroll-clipboard over
 * [DesktopSessionRegistry], guest-app launching, and opening a terminal in the
 * desktop. Cross-cutting shared helpers come from [ctx] (profileLabel for
 * consent summaries, ctx.backgroundScope for async DE lifecycle, attachAgentShell
 * for the desktop terminal); the pure helpers (requireIntArg, encodeCapture,
 * desktopByIdOrThrow, desktopToJson) are top-level functions in McpTools.kt.
 */
internal class DesktopToolProvider(
    private val ctx: ToolContext,
    private val desktopSessionRegistry: DesktopSessionRegistry,
    private val localSessionManager: LocalSessionManager,
) : ToolProvider {

    private val prootManager get() = localSessionManager.prootManager
    private val systemVmManager get() = localSessionManager.systemVmManager

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "list_desktop_sessions" to ToolHandler(
            description = "List open remote-desktop tabs (VNC/RDP/SPICE) by connection profile, with their live status (connecting, connected, disconnected, error). These are Desktop-screen tabs, not transport sessions — a VNC/RDP/SPICE-over-SSH desktop has its SSH tunnel in list_sessions and its own connect state here. 'disconnected' means the tab is still open but its session ended (server logoff / transport death) — reconnect with connect_profile, which replaces the dead tab. Use after connect_profile to confirm a desktop reached 'connected', and after disconnect_profile to confirm the tab is gone (profile absent from the list).",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listDesktopSessions() },

        "list_guest_apps" to ToolHandler(
            description = "List the GUI applications installed in the active proot guest, discovered from its `.desktop` files (the same source an xfce4 application menu reads). Use this to find an app to launch with `present_app` without knowing its exact command. Returns { count, iconsResolved, apps:[{ name, exec, hasIcon, categories }] } sorted by name; `exec` is the runnable guest command (field codes stripped) you pass straight to present_app's `command`. `hasIcon` indicates whether a decodable icon was resolved (icons themselves stay on-device for the launcher UI). Skips NoDisplay/Terminal/non-application entries.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { listGuestApps() },

        "list_desktop_environments" to ToolHandler(
            description = "Slim DE-only read of inspect_proot. Filters to DEs that have a package list for the active distro's family (matches the UI filter). Each entry includes per-family compatibility (Stable/Experimental/Broken), an Experimental note when relevant, installed?, and running? state.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listDesktopEnvironments() },

        "install_desktop" to ToolHandler(
            description = "Install a desktop environment on the active distro. Calls ProotManager.setupDesktop which downloads packages, configures VNC, and writes the launcher. Poll `inspect_proot.desktopSetupState` for progress. Failures are attributed to a DePhase (Packages / VncConfig / Marker) in both the state and the install log.",
            inputSchema = objectSchema {
                string("deId", "Desktop environment id (e.g. \"xfce4\", \"openbox\", \"labwc-native\").", required = true)
                string("vncPassword", "VNC password. Defaults to empty (SecurityTypes None).")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                val active = localSessionManager.prootManager.activeDistro
                val family = active.family
                val compat = de?.spec?.compatibilityOn(family)
                val suffix = when (compat) {
                    sh.haven.core.local.proot.Compatibility.Experimental -> " — experimental on ${family.name}"
                    sh.haven.core.local.proot.Compatibility.Broken -> " — known broken on ${family.name}"
                    else -> ""
                }
                val label = de?.label ?: deId
                "Install ${label} on ${active.label}${suffix}?"
            },
        ) { args -> installDesktopTool(args) },

        "uninstall_desktop" to ToolHandler(
            description = "Remove a desktop environment from the active distro. Stops it first if running. Calls ProotManager.uninstallDesktop.",
            inputSchema = objectSchema {
                string("deId", "Desktop environment id to uninstall.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                val active = localSessionManager.prootManager.activeDistro
                "Uninstall ${de?.label ?: deId} from ${active.label}?"
            },
        ) { args -> uninstallDesktopTool(args) },

        "start_desktop" to ToolHandler(
            description = "Start an installed desktop environment on the active distro. Calls DesktopManager.startDesktop; the launch is asynchronous. Returns the allocated display + vncPort so callers can connect a VNC client. Poll `inspect_proot.desktopEnvironments[].running` (or list_desktop_environments) to confirm RUNNING state. NestedWayland DEs (Sway, Hyprland, niri) bring up a wlroots/smithay compositor on the headless backend inside the rootfs and expose it via wayvnc on the returned port; X11Vnc DEs spawn Xvnc + the desktop; NativeCompositor runs the JNI labwc bridge.",
            inputSchema = objectSchema {
                string("deId", "Desktop environment id to start.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                val active = localSessionManager.prootManager.activeDistro
                "Start ${de?.label ?: deId} on ${active.label}?"
            },
        ) { args -> startDesktopTool(args) },

        "stop_desktop" to ToolHandler(
            description = "Stop a running desktop environment. Tears down the compositor / Xvnc process tree and releases the display number.",
            inputSchema = objectSchema {
                string("deId", "Desktop environment id to stop.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                "Stop ${de?.label ?: deId}?"
            },
        ) { args -> stopDesktopTool(args) },

        "list_system_vm_images" to ToolHandler(
            description = "List the stored system-VM disk images (#326) and the current VM's state. A system VM is a full QEMU Linux VM (x86_64 or aarch64) booted inside the active proot and viewed over VNC on loopback — distinct from a desktop environment (list_desktop_environments) and the USB-drive appliance (#287). Returns { vm: { status, vncPort, arch, accel, accelReason }, host: { arch, kvm: { usable, detail } }, count, images:[{ id, label, sizeBytes, arch }] }. `status` is one of stopped/starting/running/error; when running, `vncPort` is the loopback port a VNC connection points at (create_connection type=VNC host=127.0.0.1 vncPort=<port>). `host.kvm` is a live probe of /dev/kvm — on retail arm64 phones it is absent because the vendor hypervisor owns EL2, so guests run under TCG emulation. Images are qcow2, normalised on import.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listSystemVmImages() },

        "import_system_vm_image" to ToolHandler(
            description = "Import a bootable disk image as a system VM (#326). `source` is an http(s) URL or an on-device file path; it is downloaded/copied then normalised to qcow2 via `qemu-img convert` (raw/qcow2/vdi/vmdk in, qcow2 out) under the app cache. Provide a `label`; `id` defaults to a slug of the label. Optional `sha256` is verified against the SOURCE bytes. `arch` records which CPU the image is for — a qcow2 does not say, and booting an arm64 rootfs on the x86_64 target just hangs — and decides which qemu target is installed (Debian: qemu-system-arm for aarch64). Installs qemu in the active distro on first use (needs a VNC-capable qemu — Debian's has it, Alpine's does not). Synchronous: returns { id, label, sizeBytes, arch } when the converted image is ready. Then boot it with start_system_vm.",
            inputSchema = objectSchema {
                string("label", "Human-readable name for the image (e.g. \"Debian 12\").", required = true)
                string("source", "http(s) URL or on-device file path to a bootable disk image (.qcow2/.img/.iso/.vdi/.vmdk).", required = true)
                string("id", "Image id slug (lowercase letters, digits, . _ -). Defaults to a slug of the label.")
                string("sha256", "Optional SHA-256 of the source bytes to verify after download.")
                string("arch", "Guest CPU architecture of the image. Default x86_64.", enum = listOf("x86_64", "aarch64"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Import system-VM image \"${args.optString("label")}\" from ${args.optString("source")}?" },
        ) { args -> importSystemVmImage(args) },

        "start_system_vm" to ToolHandler(
            description = "Boot a stored system-VM image (#326) with a VNC display on a free loopback port, and wait for the VNC server to bind (up to ~20s; a timeout means this distro's qemu has no VNC — try a Debian image/distro). The image's recorded arch picks the machine: x86_64 gets `-M pc -vga std`, aarch64 gets `-M virt` with UEFI firmware, virtio-gpu and USB HID (installing the distro's edk2 package if needed). One VM at a time — call stop_system_vm first to replace a running one. Does NOT open a viewer (MCP has no UI) — returns { imageId, status, arch, accel, accelReason, vncHost, vncPort, hint } so you connect it yourself: create_connection type=VNC host=127.0.0.1 vncPort=<vncPort>, then connect_profile. `accel` is the resolved reality, not a request: KVM needs a usable /dev/kvm AND a guest arch matching the host, which retail arm64 phones cannot offer, so expect TCG and a slow (~2 min) but usable boot. `accel=kvm` fails fast when it is impossible rather than booting slowly; `accel=auto` (default) falls back to TCG if a KVM boot does not come up.",
            inputSchema = objectSchema {
                string("imageId", "Image id from list_system_vm_images.", required = true)
                integer("memMb", "Guest RAM in MiB. Default 2048.")
                integer("cpus", "Guest vCPUs. Default 2.")
                string("accel", "auto = KVM when genuinely possible else TCG (default); kvm = insist, erroring out if impossible; tcg = force emulation.", enum = listOf("auto", "kvm", "tcg"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Boot system VM '${args.optString("imageId")}'?" },
        ) { args -> startSystemVm(args) },

        "stop_system_vm" to ToolHandler(
            description = "Power off / kill the running system VM (#326) and release its loopback VNC port. Idempotent — a no-op if none is running. Kills the whole qemu process tree inside the proot.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Stop the running system VM?" },
        ) { _ -> stopSystemVm() },

        "delete_system_vm_image" to ToolHandler(
            description = "Delete a stored system-VM image (#326) — removes its qcow2 plus its label/arch sidecars. Stops the VM first if it is the one currently running.",
            inputSchema = objectSchema {
                string("imageId", "Image id to delete (from list_system_vm_images).", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete system-VM image '${args.optString("imageId")}'?" },
        ) { args -> deleteSystemVmImage(args) },

        "read_desktop_log" to ToolHandler(
            description = "Read a running (or just-failed) desktop's RUNTIME logs — distinct from inspect_proot, which only covers install state. For nested-Wayland DEs (Sway / Hyprland / niri) returns the compositor's own stdout/stderr (compositor.log: the wlr/[ERROR] lines, output-enable, buffer-allocation failures) plus Haven's captured launch-process output (the `[haven]` progress markers + wayvnc lines). This is the diagnostic for grey-screen / no-frames / compositor-refuses-to-start issues — the data that otherwise requires opening a proot shell. Pass deId to target one DE; omit for all running desktops.",
            inputSchema = objectSchema {
                string("deId", "Desktop environment id (e.g. \"sway\"). Omit for all running desktops.")
                integer("maxChars", "Cap compositor.log to its last N chars. Default 4000.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readDesktopLog(args) },

        "list_desktop_windows" to ToolHandler(
            description = "Enumerate the visible top-level windows on a running desktop (deId), so an agent can target a specific application window (e.g. KiCad's schematic editor vs. PCB editor) before capturing it. Returns { deId, count, windows:[{id,title,x,y,width,height}] }. Works on X11/VNC desktops (via xdotool) and Sway nested-Wayland desktops (via swaymsg get_tree); other nested-Wayland compositors (Hyprland/niri/cage) aren't enumerable yet — use capture_desktop for a whole-output screenshot there. Installs the X11 capture toolset (xdotool + ImageMagick) on first use.",
            inputSchema = objectSchema {
                string("deId", "Desktop environment id (e.g. \"xfce4\") of a RUNNING X11/VNC desktop.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                "Let the agent list the windows open on desktop '${args.optString("deId")}'"
            },
        ) { args -> listDesktopWindows(args) },

        "capture_desktop" to ToolHandler(
            description = "Capture a screenshot of a running desktop (deId) and return it INLINE as an image the agent can see directly — no second port or file download. Works for both X11/VNC desktops (via ImageMagick `import`) and nested-Wayland desktops — Sway / Hyprland / niri / cage (via `grim`, the wlroots screenshooter; auto-installed on first use). Whole screen by default; a single window via windowId (from list_desktop_windows) is X11/VNC only — nested-Wayland captures the whole output. The image is downscaled to maxWidth and JPEG-encoded by default to stay cheap over the MCP tunnel. Captures inside the guest, so it works even when the user isn't on the VNC tab. Returns the image plus { deId, width, height, format, source, windowId?, windowTitle? }.",
            inputSchema = objectSchema {
                string("deId", "Desktop environment id (e.g. \"xfce4\") of a RUNNING X11/VNC desktop.", required = true)
                string("windowId", "Optional X11 window id from list_desktop_windows. Captures just that window (cropped to its geometry). Omit for the whole screen.")
                integer("maxWidth", "Downscale so the image is at most this many pixels wide. Default 1024 (clamped 160–4096).")
                string("format", "\"jpeg\" (default, smaller) or \"png\" (lossless, larger).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val win = args.optString("windowId").takeIf { it.isNotBlank() }
                val what = if (win != null) "a window on" else "the screen of"
                "Let the agent see $what desktop '${args.optString("deId")}'"
            },
        ) { args -> captureDesktop(args) },

        "capture_desktop_tab" to ToolHandler(
            description = "Capture what a remote-desktop VIEWER tab (RDP, VNC, or SPICE) is actually rendering, INLINE as an image — the framebuffer the user sees, with the server cursor composited on top at the tracked pointer position. This is distinct from capture_desktop, which screenshots an in-guest X11/VNC desktop; this one captures the RDP/VNC/SPICE client viewer (e.g. to verify colours and the cursor against a remote Windows/Linux server). Pass profileId to pick a tab (from list_desktop_sessions); omit it when exactly one desktop tab is open. Returns the image plus { profileId, protocol, width, height, hasCursor, cursorWidth?, cursorHeight?, hotspotX?, hotspotY?, pointerX?, pointerY?, format }.",
            inputSchema = objectSchema {
                string("profileId", "Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one tab is open.")
                integer("maxWidth", "Downscale so the image is at most this many pixels wide. Default 1280 (clamped 160–4096).")
                string("format", "\"jpeg\" (default, smaller) or \"png\" (lossless, larger).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${ctx.profileLabel(pid)}'" else "the open remote desktop"
                "Let the agent see what $who is rendering"
            },
        ) { args -> captureDesktopTab(args) },

        "tap_desktop_tab" to ToolHandler(
            description = "Click a point on a remote-desktop VIEWER tab (RDP/VNC/SPICE) — inject a mouse click into the remote server. Coordinates are in the REMOTE framebuffer's pixel space (the same space capture_desktop_tab reports: 0..width, 0..height), NOT Haven's own UI (that's tap_haven_ui). Pass profileId to pick a tab (from list_desktop_sessions); omit when exactly one desktop tab is open. Buttons follow X11: 1=left (default), 2=middle, 3=right. Keyboard typing is not yet supported (the session abstraction has no key verb). Returns { profileId, protocol, x, y, button }.",
            inputSchema = objectSchema {
                string("profileId", "Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one is open.")
                integer("x", "Remote framebuffer X (0..width from capture_desktop_tab).", required = true)
                integer("y", "Remote framebuffer Y (0..height from capture_desktop_tab).", required = true)
                integer("button", "X11 button: 1=left (default), 2=middle, 3=right.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${ctx.profileLabel(pid)}'" else "the open remote desktop"
                "Click (${args.optInt("x")},${args.optInt("y")}) on $who"
            },
        ) { args -> tapDesktopTab(args) },

        "scroll_desktop_tab" to ToolHandler(
            description = "Scroll a remote-desktop VIEWER tab (RDP/VNC/SPICE) by injecting mouse-wheel notches into the remote server. deltaY > 0 scrolls down, < 0 scrolls up; magnitude is the number of notches. Pass profileId to pick a tab (from list_desktop_sessions); omit when exactly one is open. Returns { profileId, protocol, deltaY }.",
            inputSchema = objectSchema {
                string("profileId", "Profile id (from list_desktop_sessions). Omit when exactly one is open.")
                integer("deltaY", "Wheel notches: >0 scrolls down, <0 scrolls up.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${ctx.profileLabel(pid)}'" else "the open remote desktop"
                "Scroll $who"
            },
        ) { args -> scrollDesktopTab(args) },

        "send_desktop_clipboard" to ToolHandler(
            description = "Set the clipboard on a remote-desktop VIEWER tab (RDP/VNC) to the given text, so it can be pasted inside the remote server (Ctrl+V / right-click paste). This is the closest substitute for typing while keyboard injection is unsupported. Pass profileId to pick a tab (from list_desktop_sessions); omit when exactly one is open. Returns { profileId, protocol, chars }.",
            inputSchema = objectSchema {
                string("profileId", "Profile id (from list_desktop_sessions). Omit when exactly one is open.")
                string("text", "Text to place on the remote clipboard.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${ctx.profileLabel(pid)}'" else "the open remote desktop"
                "Set $who clipboard (${args.optString("text").length} chars)"
            },
        ) { args -> sendDesktopClipboard(args) },

        "launch_app_in_desktop" to ToolHandler(
            description = "Launch a GUI application into a RUNNING desktop (deId). X11/VNC desktops get DISPLAY/XAUTHORITY; nested-Wayland desktops (Sway/Hyprland/niri/cage) get XDG_RUNTIME_DIR/WAYLAND_DISPLAY. The software-GL fallback (LIBGL_ALWAYS_SOFTWARE=1, GALLIUM_DRIVER=llvmpipe) is exported either way, so GPU-less GL apps like KiCad/eeschema don't crash their canvas. Optionally waits for the app's window to appear and returns its windowId — pass that to capture_desktop to screenshot just that window (window-wait/windowId need enumeration: X11 and Sway; on other nested-Wayland compositors the app still launches but no windowId is returned). The app keeps running after this returns. For looking at saved design FILES prefer view_file (headless, no desktop needed); use this when you need the live interactive app.",
            inputSchema = objectSchema {
                string("deId", "Desktop environment id (e.g. \"xfce4\") of a RUNNING X11/VNC desktop.", required = true)
                string("command", "Shell command to launch, e.g. 'eeschema /root/proj/board.kicad_sch'.", required = true)
                string("waitForWindowTitle", "If set, poll until a window whose title contains this substring (case-insensitive) appears; otherwise return the first new window seen.")
                integer("timeoutMs", "Max ms to wait for the window. Default 15000, clamped 0..60000. 0 = launch and return without waiting.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Launch '${args.optString("command")}' on desktop '${args.optString("deId")}'" },
        ) { args -> launchAppInDesktop(args) },

        "open_desktop_terminal" to ToolHandler(
            description = "Open an interactive local PRoot shell whose environment JOINS a RUNNING desktop (deId) — exports DISPLAY (X11/VNC) or WAYLAND_DISPLAY + XDG_RUNTIME_DIR (nested-Wayland / native labwc) — so you can drive the desktop's apps from the command line (e.g. launch/inspect GUI programs in the same session a user is viewing over VNC). Returns a sessionId usable with send_terminal_input / read_terminal_scrollback, plus the resolved display/waylandDisplay/xdgRuntimeDir. Always a fresh session (a reused plain shell would lack the display env). The desktop must already be RUNNING (start_desktop). Unlike launch_app_in_desktop (fire-and-forget single app), this gives you an interactive shell.",
            inputSchema = objectSchema {
                string("deId", "Desktop environment id of a RUNNING desktop (e.g. \"openbox\", \"xfce4\", \"sway\").", required = true)
                boolean("plain", "Skip the user's sessionManager preference and exec a bare login shell. Default false.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> openDesktopTerminal(args) },
    )

    private fun listDesktopSessions(): JSONObject {
        val statuses = desktopSessionRegistry.statuses.value
        val arr = JSONArray()
        for ((profileId, status) in statuses) {
            arr.put(JSONObject().apply {
                put("profileId", profileId)
                put("status", status.name.lowercase())
            })
        }
        return JSONObject().apply {
            put("count", statuses.size)
            put("desktops", arr)
        }
    }

    private suspend fun listGuestApps(): JSONObject {
        val dm = localSessionManager.desktopManager
        if (!dm.isUmlCageAvailable && !prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }
        val result = withContext(Dispatchers.IO) {
            dm.scanInstalledApps()
        }
        return JSONObject().apply {
            put("count", result.total)
            put("iconsResolved", result.iconsResolved)
            put("apps", JSONArray().apply {
                result.apps.forEach { app ->
                    put(JSONObject().apply {
                        put("name", app.name)
                        put("exec", app.exec)
                        put("hasIcon", app.iconPath != null)
                        put("categories", JSONArray().apply { app.categories.forEach { put(it) } })
                    })
                }
            })
        }
    }

    private suspend fun openDesktopTerminal(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "deId is required")
        val de = desktopByIdOrThrow(deId)
        val env = try {
            localSessionManager.desktopManager.resolveClientEnv(de)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "Failed to resolve desktop env for $deId")
        }
        // ctx.attachAgentShell is a function value — positional args only.
        val base = ctx.attachAgentShell(
            /* plain = */ args.optBoolean("plain", false),
            /* desktopEnv = */ env,
            /* reuse = */ false,
        )
        return base.apply {
            put("desktopDeId", de.spec.id)
            put("display", env["DISPLAY"] ?: JSONObject.NULL)
            put("waylandDisplay", env["WAYLAND_DISPLAY"] ?: JSONObject.NULL)
            put("xdgRuntimeDir", env["XDG_RUNTIME_DIR"] ?: JSONObject.NULL)
        }
    }

    private fun listDesktopEnvironments(): JSONObject {
        val pm = prootManager
        val active = pm.activeDistro
        val installedDes = pm.installedDesktops
        val runningDes = localSessionManager.desktopManager.desktops.value.keys
        val arr = JSONArray().apply {
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
        return JSONObject().apply {
            put("activeDistroId", active.id)
            put("activeFamily", active.family.name)
            put("count", arr.length())
            put("desktopEnvironments", arr)
        }
    }

    private suspend fun installDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        val vncPassword = args.optString("vncPassword", "")
        val pm = prootManager
        val active = pm.activeDistro
        if (active.family !in de.spec.packagesPerFamily.keys) {
            throw McpError(
                -32602,
                "${de.label} has no package list for ${active.family} — supported: " +
                    "${de.spec.packagesPerFamily.keys}",
            )
        }
        ctx.backgroundScope.launch {
            try {
                pm.setupDesktop(vncPassword, de)
            } catch (_: Exception) {
                // setupDesktop's catch already pushes Error state.
            }
        }
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("activeDistroId", active.id)
            put("compatibility", de.spec.compatibilityOn(active.family).name)
            de.spec.compatibilityNoteOn(active.family)?.let { put("compatibilityNote", it) }
            put("status", "started")
            put("poll", "inspect_proot.desktopSetupState")
        }
    }

    private suspend fun uninstallDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        val pm = prootManager
        localSessionManager.desktopManager.stopDesktop(de)
        pm.uninstallDesktop(de)
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("status", "uninstalled")
        }
    }

    private suspend fun startDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        if (de !in prootManager.installedDesktops) {
            throw McpError(
                -32602,
                "${de.label} is not installed — call install_desktop first.",
            )
        }
        val dm = localSessionManager.desktopManager
        dm.startDesktop(de)
        // DesktopManager.startDesktop stays at STARTING until a caller
        // confirms the VNC port is up and calls markRunning — the UI path
        // (DesktopViewModel.startDesktop) does this, but the MCP path
        // didn't, so MCP-started desktops sat at STARTING forever (task
        // #23). Do the same port-poll → markRunning here so the state
        // finalizes. Native (labwc) self-finalizes RUNNING via the JNI
        // bridge, so skip the poll for it. We don't open an in-app VNC
        // viewer (that's UI-only; McpTools has no DesktopViewModel) — the
        // returned vncPort lets an external VNC client connect.
        if (!de.isNative) {
            val port = dm.getVncPort(de) ?: 5901
            withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    if (dm.desktops.value[de]?.state ==
                        sh.haven.core.local.DesktopManager.DesktopState.ERROR
                    ) break
                    try {
                        java.net.Socket("127.0.0.1", port).close()
                        dm.markRunning(de)
                        break
                    } catch (_: Exception) {
                        kotlinx.coroutines.delay(500)
                    }
                }
            }
        }
        val instance = dm.desktops.value[de]
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("state", instance?.state?.name ?: "UNKNOWN")
            put("displayNumber", instance?.displayNumber ?: -1)
            put("vncPort", instance?.vncPort ?: -1)
            instance?.errorMessage?.let { put("errorMessage", it) }
            put("launchKind", de.spec.launch::class.simpleName ?: "unknown")
            put("poll", "list_desktop_environments[].running")
        }
    }

    private fun stopDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        localSessionManager.desktopManager.stopDesktop(de)
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("status", "stopped")
        }
    }

    // --- System VM (#326) ---

    private fun systemVmStateJson(): JSONObject {
        val st = systemVmManager.state.value
        return JSONObject().apply {
            put("status", (st?.status?.name ?: "STOPPED").lowercase())
            put("vncPort", st?.vncPort ?: JSONObject.NULL)
            put("arch", st?.arch?.id ?: JSONObject.NULL)
            put("accel", st?.accel?.name?.lowercase() ?: JSONObject.NULL)
            // Why it's TCG is the question every "this is slow" report starts
            // with; carry the probe's own answer instead of making the agent
            // guess from the timing.
            put("accelReason", st?.accelReason?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        }
    }

    /** The live /dev/kvm verdict for this device, so an agent can report acceleration without booting a VM to find out. */
    private fun systemVmHostJson(): JSONObject {
        val kvm = systemVmManager.kvmStatus()
        return JSONObject().apply {
            put("arch", systemVmManager.hostArch()?.id ?: JSONObject.NULL)
            put(
                "kvm",
                JSONObject().apply {
                    put("usable", kvm.usable)
                    put("detail", kvm.detail)
                },
            )
        }
    }

    private fun listSystemVmImages(): JSONObject {
        val arr = JSONArray()
        systemVmManager.listImages().forEach { img ->
            arr.put(JSONObject().apply {
                put("id", img.id)
                put("label", img.label)
                put("sizeBytes", img.sizeBytes)
                put("arch", img.arch.id)
            })
        }
        return JSONObject().apply {
            put("vm", systemVmStateJson())
            put("host", systemVmHostJson())
            put("count", arr.length())
            put("images", arr)
        }
    }

    private suspend fun importSystemVmImage(args: JSONObject): JSONObject {
        val label = args.optString("label").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "label is required")
        val source = args.optString("source").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "source is required")
        val id = args.optString("id").takeIf { it.isNotBlank() }
            ?: label.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
        if (id.isEmpty()) throw McpError(-32602, "could not derive an image id from the label; pass an explicit id")
        val sha = args.optString("sha256").takeIf { it.isNotBlank() }
        // An unrecognised arch resolves to x86_64 rather than erroring — but the
        // schema enumerates the two, so a typo is caught before it gets here.
        val arch = sh.haven.core.local.VmArch.fromId(args.optString("arch").takeIf { it.isNotBlank() })
        val img = try {
            systemVmManager.importImage(id, label, source, sha, arch)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "import failed")
        }
        return JSONObject().apply {
            put("id", img.id)
            put("label", img.label)
            put("sizeBytes", img.sizeBytes)
            put("arch", img.arch.id)
        }
    }

    private suspend fun startSystemVm(args: JSONObject): JSONObject {
        val imageId = args.optString("imageId").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "imageId is required")
        val memMb = args.optInt("memMb", sh.haven.core.local.SystemVmManager.DEFAULT_MEM_MB)
        val cpus = args.optInt("cpus", sh.haven.core.local.SystemVmManager.DEFAULT_CPUS)
        val accelMode = when (args.optString("accel").trim().lowercase()) {
            "", "auto" -> sh.haven.core.local.AccelMode.AUTO
            "kvm" -> sh.haven.core.local.AccelMode.KVM
            "tcg" -> sh.haven.core.local.AccelMode.TCG
            else -> throw McpError(-32602, "accel must be one of auto, kvm, tcg")
        }
        val st = try {
            systemVmManager.startImage(imageId, memMb, cpus, accelMode)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "start failed")
        }
        return JSONObject().apply {
            put("imageId", imageId)
            put("status", st.status.name.lowercase())
            put("arch", st.arch.id)
            put("accel", st.accel.name.lowercase())
            put("accelReason", st.accelReason)
            put("vncHost", "127.0.0.1")
            put("vncPort", st.vncPort ?: JSONObject.NULL)
            put("hint", "create_connection type=VNC host=127.0.0.1 vncPort=${st.vncPort} then connect_profile")
        }
    }

    private suspend fun stopSystemVm(): JSONObject {
        systemVmManager.stop()
        return JSONObject().apply { put("status", "stopped") }
    }

    private suspend fun deleteSystemVmImage(args: JSONObject): JSONObject {
        val imageId = args.optString("imageId").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "imageId is required")
        systemVmManager.deleteImage(imageId)
        return JSONObject().apply {
            put("imageId", imageId)
            put("status", "deleted")
        }
    }

    private fun readDesktopLog(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
        val maxChars = args.optInt("maxChars", 4000).coerceIn(200, 50000)
        val dm = localSessionManager.desktopManager
        val instances = dm.desktops.value
        val targets = if (deId != null) {
            val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
                .firstOrNull { it.spec.id == deId }
                ?: throw McpError(-32602, "Unknown deId: $deId")
            listOf(de)
        } else {
            instances.keys.toList()
        }
        val arr = JSONArray()
        for (de in targets) {
            val inst = instances[de]
            val compLog = dm.compositorLogFor(de)
                ?.let { if (it.length > maxChars) it.takeLast(maxChars) else it }
            arr.put(JSONObject().apply {
                put("deId", de.spec.id)
                put("label", de.label)
                put("state", inst?.state?.name ?: "STOPPED")
                put("displayNumber", inst?.displayNumber ?: -1)
                put("vncPort", inst?.vncPort ?: -1)
                put("launchKind", de.spec.launch::class.simpleName ?: "unknown")
                inst?.errorMessage?.let { put("errorMessage", it) }
                put("capturedOutput", JSONArray(dm.capturedOutputFor(de)))
                put("compositorLog", compLog ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("count", arr.length())
            put("desktops", arr)
        }
    }

    private suspend fun listDesktopWindows(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = desktopByIdOrThrow(deId)
        val windows = try {
            localSessionManager.desktopManager.listWindows(de)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "Window enumeration failed")
        }
        val arr = JSONArray()
        windows.forEach { w ->
            arr.put(JSONObject().apply {
                put("id", w.id)
                put("title", w.title)
                put("x", w.x)
                put("y", w.y)
                put("width", w.width)
                put("height", w.height)
            })
        }
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("count", arr.length())
            put("windows", arr)
        }
    }

    private suspend fun captureDesktop(args: JSONObject): ToolResult {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = desktopByIdOrThrow(deId)
        val dm = localSessionManager.desktopManager
        val windowId = args.optString("windowId").takeIf { it.isNotBlank() }
        val maxWidth = args.optInt("maxWidth", 1024).coerceIn(160, 4096)
        val format = if (args.optString("format", "jpeg").lowercase() == "png") "png" else "jpeg"

        // When a window is requested, resolve its geometry first so we can
        // crop the full-screen capture to it app-side (a single reliable
        // capture path beats per-window-id capture on bare Xvnc).
        var crop: IntArray? = null
        var windowTitle: String? = null
        if (windowId != null) {
            val win = try {
                dm.listWindows(de).firstOrNull { it.id == windowId }
            } catch (e: Exception) {
                throw McpError(-32603, e.message ?: "Window lookup failed")
            } ?: throw McpError(
                -32602,
                "Window '$windowId' not found on '${de.spec.id}' — call list_desktop_windows first",
            )
            crop = intArrayOf(win.x, win.y, win.width, win.height)
            windowTitle = win.title
        }

        val png = try {
            dm.capture(de)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "Capture failed")
        }
        val (b64, w, h) = withContext(Dispatchers.Default) {
            encodeCapture(png, crop, maxWidth, format)
        }
        return ToolResult.Image(
            base64 = b64,
            mimeType = if (format == "jpeg") "image/jpeg" else "image/png",
            structured = JSONObject().apply {
                put("deId", de.spec.id)
                put("width", w)
                put("height", h)
                put("format", format)
                put("source", "guest")
                windowId?.let { put("windowId", it) }
                windowTitle?.let { put("windowTitle", it) }
            },
        )
    }

    private suspend fun captureDesktopTab(args: JSONObject): ToolResult {
        val explicitPid = args.optString("profileId").takeIf { it.isNotBlank() }
        val handles = desktopSessionRegistry.frameHandles()
        val pid = explicitPid ?: when (handles.size) {
            1 -> handles.keys.first()
            0 -> throw McpError(-32602, "No remote-desktop tab is open. Use connect_profile, then list_desktop_sessions.")
            else -> throw McpError(
                -32602,
                "Multiple desktop tabs open (${handles.keys.joinToString()}); pass profileId.",
            )
        }
        val handle = desktopSessionRegistry.frameHandle(pid)
            ?: throw McpError(-32602, "No capturable desktop tab for profile '$pid' (call list_desktop_sessions).")
        val src = handle.frame()
            ?: throw McpError(-32603, "Desktop '$pid' has not rendered a frame yet — wait for it to connect.")

        val maxWidth = args.optInt("maxWidth", 1280).coerceIn(160, 4096)
        val format = if (args.optString("format", "jpeg").lowercase() == "png") "png" else "jpeg"

        val cursor = handle.cursor()
        val (px, py) = handle.pointer()

        val (b64, w, h) = withContext(Dispatchers.Default) {
            // Composite the cursor onto a mutable copy so the source frame
            // (shared with the live viewer) is never mutated.
            var bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            if (cursor != null) {
                android.graphics.Canvas(bmp).drawBitmap(
                    cursor.bitmap,
                    (px - cursor.hotspotX).toFloat(),
                    (py - cursor.hotspotY).toFloat(),
                    null,
                )
            }
            val fullW = bmp.width
            val fullH = bmp.height
            if (maxWidth in 1 until bmp.width) {
                val nh = (bmp.height.toFloat() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
                bmp = Bitmap.createScaledBitmap(bmp, maxWidth, nh, true)
            }
            val out = java.io.ByteArrayOutputStream()
            if (format == "jpeg") {
                bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
            } else {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Triple(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), fullW, fullH)
        }

        return ToolResult.Image(
            base64 = b64,
            mimeType = if (format == "jpeg") "image/jpeg" else "image/png",
            structured = JSONObject().apply {
                put("profileId", pid)
                put("protocol", handle.protocol)
                put("width", w)
                put("height", h)
                put("hasCursor", cursor != null)
                if (cursor != null) {
                    put("cursorWidth", cursor.bitmap.width)
                    put("cursorHeight", cursor.bitmap.height)
                    put("hotspotX", cursor.hotspotX)
                    put("hotspotY", cursor.hotspotY)
                    put("pointerX", px)
                    put("pointerY", py)
                }
                put("format", format)
            },
        )
    }

    private fun resolveDesktopInput(args: JSONObject): Pair<String, DesktopInputHandle> {
        val explicitPid = args.optString("profileId").takeIf { it.isNotBlank() }
        val handles = desktopSessionRegistry.inputHandles()
        val pid = explicitPid ?: when (handles.size) {
            1 -> handles.keys.first()
            0 -> throw McpError(-32602, "No remote-desktop tab is open. Use connect_profile, then list_desktop_sessions.")
            else -> throw McpError(-32602, "Multiple desktop tabs open (${handles.keys.joinToString()}); pass profileId.")
        }
        val handle = desktopSessionRegistry.inputHandle(pid)
            ?: throw McpError(-32602, "No controllable desktop tab for profile '$pid' (call list_desktop_sessions).")
        return pid to handle
    }

    private fun tapDesktopTab(args: JSONObject): JSONObject {
        val (pid, h) = resolveDesktopInput(args)
        val x = requireIntArg(args, "x")
        val y = requireIntArg(args, "y")
        val button = args.optInt("button", 1).coerceIn(1, 7)
        h.mouseClick(x, y, button)
        return JSONObject().apply {
            put("profileId", pid); put("protocol", h.protocol)
            put("x", x); put("y", y); put("button", button)
        }
    }

    private fun scrollDesktopTab(args: JSONObject): JSONObject {
        val (pid, h) = resolveDesktopInput(args)
        val deltaY = requireIntArg(args, "deltaY")
        h.mouseWheel(deltaY)
        return JSONObject().apply {
            put("profileId", pid); put("protocol", h.protocol); put("deltaY", deltaY)
        }
    }

    private fun sendDesktopClipboard(args: JSONObject): JSONObject {
        val (pid, h) = resolveDesktopInput(args)
        if (!args.has("text")) throw McpError(-32602, "text is required")
        val text = args.optString("text")
        h.clipboard(text)
        return JSONObject().apply {
            put("profileId", pid); put("protocol", h.protocol); put("chars", text.length)
        }
    }

    private suspend fun launchAppInDesktop(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "deId is required")
        val command = args.optString("command").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "command is required")
        val waitTitle = args.optString("waitForWindowTitle").takeIf { it.isNotBlank() }
        val timeoutMs = args.optInt("timeoutMs", 15000).coerceIn(0, 60000)
        val de = desktopByIdOrThrow(deId)
        val dm = localSessionManager.desktopManager

        // Snapshot existing windows so we can spot the NEW one.
        val before: Set<String> = try {
            dm.listWindows(de).map { it.id }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val appId = try {
            dm.launchApp(de, command)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "launch failed")
        }

        var winId: String? = null
        var winTitle = ""
        if (timeoutMs > 0) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && winId == null) {
                kotlinx.coroutines.delay(600)
                val wins = try { dm.listWindows(de) } catch (e: Exception) { emptyList() }
                val w = if (waitTitle != null) {
                    wins.firstOrNull { it.title.contains(waitTitle, ignoreCase = true) }
                } else {
                    wins.firstOrNull { it.id !in before && it.title.isNotBlank() }
                        ?: wins.firstOrNull { it.id !in before }
                }
                if (w != null) {
                    winId = w.id
                    winTitle = w.title
                }
            }
        }
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("appId", appId)
            put("command", command)
            if (winId != null) {
                put("windowId", winId)
                put("windowTitle", winTitle)
                put("hint", "pass windowId to capture_desktop to screenshot just this window")
            } else {
                put("windowId", JSONObject.NULL)
                put(
                    "note",
                    if (timeoutMs == 0) {
                        "launched without waiting for a window"
                    } else {
                        "no matching window within ${timeoutMs}ms (still starting? try list_desktop_windows)"
                    },
                )
            }
        }
    }
}
