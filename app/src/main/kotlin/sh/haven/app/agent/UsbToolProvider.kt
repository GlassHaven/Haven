package sh.haven.app.agent

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mcp.McpError
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbIpServer
import sh.haven.core.usb.UsbProxyServer
import sh.haven.app.usb.UsbDriveVmManager

/**
 * The USB MCP tools (#mcp-backbone Stage 5, Layer E): enumerate/permission
 * USB devices and run control/bulk transfers ([UsbBroker]); expose a device
 * into the Linux guest (a loopback [UsbProxyServer] socket) or over USB/IP to
 * remote hosts ([UsbIpServer]); and open/unlock/close USB mass-storage drives
 * in a helper VM ([UsbDriveVmManager]). The device-label + device-JSON helpers
 * travel with it. [usbProxyServer] is shared with McpTools' list_bridges (the
 * Bridges registry), so the same instance is passed in rather than owned here.
 */
internal class UsbToolProvider(
    private val usbBroker: UsbBroker,
    private val usbIpServer: UsbIpServer,
    private val usbDriveVmManager: UsbDriveVmManager,
    private val usbProxyServer: UsbProxyServer,
    private val preferencesRepository: UserPreferencesRepository,
    private val localSessionManager: LocalSessionManager,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "list_usb_devices" to ToolHandler(
            description = "List USB devices attached to the phone (host/OTG). Each entry has deviceName (the stable /dev/bus/usb path used as the key for the other usb_* tools), vidPid, deviceClass, hasPermission, isOpen, and the interface/endpoint descriptors (id, class, endpoint address + direction + type). Manufacturer/product/serial strings are only filled once permission is held (call request_usb_permission). Read-only; never prompts.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listUsbDevices() },

        "request_usb_permission" to ToolHandler(
            description = "Request the Android runtime USB permission for a device (pops the system grant dialog) and open it, caching the connection for usb_control_transfer / usb_bulk_transfer. Idempotent: a no-op if permission is already held and the device is open. Returns the device info with hasPermission/isOpen reflecting the result.",
            inputSchema = objectSchema {
                string("deviceName", "deviceName from list_usb_devices (the /dev/bus/usb/BBB/DDD path).", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Grant the agent access to USB device ${usbLabel(args.optString("deviceName"))}?" },
        ) { args -> requestUsbPermission(args) },

        "usb_control_transfer" to ToolHandler(
            description = "Perform a USB endpoint-0 control transfer on an opened device. Args: deviceName, requestType (bmRequestType, int — bit 7 set = device-to-host/IN), request (bRequest), value (wValue), index (wIndex), dataBase64 (OUT payload, omit for IN), length (IN read length), timeoutMs (default 1000). Returns bytesTransferred and, for IN transfers, dataBase64. The device must already be opened via request_usb_permission.",
            inputSchema = objectSchema {
                string("deviceName", required = true)
                integer("requestType", "bmRequestType. Bit 7 (0x80) set = IN.", required = true)
                integer("request", "bRequest.", required = true)
                integer("value", "wValue.", required = true)
                integer("index", "wIndex.", required = true)
                string("dataBase64", "Base64 OUT payload; omit for IN.")
                integer("length", "IN read length; ignored for OUT.")
                integer("timeoutMs")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "USB control transfer to ${usbLabel(args.optString("deviceName"))}" },
        ) { args -> usbControlTransfer(args) },

        "usb_bulk_transfer" to ToolHandler(
            description = "Perform a USB bulk or interrupt transfer on an opened device. Direction is taken from the endpoint descriptor. Args: deviceName, endpoint (bEndpointAddress, int), dataBase64 (OUT payload, omit for IN), length (IN read length), timeoutMs (default 1000). The owning interface is claimed automatically. Returns bytesTransferred and, for IN endpoints, dataBase64.",
            inputSchema = objectSchema {
                string("deviceName", required = true)
                integer("endpoint", "bEndpointAddress from the interface descriptor.", required = true)
                string("dataBase64", "Base64 OUT payload; omit for IN endpoints.")
                integer("length", "IN read length; ignored for OUT.")
                integer("timeoutMs")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "USB bulk transfer to ${usbLabel(args.optString("deviceName"))}" },
        ) { args -> usbBulkTransfer(args) },

        "usb_attach_to_guest" to ToolHandler(
            description = "Expose a USB device to the proot Linux guest: opens it (requesting permission if needed) and binds the haven-usb proxy on an abstract LocalSocket the guest can reach, then stages the haven-usb-probe binary into the guest. Returns the socketName, the in-guest probePath, and a probeCommand you can run via run_in_proot to verify reachability. For a CDC-ACM serial device it also returns serialBridgeCommand (the haven-usb-serial PTY bridge) so unmodified serial apps (e.g. LIRC's lircd/mode2) can open it as /dev/pts/N. deviceName is optional when exactly one device is attached. This is the entry point for the guest-side USB shim (LD_PRELOAD/DllMap for HID, a PTY bridge for serial).",
            inputSchema = objectSchema {
                string("deviceName", "deviceName from list_usb_devices; optional if only one device is attached.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val n = args.optString("deviceName").ifBlank { "the attached USB device" }
                "Expose ${if (n.startsWith("/dev")) usbLabel(n) else n} to the Linux guest?"
            },
        ) { args -> usbAttachToGuest(args) },

        "detach_from_guest" to ToolHandler(
            description = "Stop the haven-usb guest proxy started by usb_attach_to_guest and release the brokered USB device handle (the guest's /dev/pts serial bridge or LD_PRELOAD HID routing stops working immediately). Pass keepOpen:true to leave the device handle open. The teardown counterpart to usb_attach_to_guest.",
            inputSchema = objectSchema {
                boolean("keepOpen", "Leave the brokered device handle open (default false = fully release it).")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> detachFromGuest(args) },

        "start_usbip_export" to ToolHandler(
            description = "Start a userspace USB/IP server exporting a phone-attached USB device over TCP (default port 3240) so a remote Linux host can `usbip attach` it as a real local device node — every app there (ssh, libfido2, browsers) sees it, with the touch happening on the phone. Opens the device (requesting permission if needed) and returns the busid, bound port, and the client-side attach command. deviceName is optional when exactly one device is attached. Pass loopbackOnly:true to bind 127.0.0.1 only (for use behind an SSH/WireGuard tunnel); the default binds all interfaces for direct LAN attach. This is the remote-host counterpart to usb_attach_to_guest (which targets the local proot guest, where usbip can't run — the Android kernel has no vhci-hcd).",
            inputSchema = objectSchema {
                string("deviceName", "deviceName from list_usb_devices; optional if only one device is attached.")
                boolean("loopbackOnly", "Bind 127.0.0.1 only (use behind a tunnel). Default false = all interfaces, LAN-reachable.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val n = args.optString("deviceName").ifBlank { "the attached USB device" }
                "Export ${if (n.startsWith("/dev")) usbLabel(n) else n} over USB/IP to remote hosts?"
            },
        ) { args -> startUsbipExport(args) },

        "stop_usbip_export" to ToolHandler(
            description = "Stop the USB/IP server started by start_usbip_export (closes the listening socket and any active client connection) and release the brokered USB device handle. Pass keepOpen:true to leave the handle open for a fast re-export.",
            inputSchema = objectSchema {
                boolean("keepOpen", "Leave the brokered device handle open for a fast re-export (default false = fully release it).")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> stopUsbipExport(args) },

        "list_usb_exports" to ToolHandler(
            description = "List active USB exports of phone-attached devices: the USB/IP server (start_usbip_export — to remote hosts) and the guest proxy (usb_attach_to_guest — to the local proot guest). Reports the exported device, busid/bound port, and whether a remote usbip client is currently attached. Read-only.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listUsbExports() },

        "open_usb_drive" to ToolHandler(
            description = "Open a phone-attached USB drive (mass storage — flash drive, SSD, SD reader) and surface its files as an ordinary connection. Two routes (#603): `route:\"vm\"` (default — boot the on-device QEMU Linux VM) gives the drive a REAL kernel, so ext4 / GPT / block partitions mount and their files are browseable: exports the drive over USB/IP, boots (or reuses, if another drive is already open) a small Alpine VM that imports it, mounts every partition (read-only unless `writable`), and runs sshd — then returns a loopback SSH/SFTP `profileId` you browse with list_directory / serve_file (and a terminal tab into the VM). A LUKS-encrypted partition mounts locked (reported in list_usb_drives' vm.locked) — call unlock_usb_drive_partition with its passphrase to mount it. The VM boot is slow (TCG, no KVM unrooted) + the first run installs packages, so this returns {status:\"starting\"} immediately — poll list_usb_drives until phase=ready (profileId set) or error. `route:\"android\"` skips the VM when Android itself has already mounted the drive (vfat/exFAT): returns {status:\"ready\", profileId:\"local\", volumePath, …} SYNCHRONOUSLY — do not poll list_usb_drives in that case; browse with list_directory(profileId=\"local\", path=volumePath). `route:\"auto\"` picks android when a mounted volume matches, else vm. See list_usb_drives' drives[].androidMounted to decide without booting. Consent-gated per session (mounting the user's disk is sensitive). Up to a phone-resource limit of concurrent VM drives (they share one VM, so this is a vhci-port/practical cap, not RAM); isochronous (webcam/audio) still can't pass.",
            inputSchema = objectSchema {
                string("deviceName", "deviceName from list_usb_devices / list_usb_drives; optional if exactly one USB drive is attached.")
                boolean("writable", "VM route only: mount read-write instead of the default read-only. An interrupted write (VM killed, app backgrounded under memory pressure) can corrupt the drive's filesystem — only set this when the caller genuinely needs to write. The android route's writability is governed by the All-files access grant (androidWritable in the response).")
                string(
                    "route",
                    "\"vm\" (default — boot the Linux VM), \"android\" (browse the drive Android already mounted, no VM; errors with -32602 if it isn't mounted), or \"auto\" (android when a mounted volume matches, else vm).",
                )
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val n = args.optString("deviceName").ifBlank { "the attached USB drive" }
                val label = if (n.startsWith("/dev")) usbLabel(n) else n
                val route = args.optString("route").ifBlank { "vm" }
                if (route == "android") {
                    "Open $label directly (Android-mounted, no VM)?"
                } else if (args.optBoolean("writable", false)) {
                    "Open $label in a Linux VM READ-WRITE? An interrupted write can corrupt the drive."
                } else {
                    "Open $label in a Linux VM and mount its files?"
                }
            },
        ) { args -> openUsbDrive(args) },

        "list_usb_drives" to ToolHandler(
            description = "List phone-attached USB mass-storage drives (the candidates for open_usb_drive) and every currently-open USB-drive VM in `vms` (up to a phone-resource concurrency limit): busid, phase (idle/opening/ready/error), the loopback SSH `profileId`, whether it's mounted read-only, any locked (LUKS) partitions awaiting unlock_usb_drive_partition, and the mounted paths once ready. Each drives[] entry also carries an `androidMount` object (#603): androidMounted + volumePath/volumeDescription/volumeReadOnly/androidWritable/routeConfidence when Android itself has mounted the drive — a cheaper open_usb_drive route:\"android\" exists for that drive. Read-only — poll this after open_usb_drive until the matching vms[] entry has phase=ready.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listUsbDrives() },

        "unlock_usb_drive_partition" to ToolHandler(
            description = "Unlock a LUKS-encrypted partition on an open USB-drive VM (see list_usb_drives' vms[].locked for candidates, e.g. \"sdb2\" → devicePath \"/dev/sdb2\") and mount it. Runs against the already-booted VM — no reboot. Returns the updated mount/locked lists; throws on a wrong passphrase.",
            inputSchema = objectSchema {
                string("busid", "Which open drive's VM (see list_usb_drives' vms[].busid); optional if exactly one is open.")
                string("devicePath", "e.g. /dev/sdb2 — the locked partition's device path inside the VM.", required = true)
                string("passphrase", "The LUKS passphrase.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Unlock ${args.optString("devicePath")} with the supplied passphrase?" },
        ) { args -> unlockUsbDrivePartition(args) },

        "close_usb_drive" to ToolHandler(
            description = "Close a USB-drive VM opened by open_usb_drive: power off the VM, stop its USB/IP export, and remove the transient SSH profile + ephemeral key. Idempotent.",
            inputSchema = objectSchema {
                string("busid", "Which open drive to close (see list_usb_drives' vms[].busid); optional if exactly one is open.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> closeUsbDrive(args) },

        "delete_usb_appliance" to ToolHandler(
            description = "Delete the persistent USB-helper Linux appliance — the small installed Alpine VM (with usbip+ssh baked in) that open_usb_drive boots to mount drives. It's provisioned once and kept so repeat opens are fast; deleting it frees the disk (~280 MB) and forces a one-time re-provision (re-download + install) on the next open_usb_drive. Closes any live USB-drive VM first. Idempotent.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ ->
                "Delete the USB-helper Linux appliance (~280 MB; re-provisioned on next drive open)?"
            },
        ) { _ -> deleteUsbAppliance() },
    )

    /** Short device label for consent prompts: "Evolv DNA 100C (9999:0001)" or just the vid:pid. */
    private fun usbLabel(deviceName: String): String =
        runCatching {
            usbBroker.listDevices().firstOrNull { it.deviceName == deviceName }?.let { d ->
                val name = d.productName ?: d.manufacturerName
                if (name != null) "$name (${d.vidPid})" else d.vidPid
            }
        }.getOrNull() ?: deviceName

    private fun usbDeviceJson(d: sh.haven.core.usb.UsbDeviceInfo): JSONObject = JSONObject().apply {
        put("deviceName", d.deviceName)
        put("vidPid", d.vidPid)
        put("vendorId", d.vendorId)
        put("productId", d.productId)
        put("deviceClass", d.deviceClass)
        put("manufacturerName", d.manufacturerName ?: JSONObject.NULL)
        put("productName", d.productName ?: JSONObject.NULL)
        put("serialNumber", d.serialNumber ?: JSONObject.NULL)
        put("hasPermission", d.hasPermission)
        put("isOpen", d.isOpen)
        put("interfaces", JSONArray().apply {
            d.interfaces.forEach { iface ->
                put(JSONObject().apply {
                    put("id", iface.id)
                    put("interfaceClass", iface.interfaceClass)
                    put("interfaceSubclass", iface.interfaceSubclass)
                    put("interfaceProtocol", iface.interfaceProtocol)
                    put("endpoints", JSONArray().apply {
                        iface.endpoints.forEach { ep ->
                            put(JSONObject().apply {
                                put("address", ep.address)
                                put("direction", ep.direction)
                                put("type", ep.type)
                                put("maxPacketSize", ep.maxPacketSize)
                            })
                        }
                    })
                })
            }
        })
    }

    private suspend fun listUsbDevices(): JSONObject = withContext(Dispatchers.IO) {
        val devices = usbBroker.listDevices()
        JSONObject().apply {
            put("count", devices.size)
            put("devices", JSONArray().apply { devices.forEach { put(usbDeviceJson(it)) } })
        }
    }

    private suspend fun requestUsbPermission(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val deviceName = args.optString("deviceName").ifBlank {
            throw McpError(-32602, "deviceName is required (from list_usb_devices)")
        }
        val info = try {
            usbBroker.openDevice(deviceName)
        } catch (e: Exception) {
            throw McpError(-32603, "USB open failed: ${e.message}")
        }
        usbDeviceJson(info)
    }

    private suspend fun usbControlTransfer(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val deviceName = args.optString("deviceName").ifBlank {
            throw McpError(-32602, "deviceName is required")
        }
        val requestType = args.getInt("requestType")
        val request = args.getInt("request")
        val value = args.getInt("value")
        val index = args.getInt("index")
        val timeoutMs = args.optInt("timeoutMs", 1000)
        val out = args.optString("dataBase64").takeIf { it.isNotBlank() }
            ?.let { Base64.decode(it, Base64.DEFAULT) }
        val length = args.optInt("length", out?.size ?: 0)
        val result = try {
            usbBroker.controlTransfer(deviceName, requestType, request, value, index, out, length, timeoutMs)
        } catch (e: Exception) {
            throw McpError(-32603, "controlTransfer failed: ${e.message}")
        }
        JSONObject().apply {
            put("bytesTransferred", result.bytesTransferred)
            if (result.data.isNotEmpty()) {
                put("dataBase64", Base64.encodeToString(result.data, Base64.NO_WRAP))
            }
        }
    }

    private suspend fun usbBulkTransfer(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val deviceName = args.optString("deviceName").ifBlank {
            throw McpError(-32602, "deviceName is required")
        }
        val endpoint = args.getInt("endpoint")
        val timeoutMs = args.optInt("timeoutMs", 1000)
        val out = args.optString("dataBase64").takeIf { it.isNotBlank() }
            ?.let { Base64.decode(it, Base64.DEFAULT) }
        val length = args.optInt("length", out?.size ?: 0)
        val result = try {
            usbBroker.bulkTransfer(deviceName, endpoint, out, length, timeoutMs)
        } catch (e: Exception) {
            throw McpError(-32603, "bulkTransfer failed: ${e.message}")
        }
        JSONObject().apply {
            put("bytesTransferred", result.bytesTransferred)
            if (result.data.isNotEmpty()) {
                put("dataBase64", Base64.encodeToString(result.data, Base64.NO_WRAP))
            }
        }
    }

    /**
     * Mono DllMap config that routes a HidSharp-based app's USB access to the
     * guest shim: libudev fully (the shim implements all 27 udev functions) and
     * the hidraw libc fileops per-function (other libc calls keep going to real
     * libc). Place beside the assembly declaring the [DllImport]s (HidSharp.dll
     * → HidSharp.dll.config). [shimPath] is the absolute in-guest shim path.
     */
    private fun monoDllMapConfig(shimPath: String): String = """
        <configuration>
          <dllmap dll="libudev.so.0" target="$shimPath"/>
          <dllmap dll="libudev.so.1" target="$shimPath"/>
          <dllmap dll="libc">
            <dllentry dll="$shimPath" name="open"  target="open"/>
            <dllentry dll="$shimPath" name="close" target="close"/>
            <dllentry dll="$shimPath" name="read"  target="read"/>
            <dllentry dll="$shimPath" name="write" target="write"/>
            <dllentry dll="$shimPath" name="ioctl" target="ioctl"/>
            <dllentry dll="$shimPath" name="poll"  target="poll"/>
          </dllmap>
        </configuration>
    """.trimIndent()

    private suspend fun usbAttachToGuest(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        // Master opt-in gate (Settings → "Expose USB devices to the Linux
        // guest"). Off by default — guest exposure lets any guest app reach the
        // device, so it needs a deliberate user switch on top of per-call consent.
        if (!preferencesRepository.usbGuestExposureEnabled.first()) {
            throw McpError(
                -32603,
                "USB-to-guest is disabled. Enable Settings → \"Expose USB devices to the Linux guest\" " +
                    "(or set the usb_guest_exposure_enabled preference) first. The direct usb_* transfer tools work without it.",
            )
        }
        val requested = args.optString("deviceName").takeIf { it.isNotBlank() }
        val deviceName = requested ?: run {
            val devices = usbBroker.listDevices()
            when (devices.size) {
                0 -> throw McpError(-32602, "No USB devices attached.")
                1 -> devices.single().deviceName
                else -> throw McpError(-32602, "Multiple USB devices attached — pass deviceName. Found: ${devices.joinToString { it.deviceName }}")
            }
        }
        val info = try {
            usbBroker.openDevice(deviceName)
        } catch (e: Exception) {
            throw McpError(-32603, "USB open failed: ${e.message}")
        }
        val socketName = usbProxyServer.start(deviceName)
        val probePath = localSessionManager.prootManager.stageHavenUsbArtifacts()
        val shimPath = localSessionManager.prootManager.havenUsbShimGuestPath
        // CDC-ACM serial bridge: for a serial device, point off-the-shelf serial
        // apps at a real guest PTY backed by the brokered device (no kernel
        // cdc_acm, no LD_PRELOAD) via the staged haven-usb-serial helper.
        val serialPath = localSessionManager.prootManager.havenUsbSerialGuestPath
        val cdcData = info.interfaces.firstOrNull { it.interfaceClass == 10 }
        val bulkOutEp = cdcData?.endpoints?.firstOrNull { it.type == "bulk" && it.direction == "out" }?.address
        val bulkInEp = cdcData?.endpoints?.firstOrNull { it.type == "bulk" && it.direction == "in" }?.address
        val isCdcAcm = info.interfaces.any { it.interfaceClass == 2 && it.interfaceSubclass == 2 } &&
            bulkOutEp != null && bulkInEp != null
        JSONObject().apply {
            put("device", usbDeviceJson(info))
            put("socketName", socketName)
            put("socketNamespace", "abstract")
            put("probePath", probePath ?: JSONObject.NULL)
            if (probePath != null) put("probeCommand", probePath)
            put("shimPath", shimPath)
            if (isCdcAcm && bulkOutEp != null && bulkInEp != null) {
                val cmd = "%s 0x%02x 0x%02x".format(serialPath, bulkOutEp, bulkInEp)
                put("cdcAcm", true)
                put("serialBridgeCommand", cmd)
                put(
                    "serialBridgeNote",
                    "CDC-ACM serial device. Run `$cmd` via run_in_proot(background:true); it prints " +
                        "`pts: /dev/pts/N`. Point an unmodified serial app at that path, e.g. " +
                        "`lircd --driver irtoy --device /dev/pts/N` or `mode2 --driver irtoy --device /dev/pts/N`.",
                )
            }
            // For a NATIVE HID app, prepend this so its /dev/hidraw* opens are
            // routed to the brokered device (no real node, no root).
            put("ldPreloadWrapper", "LD_PRELOAD=$shimPath")
            put("hidrawTestCommand", "LD_PRELOAD=$shimPath /usr/local/bin/haven-hidraw-test /dev/hidraw0")
            // For a MONO/.NET HID app (e.g. HidSharp-based), LD_PRELOAD can't
            // interpose P/Invoke — use a DllMap config beside the assembly that
            // declares the [DllImport]s (HidSharp.dll -> HidSharp.dll.config),
            // mapping libudev wholesale + the hidraw libc fileops to the shim.
            put("monoDllMapConfigName", "HidSharp.dll.config")
            put("monoDllMapConfig", monoDllMapConfig(shimPath))
            put("note", "Proxy bound on abstract socket \\0$socketName. Native apps: prepend ldPreloadWrapper (verify with hidrawTestCommand via run_in_proot). Mono apps: write monoDllMapConfig as monoDllMapConfigName next to the assembly's HidSharp.dll, then run the app under mono.")
        }
    }

    private suspend fun detachFromGuest(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        // Capture before stop() clears it, so we can release the right handle.
        val deviceName = usbProxyServer.proxyDeviceName
        usbProxyServer.stop()
        val keepOpen = args.optBoolean("keepOpen", false)
        if (!keepOpen && deviceName != null) usbBroker.closeDevice(deviceName)
        JSONObject().apply {
            put("stopped", true)
            put("deviceReleased", !keepOpen && deviceName != null)
        }
    }

    private suspend fun startUsbipExport(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val requested = args.optString("deviceName").takeIf { it.isNotBlank() }
        val deviceName = requested ?: run {
            val devices = usbBroker.listDevices()
            when (devices.size) {
                0 -> throw McpError(-32602, "No USB devices attached.")
                1 -> devices.single().deviceName
                else -> throw McpError(-32602, "Multiple USB devices attached — pass deviceName. Found: ${devices.joinToString { it.deviceName }}")
            }
        }
        val info = try {
            usbBroker.openDevice(deviceName)
        } catch (e: Exception) {
            throw McpError(-32603, "USB open failed: ${e.message}")
        }
        val loopbackOnly = args.optBoolean("loopbackOnly", false)
        val bind = if (loopbackOnly) "127.0.0.1" else null
        val port = usbIpServer.start(deviceName, bindAddress = bind)
        // busid as the kernel client expects it: "<busnum>-<devnum>" from /dev/bus/usb/BBB/DDD.
        val parts = deviceName.trimEnd('/').split('/')
        val busid = "${parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1}-${parts.lastOrNull()?.toIntOrNull() ?: 1}"
        JSONObject().apply {
            put("device", usbDeviceJson(info))
            put("busid", busid)
            put("port", port)
            put("bind", bind ?: "0.0.0.0")
            put("attachCommand", "sudo modprobe vhci-hcd && sudo usbip attach -r <phone-ip> -b $busid")
            put("detachNote", "Detach on the client with `sudo usbip detach -p 00` (port from `usbip port`).")
            put(
                "note",
                "USB/IP server bound on ${bind ?: "0.0.0.0"}:$port exporting $busid. On a Linux client with the " +
                    "usbip tool + vhci-hcd module loaded, run the attachCommand (replace <phone-ip> with the phone's " +
                    "address). loopbackOnly=$loopbackOnly — when true, reach it through a forwarded port.",
            )
        }
    }

    private suspend fun stopUsbipExport(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        // Capture before stop() clears exportedDeviceName, so we release the right handle.
        val deviceName = usbIpServer.exportedDeviceName
        usbIpServer.stop()
        val keepOpen = args.optBoolean("keepOpen", false)
        if (!keepOpen && deviceName != null) usbBroker.closeDevice(deviceName)
        JSONObject().apply {
            put("stopped", true)
            put("deviceReleased", !keepOpen && deviceName != null)
        }
    }

    private suspend fun listUsbExports(): JSONObject = withContext(Dispatchers.IO) {
        val devices = usbBroker.listDevices()
        fun deviceFor(name: String?): Any =
            name?.let { n -> devices.firstOrNull { it.deviceName == n }?.let { usbDeviceJson(it) } } ?: JSONObject.NULL
        val usbipName = usbIpServer.exportedDeviceName
        val usbip = JSONObject().apply {
            put("running", usbIpServer.isRunning)
            put("deviceName", usbipName ?: JSONObject.NULL)
            put("boundPort", usbIpServer.boundPort ?: JSONObject.NULL)
            if (usbipName != null) {
                val parts = usbipName.trimEnd('/').split('/')
                put("busid", "${parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1}-${parts.lastOrNull()?.toIntOrNull() ?: 1}")
            }
            put("clientCount", usbIpServer.clientCount)
            put("clientAttached", usbIpServer.clientCount > 0)
            put("device", deviceFor(usbipName))
        }
        val proxyName = usbProxyServer.proxyDeviceName
        val proxy = JSONObject().apply {
            put("running", usbProxyServer.isRunning)
            put("deviceName", proxyName ?: JSONObject.NULL)
            put("socketName", usbProxyServer.socketName)
            put("device", deviceFor(proxyName))
        }
        JSONObject().apply {
            put("usbip", usbip)
            put("proxy", proxy)
        }
    }

    /**
     * Resolve which open drive's busid a call means: the explicit [argName] arg
     * if given, else the single currently-open session, else an McpError
     * listing the ambiguity (mirrors UsbDriveVmManager.resolveDrive's shape for
     * deviceName). Up to QemuManager.MAX_CONCURRENT_DRIVES can be open at once.
     */
    private fun resolveUsbDriveBusid(args: JSONObject, argName: String = "busid"): String {
        args.optString(argName).takeIf { it.isNotBlank() }?.let { return it }
        val sessions = usbDriveVmManager.sessions.value
        return when (sessions.size) {
            0 -> throw McpError(-32602, "No USB drive is open.")
            1 -> sessions.keys.single()
            else -> throw McpError(
                -32602,
                "Multiple USB drives open — pass $argName. Open: ${sessions.keys.joinToString()}",
            )
        }
    }

    private suspend fun openUsbDrive(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val requested = args.optString("deviceName").takeIf { it.isNotBlank() }
        val writable = args.optBoolean("writable", false)
        val route = args.optString("route").ifBlank { "vm" }
        if (route !in setOf("vm", "android", "auto")) {
            throw McpError(-32602, "route must be \"vm\", \"android\" or \"auto\" (got \"$route\")")
        }
        // #603: the android route skips the VM when Android itself has mounted
        // the drive (vold's volume correlated with the device). "auto" falls
        // back to the VM silently; explicit "android" errors instead.
        if (route == "android" || route == "auto") {
            val match = try {
                // Short await rides vold's ~0.5–2s post-attach mount race
                // without making a wrong "not mounted" verdict likely.
                usbDriveVmManager.androidMount(requested, timeoutMs = 2_000)
            } catch (e: sh.haven.app.usb.UsbDriveVmManager.UsbVmException) {
                throw McpError(-32602, e.message ?: "Failed to resolve USB drive")
            }
            if (match != null) {
                val json = JSONObject().apply {
                    put("status", "ready")
                    put("route", route)
                    put("deviceName", match.deviceName)
                    put("profileId", "local")
                    put("volumePath", match.volume.path)
                    put("volumeDescription", match.volume.description ?: JSONObject.NULL)
                    put("readOnly", match.volume.readOnly)
                    put("androidWritable", match.writable)
                    put(
                        "routeConfidence",
                        if (match.confidence == sh.haven.app.usb.UsbMountConfidence.ATTACH_DIFF) "attach-diff" else "unclaimed-volume",
                    )
                    put(
                        "note",
                        "Android mounted this drive; browse it with list_directory(profileId=\"local\", path=\"${match.volume.path}\"). " +
                            "No VM was booted. Writes need All-files access (MANAGE_EXTERNAL_STORAGE); a read-only volume or a missing grant makes this read-only. " +
                            "Use route=\"vm\" for ext4/GPT/LUKS or anything Android couldn't mount.",
                    )
                }
                if (route == "android") return@withContext json
                return@withContext json.put("route", "auto")
            }
            if (route == "android") {
                throw McpError(
                    -32602,
                    "Android has not mounted this drive (vfat/exFAT are mounted by Android; ext4/LUKS are not). Use route=\"vm\".",
                )
            }
        }
        val deviceName = try {
            usbDriveVmManager.open(requested, writable)
        } catch (e: sh.haven.app.usb.UsbDriveVmManager.UsbVmException) {
            throw McpError(-32603, e.message ?: "Failed to open USB drive")
        }
        JSONObject().apply {
            put("status", "starting")
            put("route", "vm")
            put("deviceName", deviceName)
            put("readOnly", !writable)
            if (route == "auto") put("autoNote", "Android hadn't mounted this drive, so the Linux VM route was taken.")
            put(
                "note",
                "Booting a Linux VM and mounting the drive (slow under TCG; the first run installs packages). " +
                    "Poll list_usb_drives until the matching vms[] entry has phase=ready (profileId set), then browse " +
                    "its mounts with list_directory(profileId, path). A LUKS-encrypted partition mounts locked (see " +
                    "locked) — call unlock_usb_drive_partition (with busid, since more than one drive can be open) " +
                    "with its passphrase. Up to a phone-resource limit of concurrent drives; open_usb_drive errors if " +
                    "already at that limit.",
            )
        }
    }

    private suspend fun unlockUsbDrivePartition(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val busid = resolveUsbDriveBusid(args)
        val devicePath = args.optString("devicePath").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "devicePath is required (see the locked list from list_usb_drives, e.g. /dev/sdb2)")
        val passphrase = args.optString("passphrase").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "passphrase is required")
        try {
            usbDriveVmManager.unlockPartition(busid, devicePath, passphrase)
        } catch (e: sh.haven.app.usb.UsbDriveVmManager.UsbVmException) {
            throw McpError(-32603, e.message ?: "Failed to unlock partition")
        }
        val st = usbDriveVmManager.sessions.value[busid]
        JSONObject().apply {
            put("unlocked", true)
            put("mounts", JSONArray().apply { st?.mounts?.forEach { put(it) } })
            put("locked", JSONArray().apply { st?.locked?.forEach { put(it) } })
        }
    }

    /** The #603 Android-mount correlation fields for one drive (absent when nothing matched). */
    private fun androidMountJson(d: sh.haven.core.usb.UsbDeviceInfo): JSONObject = JSONObject().apply {
        val match = usbDriveVmManager.androidMountSnapshot(d.deviceName)
        put("androidMounted", match != null)
        if (match != null) {
            put("volumePath", match.volume.path)
            put("volumeDescription", match.volume.description ?: JSONObject.NULL)
            put("volumeReadOnly", match.volume.readOnly)
            put("androidWritable", match.writable)
            put(
                "routeConfidence",
                if (match.confidence == sh.haven.app.usb.UsbMountConfidence.ATTACH_DIFF) "attach-diff" else "unclaimed-volume",
            )
        }
    }

    private suspend fun listUsbDrives(): JSONObject = withContext(Dispatchers.IO) {
        val drives = usbDriveVmManager.massStorageDevices()
        val sessions = usbDriveVmManager.sessions.value
        JSONObject().apply {
            put("drives", JSONArray().apply {
                drives.forEach { d ->
                    put(usbDeviceJson(d).apply {
                        // route:"android" candidates for open_usb_drive (see that
                        // tool's description). Claim bookkeeping inside the
                        // correlator is idempotent per device, so polling here
                        // doesn't disturb matching.
                        put("androidMount", androidMountJson(d))
                    })
                }
            })
            put("vms", JSONArray().apply {
                sessions.forEach { (busid, st) ->
                    put(
                        JSONObject().apply {
                            put("busid", busid)
                            put("phase", st.phase.name.lowercase())
                            put("stage", st.stage)
                            put("deviceName", st.deviceName ?: JSONObject.NULL)
                            put("profileId", st.profileId ?: JSONObject.NULL)
                            put("sshPort", st.sshPort)
                            put("mounts", JSONArray().apply { st.mounts.forEach { put(it) } })
                            put("readOnly", st.readOnly)
                            put("locked", JSONArray().apply { st.locked.forEach { put(it) } })
                            put("error", st.error ?: JSONObject.NULL)
                        },
                    )
                }
            })
            // Whether the persistent helper appliance is provisioned (first open
            // provisions it once; subsequent opens are fast). delete_usb_appliance
            // clears it.
            put("applianceProvisioned", usbDriveVmManager.applianceProvisioned)
        }
    }

    private suspend fun closeUsbDrive(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val busid = resolveUsbDriveBusid(args)
        usbDriveVmManager.close(busid)
        JSONObject().apply { put("closed", true) }
    }

    private suspend fun deleteUsbAppliance(): JSONObject = withContext(Dispatchers.IO) {
        val was = usbDriveVmManager.applianceProvisioned
        usbDriveVmManager.deleteAppliance()
        JSONObject().apply {
            put("deleted", was)
            put("note", "USB-helper appliance removed; the next open_usb_drive re-provisions it (one-time).")
        }
    }
}
