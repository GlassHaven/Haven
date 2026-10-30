package sh.haven.app.agent

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.desktop.DesktopFrameHandle
import sh.haven.core.data.desktop.DesktopInputHandle
import sh.haven.core.data.desktop.DesktopSessionRegistry
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mcp.McpError
import sh.haven.core.scan.OcrBbox
import sh.haven.core.scan.OcrNeighbor
import sh.haven.core.scan.OcrNode
import sh.haven.core.scan.OcrScreenIndex
import sh.haven.core.scan.TextRecognizer
import kotlin.math.roundToInt

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
    private val textRecognizer: TextRecognizer? = null,
) : ToolProvider {

    override val defaultCategory = sh.haven.core.data.agent.McpToolCategory.DESKTOP

    private val prootManager get() = localSessionManager.prootManager
    private val systemVmManager get() = localSessionManager.systemVmManager
    private val lastInspectedIndex = java.util.concurrent.ConcurrentHashMap<String, OcrScreenIndex>()

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
            description = "Boot a stored system-VM image (#326) with a VNC display on a free loopback port, and wait for the VNC server to bind (up to ~20s; a timeout means this distro's qemu has no VNC — try a Debian image/distro). The image's recorded arch picks the machine: x86_64 gets `-M pc -vga std`, aarch64 gets `-M virt` with UEFI firmware, virtio-gpu and USB HID (installing the distro's edk2 package if needed). Pass `usbRedirPort` (from `start_usbredir_export`, default 4000) to attach a phone-connected USB device directly into the guest over `-device qemu-xhci -chardev socket,id=usbredir0,host=127.0.0.1,port=<usbRedirPort> -device usb-redir,chardev=usbredir0` (#613). One VM at a time — call stop_system_vm first to replace a running one. Does NOT open a viewer (MCP has no UI) — returns { imageId, status, arch, accel, accelReason, vncHost, vncPort, usbRedirPort, hint } so you connect it yourself: create_connection type=VNC host=127.0.0.1 vncPort=<vncPort>, then connect_profile. `accel` is the resolved reality, not a request: KVM needs a usable /dev/kvm AND a guest arch matching the host, which retail arm64 phones cannot offer, so expect TCG and a slow (~2 min) but usable boot. `accel=kvm` fails fast when it is impossible rather than booting slowly; `accel=auto` (default) falls back to TCG if a KVM boot does not come up.",
            inputSchema = objectSchema {
                string("imageId", "Image id from list_system_vm_images.", required = true)
                integer("memMb", "Guest RAM in MiB. Default 2048.")
                integer("cpus", "Guest vCPUs. Default 2.")
                string("accel", "auto = KVM when genuinely possible else TCG (default); kvm = insist, erroring out if impossible; tcg = force emulation.", enum = listOf("auto", "kvm", "tcg"))
                integer("usbRedirPort", "Optional loopback TCP port of a running usbredir export (from start_usbredir_export, default 4000) to attach to the guest over qemu-xhci + usb-redir (#613).")
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
            description = "Capture what a remote-desktop VIEWER tab (RDP, VNC, SPICE, or GLKVM) is actually rendering, INLINE as an image — the framebuffer the user sees, with the server cursor composited on top at the tracked pointer position. This is distinct from capture_desktop, which screenshots an in-guest X11/VNC desktop; this one captures the RDP/VNC/SPICE client viewer (e.g. to verify colours and the cursor against a remote Windows/Linux server). Optionally pass regionX/regionY/regionWidth/regionHeight to crop to a specific region of interest. Pass profileId to pick a tab (from list_desktop_sessions); omit it when exactly one desktop tab is open. Returns the image plus { profileId, protocol, width, height, imageWidth, imageHeight, region?, hasCursor, cursorWidth?, cursorHeight?, hotspotX?, hotspotY?, pointerX?, pointerY?, format }.",
            inputSchema = objectSchema {
                string("profileId", "Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one tab is open.")
                integer("maxWidth", "Downscale so the image is at most this many pixels wide. Default 1280 (clamped 160–4096).")
                string("format", "\"jpeg\" (default, smaller) or \"png\" (lossless, larger).")
                boolean("forceRefresh", "For poll-based protocols (GLKVM), force a fresh snapshot pull even if no input occurred since the last frame. Default false.")
                integer("regionX", "Optional crop region left X in full framebuffer pixels.")
                integer("regionY", "Optional crop region top Y in full framebuffer pixels.")
                integer("regionWidth", "Optional crop region width in framebuffer pixels.")
                integer("regionHeight", "Optional crop region height in framebuffer pixels.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${ctx.profileLabel(pid)}'" else "the open remote desktop"
                "Let the agent see what $who is rendering"
            },
        ) { args -> captureDesktopTab(args) },

        "tap_desktop_tab" to ToolHandler(
            description = "Click one or more points on a remote-desktop VIEWER tab (RDP/VNC/SPICE/GLKVM) — inject mouse clicks into the remote server. Coordinates are in the REMOTE framebuffer's pixel space (the same space capture_desktop_tab and inspect_desktop_tab report: 0..width, 0..height), NOT Haven's own UI (that's tap_haven_ui). Pass either (x, y) for a single target or points=[{x, y, button?, clicks?, delayMs?}, ...] to click a sequence of points in one round-trip (e.g. selecting multiple checkboxes and closing a dropdown). Set inspectAfter=\"diff\" (or \"lines\"/\"at\") to wait settleMs and return the post-click RapidOCR screen diff/observation in the same call, or returnScreenshot=true to return a fresh screenshot inline. Buttons follow X11: 1=left (default), 2=middle, 3=right. Pass clicks=2 for a double-click. Returns { profileId, protocol, x, y, button, clicks, pointsClicked?, inspection? }.",
            inputSchema = objectSchema {
                string("profileId", "Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one is open.")
                integer("x", "Remote framebuffer X (0..width from capture_desktop_tab). Required unless points is provided.")
                integer("y", "Remote framebuffer Y (0..height from capture_desktop_tab). Required unless points is provided.")
                integer("button", "X11 button: 1=left (default), 2=middle, 3=right.")
                integer("clicks", "Number of clicks: 1=single click (default), 2=double-click (clamped 1..3).")
                objectArray("points", "Optional sequence of points to click in order (1..20 items). Each item specifies {x, y, button?, clicks?, delayMs?}.") {
                    integer("x", "Remote framebuffer X.", required = true)
                    integer("y", "Remote framebuffer Y.", required = true)
                    integer("button", "Optional X11 button (default inherits top-level button or 1).")
                    integer("clicks", "Optional click count 1..3 (default inherits top-level clicks or 1).")
                    integer("delayMs", "Optional delay in ms before this click (default 80ms for subsequent points, clamped 0..5000).")
                }
                string("inspectAfter", "Optional post-click RapidOCR inspection mode to run after settleMs: \"none\" (default), \"diff\" (added/removed lines vs before the click), \"lines\" (compact line list), \"at\", or \"tree\".", enum = listOf("none", "diff", "lines", "at", "tree"))
                boolean("returnScreenshot", "If true, wait settleMs after clicking and return a fresh screenshot inline. Default false.")
                integer("settleMs", "Milliseconds to wait before post-action inspection/screenshot. Default 250 (clamped 0..5000).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${ctx.profileLabel(pid)}'" else "the open remote desktop"
                val pts = args.optJSONArray("points")
                if (pts != null && pts.length() > 0) {
                    "Click ${pts.length()} point(s) on $who"
                } else {
                    val clicks = args.optInt("clicks", 1).coerceIn(1, 3)
                    val verb = if (clicks == 2) "Double-click" else "Click"
                    "$verb (${args.optInt("x")},${args.optInt("y")}) on $who"
                }
            },
        ) { args -> tapDesktopTab(args) },

        "inspect_desktop_tab" to ToolHandler(
            description = "Run RapidOCR + a 2D k-d tree spatial index over a remote-desktop VIEWER tab (VNC, RDP, SPICE, or GLKVM) in full framebuffer pixel coordinates (matching tap_desktop_tab). Uses perceptual tile-grid caching to ignore HDMI/JPEG noise and GLKVM hardware mouse-wobbler motion outside text boxes, plus per-line crop caching so partial screen updates only re-recognize changed lines. Pass regionX/regionY/regionWidth/regionHeight to restrict OCR to a region of interest while keeping coordinates in full-framebuffer space. Supports five query modes: (1) mode=\"at\" (or pass x and y): coordinate k-d tree lookup returning containing elements and k nearest neighbors; (2) mode=\"find\" (or pass query): text search (substring or exact=true, optionally restricted to minX..maxX / minY..maxY and ranked by nearX/nearY); (3) mode=\"lines\": compact flat list of detected lines [{id, text, score, bbox, center}] without per-word repetition; (4) mode=\"diff\": returns only addedLines, removedLines, and unchangedLineCount compared to the previous inspection on this tab; (5) mode=\"tree\" (default when no query or x/y given): full blocks -> lines -> words hierarchy.",
            inputSchema = objectSchema {
                string("profileId", "Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one tab is open.")
                string("mode", "Query mode: \"auto\" (default: coordinate lookup if x/y set, text search if query set, else full tree), \"at\", \"find\", \"lines\" (compact line list without word bloat), \"diff\" (added/removed lines since previous inspection), or \"tree\".", enum = listOf("auto", "at", "find", "lines", "diff", "tree"))
                integer("x", "Framebuffer X coordinate for 2D k-d tree nearest-neighbor / containment lookup.")
                integer("y", "Framebuffer Y coordinate for 2D k-d tree nearest-neighbor / containment lookup.")
                string("query", "Text to search for (case-insensitive substring by default, or full match when exact=true).")
                boolean("exact", "Require exact normalized text equality for query instead of substring match. Default false.")
                integer("nearX", "Optional anchor X coordinate to disambiguate multiple text matches by proximity.")
                integer("nearY", "Optional anchor Y coordinate to disambiguate multiple text matches by proximity.")
                integer("minX", "Optional minimum center X coordinate for text matches.")
                integer("maxX", "Optional maximum center X coordinate for text matches.")
                integer("minY", "Optional minimum center Y coordinate for text matches (e.g. minY=160 to skip browser tab/address bars).")
                integer("maxY", "Optional maximum center Y coordinate for text matches.")
                integer("regionX", "Optional ROI crop left X in full framebuffer pixels (all returned bboxes remain in full-framebuffer coordinates).")
                integer("regionY", "Optional ROI crop top Y in full framebuffer pixels.")
                integer("regionWidth", "Optional ROI crop width in framebuffer pixels.")
                integer("regionHeight", "Optional ROI crop height in framebuffer pixels.")
                integer("k", "Number of nearest 2D k-d tree neighbors to return (for coordinate lookup or per text match). Default 5 (clamped 0..50).")
                integer("maxRadius", "Optional maximum distance in pixels for k-d tree neighbor search.")
                string("level", "Filter detections by granularity: \"all\" (default), \"word\", or \"line\".", enum = listOf("all", "word", "line"))
                boolean("forceRefresh", "Force a fresh RapidOCR pass even if the framebuffer perceptually matches the cached index. Default false.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${ctx.profileLabel(pid)}'" else "the open remote desktop"
                "Let the agent inspect OCR and spatial elements on $who"
            },
        ) { args -> inspectDesktopTab(args) },

        "tap_desktop_text" to ToolHandler(
            description = "Find visible text on a remote-desktop VIEWER tab (VNC, RDP, SPICE, or GLKVM) using RapidOCR + the 2D k-d tree index and click its bounding-box center or edge anchor (plus optional dx,dy offset). Pass minX/maxX/minY/maxY to restrict matching to a screen region (e.g. minY=160 to ignore browser tabs), or regionX/regionY/regionWidth/regionHeight to run OCR only on a region of interest. When multiple identical labels exist on screen, pass nearX and nearY to click the match closest to that anchor coordinate, or pass nth (0-based) to select by rank. Set anchor=\"right\" (plus e.g. dx=12) to click a split-button dropdown chevron or icon adjacent to a label. Set inspectAfter=\"diff\" (or \"lines\") to wait settleMs and return the post-click OCR diff/observation in a single round-trip, or returnScreenshot=true to return a screenshot inline. Returns the clicked (x,y), the matched OCR node, its k-d tree spatial neighbors, total match count, candidates, and optional inspection.",
            inputSchema = objectSchema {
                string("profileId", "Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one is open.")
                string("query", "Visible text label to find and click.", required = true)
                boolean("exact", "Require exact normalized text match instead of substring match. Default false.")
                integer("nearX", "Optional anchor X to rank matches by proximity.")
                integer("nearY", "Optional anchor Y to rank matches by proximity.")
                integer("minX", "Optional minimum center X coordinate for matching text.")
                integer("maxX", "Optional maximum center X coordinate for matching text.")
                integer("minY", "Optional minimum center Y coordinate for matching text (e.g. minY=160 to skip browser tab/address bars).")
                integer("maxY", "Optional maximum center Y coordinate for matching text.")
                integer("regionX", "Optional ROI crop left X in full framebuffer pixels.")
                integer("regionY", "Optional ROI crop top Y in full framebuffer pixels.")
                integer("regionWidth", "Optional ROI crop width in framebuffer pixels.")
                integer("regionHeight", "Optional ROI crop height in framebuffer pixels.")
                integer("nth", "0-based index among ranked matches to click. Default 0 (best/closest match).")
                string("anchor", "Point on the matched bounding box before applying (dx,dy): \"center\" (default), \"left\", \"right\", \"top\", or \"bottom\".", enum = listOf("center", "left", "right", "top", "bottom"))
                integer("dx", "Horizontal pixel offset added to the anchor X before clicking. Default 0.")
                integer("dy", "Vertical pixel offset added to the anchor Y before clicking. Default 0.")
                integer("button", "X11 mouse button: 1=left (default), 2=middle, 3=right.")
                integer("clicks", "Number of clicks: 1=single click (default), 2=double-click (clamped 1..3).")
                string("level", "Granularity to match: \"all\" (default, prefers tight word boxes), \"word\", or \"line\".", enum = listOf("all", "word", "line"))
                boolean("forceRefresh", "Force a fresh RapidOCR pass before matching. Default false.")
                string("inspectAfter", "Optional post-click RapidOCR inspection mode to run after settleMs: \"none\" (default), \"diff\" (added/removed lines vs before the click), \"lines\", \"at\", or \"tree\".", enum = listOf("none", "diff", "lines", "at", "tree"))
                boolean("returnScreenshot", "If true, wait settleMs after clicking and return a fresh screenshot inline. Default false.")
                integer("settleMs", "Milliseconds to wait before post-action inspection/screenshot. Default 250 (clamped 0..5000).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${ctx.profileLabel(pid)}'" else "the open remote desktop"
                "Click text \"${args.optString("query")}\" on $who"
            },
        ) { args -> tapDesktopText(args) },

        "move_desktop_mouse" to ToolHandler(
            description = "Move the mouse pointer on a remote-desktop VIEWER tab (VNC, RDP, SPICE, or GLKVM) to (x, y) in remote framebuffer pixel coordinates without clicking. Useful for hovering to reveal tooltips/menus or positioning the cursor before inspect_desktop_tab(mode=\"at\") or scroll_desktop_tab. Returns { profileId, protocol, x, y }.",
            inputSchema = objectSchema {
                string("profileId", "Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one is open.")
                integer("x", "Remote framebuffer X (0..width).", required = true)
                integer("y", "Remote framebuffer Y (0..height).", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${ctx.profileLabel(pid)}'" else "the open remote desktop"
                "Move mouse on $who"
            },
        ) { args -> moveDesktopMouse(args) },

        "drag_desktop_tab" to ToolHandler(
            description = "Drag the mouse on a remote-desktop VIEWER tab (VNC, RDP, SPICE, or GLKVM) from (startX, startY) to (endX, endY) in remote framebuffer pixel coordinates — moves to start, presses the mouse button, interpolates intermediate motion steps over durationMs, and releases the button. Use for moving windows, selecting text/regions, or dragging sliders. Returns { profileId, protocol, startX, startY, endX, endY, button, steps, durationMs }.",
            inputSchema = objectSchema {
                string("profileId", "Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one is open.")
                integer("startX", "Drag start X in remote framebuffer pixels.", required = true)
                integer("startY", "Drag start Y in remote framebuffer pixels.", required = true)
                integer("endX", "Drag end X in remote framebuffer pixels.", required = true)
                integer("endY", "Drag end Y in remote framebuffer pixels.", required = true)
                integer("button", "X11 mouse button to hold during drag: 1=left (default), 2=middle, 3=right.")
                integer("steps", "Number of interpolated motion steps between start and end. Default 12 (clamped 1..100).")
                integer("durationMs", "Total drag duration in milliseconds. Default 180 (clamped 0..5000).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${ctx.profileLabel(pid)}'" else "the open remote desktop"
                "Drag (${args.optInt("startX")},${args.optInt("startY")}) → (${args.optInt("endX")},${args.optInt("endY")}) on $who"
            },
        ) { args -> dragDesktopTab(args) },

        "type_desktop_text" to ToolHandler(
            description = "Type text or send special keys/shortcuts into a remote-desktop VIEWER tab as real keystrokes (GLKVM/VNC/RDP/SPICE). Pass text to type characters (with optional pressEnter=true to tap Enter after typing), or pass key (e.g. \"Enter\", \"Tab\", \"Escape\", \"Backspace\", \"Delete\", \"Space\", \"ArrowDown\", \"ArrowUp\", \"ArrowLeft\", \"ArrowRight\", \"Home\", \"End\", \"PageUp\", \"PageDown\", or \"a\"..\"z\" / \"KeyA\"..\"KeyZ\") with optional ctrl/alt/shift/meta modifiers and optional repeat (e.g. key=\"PageUp\", repeat=3 to scroll up 3 pages). Set inspectAfter=\"diff\" (or \"lines\") to wait settleMs and return the post-typing OCR diff/observation in the same call, or returnScreenshot=true to return a fresh screenshot inline. Pass profileId to pick a tab (from list_desktop_sessions); omit when exactly one is open.",
            inputSchema = objectSchema {
                string("profileId", "Profile id (from list_desktop_sessions). Omit when exactly one is open.")
                string("text", "Text to type as keystrokes. Optional when key is provided.")
                string("key", "Optional special key or key code to tap (e.g. \"Enter\", \"Tab\", \"Escape\", \"Backspace\", \"Delete\", \"ArrowDown\", \"ArrowUp\", \"ArrowLeft\", \"ArrowRight\", \"a\", \"v\", \"KeyA\").")
                integer("repeat", "Number of times to tap key when key is provided. Default 1 (clamped 1..50).")
                boolean("ctrl", "Hold ControlLeft while tapping key. Default false.")
                boolean("alt", "Hold AltLeft while tapping key. Default false.")
                boolean("shift", "Hold ShiftLeft while tapping key. Default false.")
                boolean("meta", "Hold MetaLeft (Windows/Super) while tapping key. Default false.")
                boolean("pressEnter", "Tap Enter after typing text. Default false.")
                string("inspectAfter", "Optional post-typing RapidOCR inspection mode to run after settleMs: \"none\" (default), \"diff\" (added/removed lines vs before typing), \"lines\", \"at\", or \"tree\".", enum = listOf("none", "diff", "lines", "at", "tree"))
                boolean("returnScreenshot", "If true, wait settleMs after typing and return a fresh screenshot inline. Default false.")
                integer("settleMs", "Milliseconds to wait before post-action inspection/screenshot. Default 250 (clamped 0..5000).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${ctx.profileLabel(pid)}'" else "the open remote desktop"
                val key = args.optString("key").takeIf { it.isNotBlank() }
                if (key != null) {
                    "Send key '$key' to $who"
                } else {
                    "Type ${args.optString("text").length} chars into $who"
                }
            },
        ) { args -> typeDesktopText(args) },

        "scroll_desktop_tab" to ToolHandler(
            description = "Scroll a remote-desktop VIEWER tab (RDP/VNC/SPICE/GLKVM) by injecting mouse-wheel notches into the remote server. deltaY > 0 scrolls down, < 0 scrolls up; magnitude is the number of notches. Optionally pass x and y to position the pointer over the target pane before scrolling, set inspectAfter=\"diff\" (or \"lines\") to wait settleMs and return the post-scroll OCR diff/observation, or set returnScreenshot=true to return a fresh screenshot inline. Pass profileId to pick a tab (from list_desktop_sessions); omit when exactly one is open. Returns { profileId, protocol, deltaY, x?, y?, inspection? }.",
            inputSchema = objectSchema {
                string("profileId", "Profile id (from list_desktop_sessions). Omit when exactly one is open.")
                integer("deltaY", "Wheel notches: >0 scrolls down, <0 scrolls up.", required = true)
                integer("x", "Optional remote framebuffer X to move the pointer to before scrolling.")
                integer("y", "Optional remote framebuffer Y to move the pointer to before scrolling.")
                string("inspectAfter", "Optional post-scroll RapidOCR inspection mode to run after settleMs: \"none\" (default), \"diff\" (added/removed lines vs before scrolling), \"lines\", \"at\", or \"tree\".", enum = listOf("none", "diff", "lines", "at", "tree"))
                boolean("returnScreenshot", "If true, wait settleMs after scrolling and return a fresh screenshot inline. Default false.")
                integer("settleMs", "Milliseconds to wait before post-action inspection/screenshot. Default 250 (clamped 0..5000).")
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
            put("usbRedirPort", st?.usbRedirPort ?: JSONObject.NULL)
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
        val usbRedirPort = if (args.has("usbRedirPort") && !args.isNull("usbRedirPort")) {
            args.optInt("usbRedirPort", 0).takeIf { it > 0 }
                ?: throw McpError(-32602, "usbRedirPort must be in 1..65535")
        } else {
            null
        }
        val st = try {
            systemVmManager.startImage(imageId, memMb, cpus, accelMode, usbRedirPort)
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
            put("usbRedirPort", st.usbRedirPort ?: JSONObject.NULL)
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

    private suspend fun captureDesktopTab(args: JSONObject): ToolResult.Image {
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
        val forceRefresh = args.optBoolean("forceRefresh", false)
        val src = (handle.refreshFrame?.let { refresh ->
            withContext(Dispatchers.IO) { refresh(forceRefresh) }
        } ?: handle.frame())
            ?: throw McpError(-32603, "Desktop '$pid' has not rendered a frame yet — wait for it to connect.")

        val maxWidth = args.optInt("maxWidth", 1280).coerceIn(160, 4096)
        val format = if (args.optString("format", "jpeg").lowercase() == "png") "png" else "jpeg"

        val cursor = handle.cursor()
        val (px, py) = handle.pointer()
        val reqRegion = parseRegionArg(args, src.width, src.height)

        data class EncodedCapture(
            val base64: String,
            val fullW: Int,
            val fullH: Int,
            val imgW: Int,
            val imgH: Int,
        )

        val encoded = withContext(Dispatchers.Default) {
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
            if (reqRegion != null &&
                (reqRegion.x != 0 || reqRegion.y != 0 || reqRegion.width != fullW || reqRegion.height != fullH)
            ) {
                val cropped = Bitmap.createBitmap(bmp, reqRegion.x, reqRegion.y, reqRegion.width, reqRegion.height)
                if (cropped !== bmp) bmp.recycle()
                bmp = cropped
            }
            if (maxWidth in 1 until bmp.width) {
                val nh = (bmp.height.toFloat() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
                bmp = Bitmap.createScaledBitmap(bmp, maxWidth, nh, true)
            }
            val imgW = bmp.width
            val imgH = bmp.height
            val out = java.io.ByteArrayOutputStream()
            if (format == "jpeg") {
                bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
            } else {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            EncodedCapture(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), fullW, fullH, imgW, imgH)
        }

        val lastAgentAt = desktopSessionRegistry.lastAgentActionAt(pid)
        val recentUser = desktopSessionRegistry.recentUserInteractions(pid, limit = 8)
        val nowMs = System.currentTimeMillis()
        val userInteractedSinceLastAgent = recentUser.any { u ->
            lastAgentAt == null || u.timestampMs > lastAgentAt
        }
        desktopSessionRegistry.recordAgentAction(pid, nowMs)
        val activeBounds = handle.activeBounds?.invoke()

        return ToolResult.Image(
            base64 = encoded.base64,
            mimeType = if (format == "jpeg") "image/jpeg" else "image/png",
            structured = JSONObject().apply {
                put("profileId", pid)
                put("protocol", handle.protocol)
                put("width", encoded.fullW)
                put("height", encoded.fullH)
                put("imageWidth", encoded.imgW)
                put("imageHeight", encoded.imgH)
                if (reqRegion != null) {
                    put("region", bboxToJson(reqRegion))
                }
                put("pointerX", px)
                put("pointerY", py)
                if (activeBounds != null) {
                    put("activeLeftPx", activeBounds.first)
                    put("activeWidthPx", activeBounds.second)
                }
                put("hasCursor", cursor != null)
                if (cursor != null) {
                    put("cursorWidth", cursor.bitmap.width)
                    put("cursorHeight", cursor.bitmap.height)
                    put("hotspotX", cursor.hotspotX)
                    put("hotspotY", cursor.hotspotY)
                }
                put("format", format)
                put("userInteractedSinceLastAgentAction", userInteractedSinceLastAgent)
            },
        )
    }

    private fun parseRegionArg(args: JSONObject, frameW: Int, frameH: Int): OcrBbox? {
        val hasX = args.has("regionX") && !args.isNull("regionX")
        val hasY = args.has("regionY") && !args.isNull("regionY")
        val hasW = args.has("regionWidth") && !args.isNull("regionWidth")
        val hasH = args.has("regionHeight") && !args.isNull("regionHeight")
        if (!hasX && !hasY && !hasW && !hasH) return null
        if (frameW <= 0 || frameH <= 0) return null
        val rx = (if (hasX) args.getInt("regionX") else 0).coerceIn(0, (frameW - 1).coerceAtLeast(0))
        val ry = (if (hasY) args.getInt("regionY") else 0).coerceIn(0, (frameH - 1).coerceAtLeast(0))
        val rw = (if (hasW) args.getInt("regionWidth") else frameW - rx).coerceIn(1, (frameW - rx).coerceAtLeast(1))
        val rh = (if (hasH) args.getInt("regionHeight") else frameH - ry).coerceIn(1, (frameH - ry).coerceAtLeast(1))
        return if (rw >= 4 && rh >= 4) OcrBbox(rx, ry, rw, rh) else null
    }

    private suspend fun maybeCapturePreActionBaseline(pid: String, args: JSONObject) {
        val inspectAfter = args.optString("inspectAfter", "none").trim().lowercase()
        if (inspectAfter != "diff" || textRecognizer == null) return
        val fh = desktopSessionRegistry.frameHandle(pid) ?: return
        runCatching {
            lastInspectedIndex[pid] = buildOrGetOcrIndex(pid, fh, forceRefresh = false, args = args)
        }
    }

    private suspend fun maybeWithPostAction(
        pid: String,
        args: JSONObject,
        structured: JSONObject,
    ): Any {
        val inspectAfter = args.optString("inspectAfter", "none").trim().lowercase()
        val wantInspect = inspectAfter.isNotEmpty() && inspectAfter != "none"
        val wantScreenshot = args.optBoolean("returnScreenshot", false)
        if (!wantInspect && !wantScreenshot) return structured

        val settleMs = args.optInt("settleMs", 250).coerceIn(0, 5000)
        if (settleMs > 0) kotlinx.coroutines.delay(settleMs.toLong())

        if (wantInspect && textRecognizer != null && desktopSessionRegistry.frameHandle(pid) != null) {
            val inspectArgs = JSONObject().apply {
                put("profileId", pid)
                put("mode", inspectAfter)
                put("forceRefresh", true)
                for (k in listOf("regionX", "regionY", "regionWidth", "regionHeight", "x", "y")) {
                    if (args.has(k) && !args.isNull(k)) put(k, args.getInt(k))
                }
            }
            val insp = inspectDesktopTab(inspectArgs)
            structured.put("inspection", insp)
        }

        if (!wantScreenshot) return structured

        val captureArgs = JSONObject().apply {
            put("profileId", pid)
            put("forceRefresh", !wantInspect)
            if (args.has("maxWidth")) put("maxWidth", args.optInt("maxWidth", 1280))
            if (args.has("format")) put("format", args.optString("format", "jpeg"))
            for (k in listOf("regionX", "regionY", "regionWidth", "regionHeight")) {
                if (args.has(k) && !args.isNull(k)) put(k, args.getInt(k))
            }
        }
        val cap = captureDesktopTab(captureArgs)
        structured.put("screenshot", cap.structured)
        return ToolResult.Image(
            base64 = cap.base64,
            mimeType = cap.mimeType,
            structured = structured,
        )
    }

    private fun filterMatchesByBounds(
        matches: List<OcrScreenIndex.TextMatchResult>,
        args: JSONObject,
    ): List<OcrScreenIndex.TextMatchResult> {
        val minX = if (args.has("minX") && !args.isNull("minX")) args.getInt("minX").toDouble() else null
        val maxX = if (args.has("maxX") && !args.isNull("maxX")) args.getInt("maxX").toDouble() else null
        val minY = if (args.has("minY") && !args.isNull("minY")) args.getInt("minY").toDouble() else null
        val maxY = if (args.has("maxY") && !args.isNull("maxY")) args.getInt("maxY").toDouble() else null
        if (minX == null && maxX == null && minY == null && maxY == null) return matches
        return matches.filter { m ->
            val cx = m.node.bbox.cx
            val cy = m.node.bbox.cy
            (minX == null || cx >= minX) &&
                (maxX == null || cx <= maxX) &&
                (minY == null || cy >= minY) &&
                (maxY == null || cy <= maxY)
        }
    }

    private fun resolveDesktopFrame(args: JSONObject): Pair<String, DesktopFrameHandle> {
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
        return pid to handle
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

    private fun parseLevelArg(raw: String): OcrNode.Level? = when (raw.trim().lowercase()) {
        "", "all" -> null
        "word", "words" -> OcrNode.Level.WORD
        "line", "lines" -> OcrNode.Level.LINE
        else -> throw McpError(-32602, "level must be one of all, word, line")
    }

    private fun bboxToJson(b: OcrBbox): JSONArray =
        JSONArray().put(b.x).put(b.y).put(b.width).put(b.height)

    private fun centerToJson(b: OcrBbox): JSONArray =
        JSONArray().put(b.cx.roundToInt()).put(b.cy.roundToInt())

    private fun nodeToJson(n: OcrNode): JSONObject = JSONObject().apply {
        put("id", n.id)
        put("level", n.level.name.lowercase())
        put("text", n.text)
        put("score", (n.score * 1000.0).roundToInt() / 1000.0)
        put("bbox", bboxToJson(n.bbox))
        put("center", centerToJson(n.bbox))
        put("blockId", n.blockId)
        put("lineId", n.lineId)
    }

    private fun neighborToJson(nb: OcrNeighbor): JSONObject = JSONObject().apply {
        put("id", nb.node.id)
        put("level", nb.node.level.name.lowercase())
        put("text", nb.node.text)
        put("score", (nb.node.score * 1000.0).roundToInt() / 1000.0)
        put("bbox", bboxToJson(nb.node.bbox))
        put("center", centerToJson(nb.node.bbox))
        put("centerDistance", (nb.centerDistance * 10.0).roundToInt() / 10.0)
        put("rectDistance", (nb.rectDistance * 10.0).roundToInt() / 10.0)
        put("dx", (nb.dx * 10.0).roundToInt() / 10.0)
        put("dy", (nb.dy * 10.0).roundToInt() / 10.0)
    }

    private fun buildUserInteractionsJson(
        pid: String,
        index: OcrScreenIndex?,
        nowMs: Long,
        lastAgentAt: Long?,
    ): Pair<Boolean, JSONArray> {
        val recent = desktopSessionRegistry.recentUserInteractions(pid, limit = 8)
        val sinceLastAgent = recent.any { u -> lastAgentAt == null || u.timestampMs > lastAgentAt }
        val arr = JSONArray()
        for (u in recent) {
            arr.put(JSONObject().apply {
                put("type", u.type)
                put("timestampMs", u.timestampMs)
                put("ageMs", (nowMs - u.timestampMs).coerceAtLeast(0L))
                put("afterLastAgentAction", lastAgentAt == null || u.timestampMs > lastAgentAt)
                val ux = u.x
                val uy = u.y
                if (ux != null) put("x", ux)
                if (uy != null) put("y", uy)
                u.detail?.let { put("detail", it) }
                if (index != null && ux != null && uy != null) {
                    val spatial = index.queryAt(ux.toDouble(), uy.toDouble(), k = 2)
                    spatial.containing.firstOrNull()?.let { hit ->
                        put("targetText", hit.text)
                        put("targetBbox", bboxToJson(hit.bbox))
                    }
                    spatial.nearest.firstOrNull()?.let { nb ->
                        put("nearestText", nb.node.text)
                        put("nearestDistance", (nb.rectDistance * 10.0).roundToInt() / 10.0)
                    }
                }
            })
        }
        return sinceLastAgent to arr
    }

    private fun lineToJson(line: sh.haven.core.scan.OcrLine): JSONObject = JSONObject().apply {
        put("id", line.id)
        put("text", line.text)
        put("score", (line.score * 1000.0).roundToInt() / 1000.0)
        put("bbox", bboxToJson(line.bbox))
        put("center", centerToJson(line.bbox))
    }

    private suspend fun buildOrGetOcrIndex(
        pid: String,
        handle: DesktopFrameHandle,
        forceRefresh: Boolean,
        args: JSONObject? = null,
    ): OcrScreenIndex {
        val recognizer = textRecognizer
            ?: throw McpError(-32603, "RapidOCR TextRecognizer is not available")
        val src = (handle.refreshFrame?.let { refresh ->
            withContext(Dispatchers.IO) { refresh(forceRefresh) }
        } ?: handle.frame())
            ?: throw McpError(-32603, "Desktop '$pid' has not rendered a frame yet — wait for it to connect.")
        val region = args?.let { parseRegionArg(it, src.width, src.height) }
        return try {
            if (region != null) {
                recognizer.recognizeIndex(src, forceRefresh = forceRefresh, region = region)
            } else {
                recognizer.recognizeIndex(src, forceRefresh = forceRefresh)
            }
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "RapidOCR failed on desktop '$pid'")
        }
    }

    private suspend fun inspectDesktopTab(args: JSONObject): JSONObject {
        val (pid, handle) = resolveDesktopFrame(args)
        val forceRefresh = args.optBoolean("forceRefresh", false)
        val level = parseLevelArg(args.optString("level", "all"))
        val k = args.optInt("k", 5).coerceIn(0, 50)
        val maxRadius = if (args.has("maxRadius") && !args.isNull("maxRadius")) {
            args.optDouble("maxRadius", Double.POSITIVE_INFINITY).coerceAtLeast(0.0)
        } else {
            Double.POSITIVE_INFINITY
        }

        val rawMode = args.optString("mode", "auto").trim().lowercase()
        val hasCoords = args.has("x") && !args.isNull("x") && args.has("y") && !args.isNull("y")
        val query = args.optString("query").trim()
        val resolvedMode = when (rawMode) {
            "", "auto" -> when {
                hasCoords -> "at"
                query.isNotEmpty() -> "find"
                else -> "tree"
            }
            "at", "find", "lines", "diff", "tree" -> rawMode
            else -> throw McpError(-32602, "mode must be one of auto, at, find, lines, diff, tree")
        }

        val prevIndex = lastInspectedIndex[pid]
        val index = buildOrGetOcrIndex(pid, handle, forceRefresh, args)
        lastInspectedIndex[pid] = index

        val (pointerX, pointerY) = handle.pointer()
        val activeBounds = handle.activeBounds?.invoke()
        val reqRegion = parseRegionArg(args, index.frameWidth, index.frameHeight)
        val nowMs = System.currentTimeMillis()
        val lastAgentAt = desktopSessionRegistry.lastAgentActionAt(pid)
        val (userInteractedSinceLastAgent, recentUserJson) =
            buildUserInteractionsJson(pid, index, nowMs, lastAgentAt)
        desktopSessionRegistry.recordAgentAction(pid, nowMs)

        val base = JSONObject().apply {
            put("profileId", pid)
            put("protocol", handle.protocol)
            put("width", index.frameWidth)
            put("height", index.frameHeight)
            if (activeBounds != null) {
                put("activeLeftPx", activeBounds.first)
                put("activeWidthPx", activeBounds.second)
            }
            if (reqRegion != null) {
                put("region", bboxToJson(reqRegion))
            }
            put("frameHash", index.frameHash)
            put("ocrMs", (index.ocrMs * 10.0).roundToInt() / 10.0)
            textRecognizer?.let { rec ->
                put("cacheHit", rec.lastCacheHit)
                put("wobbleIgnored", rec.lastWobbleIgnored)
            }
            put("blockCount", index.blocks.size)
            put("lineCount", index.lineCount)
            put("wordCount", index.wordCount)
            put("pointerX", pointerX)
            put("pointerY", pointerY)
            put("mode", resolvedMode)
            put("userInteractedSinceLastAgentAction", userInteractedSinceLastAgent)
            put("recentUserInteractions", recentUserJson)
        }

        when (resolvedMode) {
            "at" -> {
                val qx = if (hasCoords) args.getInt("x").toDouble() else pointerX.toDouble()
                val qy = if (hasCoords) args.getInt("y").toDouble() else pointerY.toDouble()
                val res = index.queryAt(
                    x = qx,
                    y = qy,
                    k = k,
                    maxRadius = maxRadius,
                    level = level,
                )
                base.put("queryX", res.x)
                base.put("queryY", res.y)
                base.put("containing", JSONArray().apply {
                    res.containing.forEach { put(nodeToJson(it)) }
                })
                base.put("nearest", JSONArray().apply {
                    res.nearest.forEach { put(neighborToJson(it)) }
                })
            }
            "find" -> {
                if (query.isEmpty()) {
                    throw McpError(-32602, "query is required when mode=\"find\"")
                }
                val exact = args.optBoolean("exact", false)
                val nearX = if (args.has("nearX") && !args.isNull("nearX")) args.getInt("nearX").toDouble() else null
                val nearY = if (args.has("nearY") && !args.isNull("nearY")) args.getInt("nearY").toDouble() else null
                val matches = filterMatchesByBounds(
                    index.findText(
                        query = query,
                        exact = exact,
                        nearX = nearX,
                        nearY = nearY,
                        kNeighbors = k,
                        level = level,
                    ),
                    args,
                )
                base.put("query", query)
                base.put("exact", exact)
                if (nearX != null && nearY != null) {
                    base.put("nearX", nearX.roundToInt())
                    base.put("nearY", nearY.roundToInt())
                }
                base.put("matchCount", matches.size)
                base.put("matches", JSONArray().apply {
                    matches.forEach { m ->
                        put(nodeToJson(m.node).apply {
                            m.anchorDistance?.let { d ->
                                put("anchorDistance", (d * 10.0).roundToInt() / 10.0)
                            }
                            put("neighbors", JSONArray().apply {
                                m.neighbors.forEach { nb -> put(neighborToJson(nb)) }
                            })
                        })
                    }
                })
            }
            "lines" -> {
                base.put("lines", JSONArray().apply {
                    index.blocks.forEach { blk ->
                        blk.lines.forEach { line ->
                            put(lineToJson(line))
                        }
                    }
                })
            }
            "diff" -> {
                val currLines = index.blocks.flatMap { it.lines }
                val prevLines = prevIndex?.blocks?.flatMap { it.lines }.orEmpty()
                val usedPrev = BooleanArray(prevLines.size)
                val addedLines = mutableListOf<sh.haven.core.scan.OcrLine>()
                var unchangedCount = 0
                for (c in currLines) {
                    val cNorm = OcrScreenIndex.normalizeText(c.text)
                    var bestIdx = -1
                    var bestDist = Double.POSITIVE_INFINITY
                    for (pi in prevLines.indices) {
                        if (usedPrev[pi]) continue
                        val p = prevLines[pi]
                        if (OcrScreenIndex.normalizeText(p.text) == cNorm) {
                            val dist = kotlin.math.hypot(c.bbox.cx - p.bbox.cx, c.bbox.cy - p.bbox.cy)
                            if (dist <= 64.0 && dist < bestDist) {
                                bestDist = dist
                                bestIdx = pi
                            }
                        }
                    }
                    if (bestIdx >= 0) {
                        usedPrev[bestIdx] = true
                        unchangedCount++
                    } else {
                        addedLines.add(c)
                    }
                }
                val removedLines = prevLines.filterIndexed { idx, _ -> !usedPrev[idx] }
                base.put("hasBaseline", prevIndex != null)
                base.put("changed", addedLines.isNotEmpty() || removedLines.isNotEmpty())
                base.put("unchangedLineCount", unchangedCount)
                base.put("addedLines", JSONArray().apply {
                    addedLines.forEach { put(lineToJson(it)) }
                })
                base.put("removedLines", JSONArray().apply {
                    removedLines.forEach { put(lineToJson(it)) }
                })
            }
            else -> {
                base.put("blocks", JSONArray().apply {
                    index.blocks.forEach { blk ->
                        put(JSONObject().apply {
                            put("id", blk.id)
                            put("lines", JSONArray().apply {
                                blk.lines.forEach { line ->
                                    put(JSONObject().apply {
                                        put("id", line.id)
                                        put("text", line.text)
                                        put("score", (line.score * 1000.0).roundToInt() / 1000.0)
                                        put("bbox", bboxToJson(line.bbox))
                                        put("center", centerToJson(line.bbox))
                                        put("words", JSONArray().apply {
                                            line.words.forEach { w ->
                                                put(JSONObject().apply {
                                                    put("id", w.id)
                                                    put("text", w.text)
                                                    put("bbox", bboxToJson(w.bbox))
                                                    put("center", centerToJson(w.bbox))
                                                })
                                            }
                                        })
                                    })
                                }
                            })
                        })
                    }
                })
            }
        }
        return base
    }

    private suspend fun tapDesktopText(args: JSONObject): Any {
        val (pid, inputHandle) = resolveDesktopInput(args)
        val frameHandle = desktopSessionRegistry.frameHandle(pid)
            ?: throw McpError(-32602, "No capturable desktop tab for profile '$pid' (call list_desktop_sessions).")
        val query = args.optString("query").trim()
        if (query.isEmpty()) throw McpError(-32602, "query is required")

        val exact = args.optBoolean("exact", false)
        val nearX = if (args.has("nearX") && !args.isNull("nearX")) args.getInt("nearX").toDouble() else null
        val nearY = if (args.has("nearY") && !args.isNull("nearY")) args.getInt("nearY").toDouble() else null
        val nth = args.optInt("nth", 0).coerceAtLeast(0)
        val anchor = args.optString("anchor", "center").trim().lowercase()
        val dx = args.optInt("dx", 0)
        val dy = args.optInt("dy", 0)
        val button = args.optInt("button", 1).coerceIn(1, 7)
        val clicks = args.optInt("clicks", 1).coerceIn(1, 3)
        val level = parseLevelArg(args.optString("level", "all"))
        val forceRefresh = args.optBoolean("forceRefresh", false)

        val index = buildOrGetOcrIndex(pid, frameHandle, forceRefresh, args)
        lastInspectedIndex[pid] = index
        val matches = filterMatchesByBounds(
            index.findText(
                query = query,
                exact = exact,
                nearX = nearX,
                nearY = nearY,
                kNeighbors = 4,
                level = level,
            ),
            args,
        )
        if (matches.isEmpty()) {
            throw McpError(
                -32603,
                "No OCR text matching '$query' found on desktop '$pid' " +
                    "(${index.lineCount} lines, ${index.wordCount} words indexed)",
            )
        }
        if (nth >= matches.size) {
            throw McpError(
                -32602,
                "nth=$nth is out of range for '$query' (found ${matches.size} match(es))",
            )
        }
        val chosen = matches[nth]
        val box = chosen.node.bbox
        val (baseX, baseY) = when (anchor) {
            "", "center" -> box.cx.roundToInt() to box.cy.roundToInt()
            "left" -> box.x to box.cy.roundToInt()
            "right" -> box.right to box.cy.roundToInt()
            "top" -> box.cx.roundToInt() to box.y
            "bottom" -> box.cx.roundToInt() to box.bottom
            else -> throw McpError(-32602, "anchor must be one of center, left, right, top, bottom")
        }
        val clickX = (baseX + dx).coerceIn(0, (index.frameWidth - 1).coerceAtLeast(0))
        val clickY = (baseY + dy).coerceIn(0, (index.frameHeight - 1).coerceAtLeast(0))

        desktopSessionRegistry.recordAgentAction(pid)
        repeat(clicks) { i ->
            if (i > 0) kotlinx.coroutines.delay(60)
            inputHandle.mouseClick(clickX, clickY, button)
        }

        val structured = JSONObject().apply {
            put("profileId", pid)
            put("protocol", inputHandle.protocol)
            put("x", clickX)
            put("y", clickY)
            put("button", button)
            put("clicks", clicks)
            put("query", query)
            put("nth", nth)
            put("anchor", anchor.ifEmpty { "center" })
            put("matchCount", matches.size)
            put("matched", nodeToJson(chosen.node).apply {
                chosen.anchorDistance?.let { d ->
                    put("anchorDistance", (d * 10.0).roundToInt() / 10.0)
                }
            })
            put("candidates", JSONArray().apply {
                matches.take(10).forEachIndexed { idx, m ->
                    put(JSONObject().apply {
                        put("nth", idx)
                        put("text", m.node.text)
                        put("level", m.node.level.name.lowercase())
                        put("bbox", bboxToJson(m.node.bbox))
                        put("center", centerToJson(m.node.bbox))
                        m.anchorDistance?.let { d ->
                            put("anchorDistance", (d * 10.0).roundToInt() / 10.0)
                        }
                    })
                }
            })
            put("neighbors", JSONArray().apply {
                chosen.neighbors.forEach { nb -> put(neighborToJson(nb)) }
            })
        }
        return maybeWithPostAction(pid, args, structured)
    }

    private suspend fun tapDesktopTab(args: JSONObject): Any {
        val (pid, h) = resolveDesktopInput(args)
        maybeCapturePreActionBaseline(pid, args)
        val defaultButton = args.optInt("button", 1).coerceIn(1, 7)
        val defaultClicks = args.optInt("clicks", 1).coerceIn(1, 3)
        val pointsArr = args.optJSONArray("points")
        desktopSessionRegistry.recordAgentAction(pid)

        val structured = if (pointsArr != null && pointsArr.length() > 0) {
            val count = pointsArr.length().coerceAtMost(20)
            val clicked = JSONArray()
            var lastX = 0
            var lastY = 0
            for (idx in 0 until count) {
                val pt = pointsArr.optJSONObject(idx)
                    ?: throw McpError(-32602, "points[$idx] must be an object with x and y")
                val px = requireIntArg(pt, "x")
                val py = requireIntArg(pt, "y")
                val btn = pt.optInt("button", defaultButton).coerceIn(1, 7)
                val clk = pt.optInt("clicks", defaultClicks).coerceIn(1, 3)
                val delayMs = pt.optInt("delayMs", if (idx == 0) 0 else 80).coerceIn(0, 5000)
                if (delayMs > 0) kotlinx.coroutines.delay(delayMs.toLong())
                repeat(clk) { i ->
                    if (i > 0) kotlinx.coroutines.delay(60)
                    h.mouseClick(px, py, btn)
                }
                lastX = px
                lastY = py
                clicked.put(JSONObject().apply {
                    put("x", px); put("y", py); put("button", btn); put("clicks", clk)
                })
            }
            JSONObject().apply {
                put("profileId", pid); put("protocol", h.protocol)
                put("x", lastX); put("y", lastY)
                put("pointsClicked", clicked)
            }
        } else {
            val x = requireIntArg(args, "x")
            val y = requireIntArg(args, "y")
            repeat(defaultClicks) { i ->
                if (i > 0) kotlinx.coroutines.delay(60)
                h.mouseClick(x, y, defaultButton)
            }
            JSONObject().apply {
                put("profileId", pid); put("protocol", h.protocol)
                put("x", x); put("y", y); put("button", defaultButton); put("clicks", defaultClicks)
            }
        }
        return maybeWithPostAction(pid, args, structured)
    }

    private fun moveDesktopMouse(args: JSONObject): JSONObject {
        val (pid, h) = resolveDesktopInput(args)
        val x = requireIntArg(args, "x")
        val y = requireIntArg(args, "y")
        desktopSessionRegistry.recordAgentAction(pid)
        h.mouseMove(x, y)
        return JSONObject().apply {
            put("profileId", pid); put("protocol", h.protocol)
            put("x", x); put("y", y)
        }
    }

    private suspend fun dragDesktopTab(args: JSONObject): JSONObject {
        val (pid, h) = resolveDesktopInput(args)
        val startX = requireIntArg(args, "startX")
        val startY = requireIntArg(args, "startY")
        val endX = requireIntArg(args, "endX")
        val endY = requireIntArg(args, "endY")
        val button = args.optInt("button", 1).coerceIn(1, 7)
        val steps = args.optInt("steps", 12).coerceIn(1, 100)
        val durationMs = args.optInt("durationMs", 180).coerceIn(0, 5000)
        val stepDelayMs = if (steps > 0) (durationMs / steps).toLong() else 0L

        desktopSessionRegistry.recordAgentAction(pid)
        h.mouseMove(startX, startY)
        h.mouseButton(button, true)
        try {
            for (i in 1..steps) {
                if (stepDelayMs > 0) kotlinx.coroutines.delay(stepDelayMs)
                val frac = i.toDouble() / steps.toDouble()
                val ix = (startX + (endX - startX) * frac).roundToInt()
                val iy = (startY + (endY - startY) * frac).roundToInt()
                h.mouseMove(ix, iy)
            }
        } finally {
            h.mouseButton(button, false)
        }

        return JSONObject().apply {
            put("profileId", pid)
            put("protocol", h.protocol)
            put("startX", startX)
            put("startY", startY)
            put("endX", endX)
            put("endY", endY)
            put("button", button)
            put("steps", steps)
            put("durationMs", durationMs)
        }
    }

    private suspend fun scrollDesktopTab(args: JSONObject): Any {
        val (pid, h) = resolveDesktopInput(args)
        maybeCapturePreActionBaseline(pid, args)
        val deltaY = requireIntArg(args, "deltaY")
        val hasX = args.has("x") && !args.isNull("x")
        val hasY = args.has("y") && !args.isNull("y")
        desktopSessionRegistry.recordAgentAction(pid)
        if (hasX && hasY) {
            h.mouseMove(args.getInt("x"), args.getInt("y"))
        }
        // MCP schema convention: deltaY > 0 scrolls down, < 0 scrolls up.
        // RemoteDesktopSession convention: deltaY > 0 scrolls up, < 0 scrolls down.
        h.mouseWheel(-deltaY)
        val structured = JSONObject().apply {
            put("profileId", pid)
            put("protocol", h.protocol)
            put("deltaY", deltaY)
            if (hasX && hasY) {
                put("x", args.getInt("x"))
                put("y", args.getInt("y"))
            }
        }
        return maybeWithPostAction(pid, args, structured)
    }

    private fun normalizeKeyCode(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length == 1) {
            val ch = trimmed[0]
            if (ch in 'a'..'z' || ch in 'A'..'Z') return "Key${ch.uppercaseChar()}"
            if (ch in '0'..'9') return "Digit$ch"
        }
        return when (trimmed.lowercase()) {
            "enter", "return" -> "Enter"
            "tab" -> "Tab"
            "esc", "escape" -> "Escape"
            "backspace", "bs" -> "Backspace"
            "del", "delete" -> "Delete"
            "space", "spacebar" -> "Space"
            "up", "arrowup" -> "ArrowUp"
            "down", "arrowdown" -> "ArrowDown"
            "left", "arrowleft" -> "ArrowLeft"
            "right", "arrowright" -> "ArrowRight"
            "home" -> "Home"
            "end" -> "End"
            "pageup", "pgup" -> "PageUp"
            "pagedown", "pgdn" -> "PageDown"
            "insert", "ins" -> "Insert"
            else -> trimmed
        }
    }

    private suspend fun typeDesktopText(args: JSONObject): Any {
        val (pid, h) = resolveDesktopInput(args)
        maybeCapturePreActionBaseline(pid, args)
        val text = args.optString("text")
        val rawKey = args.optString("key").trim()
        val repeatCount = args.optInt("repeat", 1).coerceIn(1, 50)
        val ctrl = args.optBoolean("ctrl", false)
        val alt = args.optBoolean("alt", false)
        val shift = args.optBoolean("shift", false)
        val meta = args.optBoolean("meta", false)
        val pressEnter = args.optBoolean("pressEnter", false)

        if (text.isEmpty() && rawKey.isEmpty()) {
            throw McpError(-32602, "Either text or key is required")
        }
        desktopSessionRegistry.recordAgentAction(pid)

        if (text.isNotEmpty()) {
            val type = h.typeText
                ?: throw McpError(
                    -32602,
                    "${h.protocol} does not support text typing (no keyboard verb yet); " +
                        "use send_desktop_clipboard on this tab instead.",
                )
            type(text)
        }

        if (rawKey.isNotEmpty() || pressEnter) {
            val sendKey = h.sendKey
                ?: if (pressEnter && h.typeText != null && rawKey.isEmpty()) {
                    null
                } else {
                    throw McpError(
                        -32602,
                        "${h.protocol} does not support raw key codes yet.",
                    )
                }
            if (rawKey.isNotEmpty() && sendKey != null) {
                val code = normalizeKeyCode(rawKey)
                val mods = buildList {
                    if (ctrl) add("ControlLeft")
                    if (alt) add("AltLeft")
                    if (shift) add("ShiftLeft")
                    if (meta) add("MetaLeft")
                }
                for (m in mods) sendKey(m, true)
                repeat(repeatCount) { i ->
                    if (i > 0) kotlinx.coroutines.delay(15)
                    sendKey(code, true)
                    kotlinx.coroutines.delay(15)
                    sendKey(code, false)
                }
                for (m in mods.asReversed()) sendKey(m, false)
            }
            if (pressEnter) {
                if (sendKey != null) {
                    sendKey("Enter", true)
                    kotlinx.coroutines.delay(15)
                    sendKey("Enter", false)
                } else {
                    h.typeText?.invoke("\n")
                }
            }
        }

        val structured = JSONObject().apply {
            put("profileId", pid)
            put("protocol", h.protocol)
            put("chars", text.length)
            if (rawKey.isNotEmpty()) {
                put("key", normalizeKeyCode(rawKey))
                if (repeatCount > 1) put("repeat", repeatCount)
            }
            if (pressEnter) put("pressEnter", true)
        }
        return maybeWithPostAction(pid, args, structured)
    }

    private fun sendDesktopClipboard(args: JSONObject): JSONObject {
        val (pid, h) = resolveDesktopInput(args)
        if (!args.has("text")) throw McpError(-32602, "text is required")
        val text = args.optString("text")
        desktopSessionRegistry.recordAgentAction(pid)
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
