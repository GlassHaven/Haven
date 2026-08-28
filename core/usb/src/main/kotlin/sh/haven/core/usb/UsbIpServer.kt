package sh.haven.core.usb

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Userspace USB/IP server. Exports one [UsbBroker]-owned device over TCP so a
 * stock `usbip attach` client on a remote Linux host imports it as a real USB
 * device node — no per-app shim, every guest app (ssh, libfido2, browsers) sees
 * it, touch happens on the phone.
 *
 * This is the remote-host counterpart to [UsbProxyServer] (which serves the
 * proot guest over a LocalSocket): the proot guest can't be a usbip *client*
 * because the Android kernel ships no `vhci-hcd`, so usbip is the over-the-wire
 * path and the bespoke proxy stays the local-guest path.
 *
 * One shared server (one port) can export multiple devices at once, each
 * client's IMPORT request selecting one by busid ([export]/[unexport]) —
 * e.g. two concurrent on-device USB-drive VMs (#287). [start]/[stop] keep
 * the older single-device replace-everything behaviour for the remote-host
 * export MCP verbs. URBs are serialized **per (endpoint,
 * direction)** lane: same endpoint runs in submission order (a multi-frame
 * CTAPHID write — init + continuation frames on interrupt-OUT — must reach the
 * device in order or it rejects the whole message as INVALID_LENGTH), while
 * different endpoints run concurrently so a blocking interrupt-IN (e.g. the
 * composite key's idle CCID status poll, or a FIDO key awaiting touch) never
 * starves the FIDO traffic on another endpoint.
 *
 * UNLINK: Android has no per-transfer cancel, so an unlinked transfer runs to
 * completion in its pool thread — but its late RET_SUBMIT MUST be dropped. A
 * RET_SUBMIT for a seqnum the client already unlinked makes the vhci client
 * tear down the *whole* device ("cannot find a urb of seqnum N"), which is what
 * killed forwarding of a composite key (idle CCID/FIDO interrupt-INs get
 * unlinked, then their stale completion desynced the stream). We track unlinked
 * seqnums and suppress their reply; the thread just exits quietly.
 */
@Singleton
class UsbIpServer @Inject constructor(
    private val broker: UsbBroker,
) {
    @Volatile private var serverSocket: ServerSocket? = null

    // busid -> exported deviceName. One shared ServerSocket serves every export;
    // each client's IMPORT request picks a device by busid (the wire protocol
    // already carries it — describeForImport looks it up below). This is what
    // makes concurrent exports (e.g. two on-device USB-drive VMs) possible on
    // one port instead of one device monopolising the whole server (#287
    // multi-drive). [start]/[stop] (used by the single-device remote-host export
    // MCP verbs) keep their old replace-everything semantics on top of this map.
    private val exported = java.util.concurrent.ConcurrentHashMap<String, String>()

    val isRunning: Boolean get() = serverSocket != null
    val boundPort: Int? get() = serverSocket?.localPort

    /** The exported device's `/dev/bus/usb/...` name, when exactly one export is active. */
    val exportedDeviceName: String? get() = exported.values.singleOrNull()

    private val clients = java.util.concurrent.atomic.AtomicInteger(0)

    /** Live client sockets, so [stop] can close them and unblock their serve() threads. */
    private val clientSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    /** Remote usbip clients currently connected (>0 ⇒ a host has attached). */
    val clientCount: Int get() = clients.get()

    /**
     * Bind the usbip server for [deviceName] (already opened via
     * [UsbBroker.openDevice]), replacing whatever else was exported. [bindAddress]
     * null binds all interfaces (LAN + loopback); pass "127.0.0.1" to keep it
     * loopback-only behind a tunnel. Returns the bound port. For the
     * single-device remote-host export path (MCP `start_usbip_export`); a
     * concurrent multi-device export ([export]/[unexport]) collides with this
     * on the shared socket exactly like it did before this class supported
     * more than one device — not a new limitation.
     */
    @Synchronized
    fun start(deviceName: String, port: Int = USBIP_PORT, bindAddress: String? = null): Int {
        if (serverSocket != null && exported.values.singleOrNull() == deviceName) return serverSocket!!.localPort
        stop()
        export(deviceName, port, bindAddress)
        return serverSocket!!.localPort
    }

    /**
     * Export [deviceName] (already opened via [UsbBroker.openDevice]) over
     * USB/IP alongside any other current exports, starting the shared server if
     * this is the first one. Returns the busid a `usbip attach -b <busid>`
     * client should use to reach it.
     */
    @Synchronized
    fun export(deviceName: String, port: Int = USBIP_PORT, bindAddress: String? = null): String {
        val busid = busidOf(deviceName)
        exported[busid] = deviceName
        if (serverSocket == null) {
            val addr = bindAddress?.let { InetAddress.getByName(it) }
            // Build unbound so SO_REUSEADDR is set *before* bind: the immediate-bind
            // ServerSocket(port, backlog, addr) ctor binds with REUSEADDR off, so a
            // re-export within one app session (after stop(), while the prior port is
            // in TIME_WAIT) fails with EADDRINUSE. Enabling it lets the rebind succeed.
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(addr, port), BACKLOG)
            }
            serverSocket = socket
            thread(name = "haven-usbip-accept", isDaemon = true) {
                Log.i(TAG, "usbip server listening on ${addr ?: "0.0.0.0"}:${socket.localPort}")
                while (serverSocket === socket) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        if (serverSocket === socket) Log.w(TAG, "accept failed: ${e.message}")
                        break
                    }
                    thread(name = "haven-usbip-conn", isDaemon = true) { serve(client) }
                }
            }
        }
        Log.i(TAG, "exporting $deviceName as $busid (${exported.size} device(s) total)")
        return busid
    }

    /** Stop exporting just [busid]; stops the whole server once nothing is left exported. */
    @Synchronized
    fun unexport(busid: String) {
        exported.remove(busid)
        if (exported.isEmpty()) stop()
    }

    @Synchronized
    fun stop() {
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
        exported.clear()
        // Closing the server socket doesn't close already-accepted client sockets,
        // so without this their serve() threads stay blocked on read/write and
        // clientCount lingers >0 until the remote's TCP errors. Close them now so
        // they exit and decrement promptly.
        clientSockets.forEach { runCatching { it.close() } }
        clientSockets.clear()
    }

    // "/dev/bus/usb/BBB/DDD" -> "B-D", matching describeForImport's own busid.
    private fun busidOf(deviceName: String): String {
        val parts = deviceName.trimEnd('/').split('/')
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
        val dev = parts.lastOrNull()?.toIntOrNull() ?: 1
        return "$bus-$dev"
    }

    private fun serve(client: Socket) {
        clients.incrementAndGet()
        clientSockets.add(client)
        try {
            serveConnection(client)
        } finally {
            clientSockets.remove(client)
            clients.decrementAndGet()
        }
    }

    private fun serveConnection(client: Socket) {
        // One single-thread lane per (endpoint, direction): in-order within a lane,
        // concurrent across lanes. Keyed by ep<<1|direction (DIR_IN=1, DIR_OUT=0).
        val lanes = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.ExecutorService>()
        fun laneFor(ep: Int, direction: Int): java.util.concurrent.ExecutorService =
            lanes.getOrPut((ep shl 1) or direction) { Executors.newSingleThreadExecutor() }
        // Seqnums the client has unlinked: their (late) RET_SUBMIT must be dropped.
        val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        client.use { sock ->
            sock.tcpNoDelay = true
            val input = DataInputStream(sock.getInputStream().buffered())
            val output = DataOutputStream(sock.getOutputStream())
            val writeLock = Any()

            // Phase 1: op handshake (expect a single IMPORT).
            val op = try {
                UsbIpProtocol.readOpRequest(input)
            } catch (e: Exception) {
                Log.w(TAG, "op handshake failed: ${e.message}"); return
            } ?: return
            val device: String
            when (op) {
                is UsbIpProtocol.OpRequest.Import -> {
                    device = exported[op.busid] ?: run {
                        Log.w(TAG, "IMPORT of busid ${op.busid} failed — not currently exported")
                        synchronized(writeLock) {
                            output.write(UsbIpProtocol.encodeImportReply(null)); output.flush()
                        }
                        return
                    }
                    val dev = runCatching { describeForImport(device) }.getOrNull()
                    synchronized(writeLock) {
                        output.write(UsbIpProtocol.encodeImportReply(dev)); output.flush()
                    }
                    if (dev == null) {
                        Log.w(TAG, "IMPORT of $device failed — no descriptors"); return
                    }
                    Log.i(TAG, "IMPORT ${op.busid} -> $device ok; entering URB loop")
                }
            }

            // Phase 2: URB stream.
            val backend = BrokerBackend(broker, device)
            while (true) {
                // Device unplugged / re-enumerated (e.g. esptool resetting an ESP32-S3):
                // stop rather than spin replying EPIPE on a dead handle. Exiting the
                // client.use block closes the socket and drains the lanes below.
                if (!broker.isOpen(device)) {
                    Log.i(TAG, "device $device no longer open — ending export connection"); break
                }
                val urb = try {
                    UsbIpProtocol.readUrb(input)
                } catch (e: Exception) {
                    Log.w(TAG, "URB read failed: ${e.message}"); break
                } ?: break
                when (urb) {
                    is UsbIpProtocol.Urb.Submit -> laneFor(urb.ep, urb.direction).execute {
                        // Interrupt/bulk IN stays pending (short-poll) until data or unlink;
                        // OUT/control is a single bounded transfer.
                        val reply = if (urb.direction == UsbIpProtocol.DIR_IN && urb.ep != 0) {
                            pollIn(urb, backend, cancelled, IN_POLL_MS)
                        } else {
                            bridgeSubmit(urb, backend, TRANSFER_TIMEOUT_MS)
                        }
                        synchronized(writeLock) {
                            // Drop the reply if the client unlinked this URB while it ran —
                            // a stale RET_SUBMIT desyncs vhci and disconnects the device.
                            val wasCancelled = cancelled.remove(urb.seqnum)
                            if (reply != null && !wasCancelled) {
                                runCatching { output.write(reply); output.flush() }
                            }
                        }
                    }
                    is UsbIpProtocol.Urb.Unlink -> synchronized(writeLock) {
                        cancelled.add(urb.unlinkSeqnum)
                        runCatching {
                            output.write(UsbIpProtocol.encodeUnlinkReply(urb.seqnum, status = 0)); output.flush()
                        }
                    }
                }
            }
        }
        lanes.values.forEach { it.shutdownNow() }
    }

    /** Build the OP_REP_IMPORT device record from the brokered descriptors. */
    private fun describeForImport(device: String): UsbIpProtocol.Device {
        val raw = broker.rawDescriptors(device)
        require(raw.size >= 18) { "short device descriptor (${raw.size} bytes)" }
        fun u8(i: Int) = raw[i].toInt() and 0xFF
        fun u16le(i: Int) = u8(i) or (u8(i + 1) shl 8)
        // busnum/devnum from "/dev/bus/usb/BBB/DDD".
        val parts = device.trimEnd('/').split('/')
        val busnum = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
        val devnum = parts.lastOrNull()?.toIntOrNull() ?: 1
        // Active-config descriptor follows the 18-byte device descriptor.
        val configValue = if (raw.size >= 18 + 6) u8(18 + 5) else 1
        val config = if (raw.size > 18) raw.copyOfRange(18, raw.size) else ByteArray(0)
        return UsbIpProtocol.Device(
            busid = "$busnum-$devnum",
            busnum = busnum,
            devnum = devnum,
            // Report the device's real speed. It was hardcoded FULL ("cosmetic"
            // for full-speed FIDO keys), but a high-speed device (mass storage,
            // 512-byte bulk endpoints) imported as full-speed makes the guest's
            // vhci_hcd mis-enumerate it — the drive never appears (#287 empty
            // mount). Detect from the endpoint packet sizes.
            speed = detectSpeed(config, u8(7)),
            idVendor = u16le(8),
            idProduct = u16le(10),
            bcdDevice = u16le(12),
            deviceClass = u8(4),
            deviceSubClass = u8(5),
            deviceProtocol = u8(6),
            configurationValue = configValue,
            numConfigurations = u8(17),
            // FIDO keys export interface 0 only (CCID stripped, see filterToFidoInterface);
            // every other device keeps its real count so multi-interface classes
            // (e.g. CDC serial: control + data) fully enumerate on the host.
            numInterfaces = if (interface0IsHid(config)) 1
                else if (config.size >= 5) config[4].toInt() and 0xFF else 1,
        )
    }

    companion object {
        /**
         * Infer the device's USB speed from its endpoint descriptors. Full-speed
         * bulk/interrupt endpoints cap at 64; high-speed bulk carries 512; a
         * SuperSpeed bulk carries 1024. So max endpoint size picks HIGH vs FULL,
         * and a SuperSpeed-only signal promotes to SUPER:
         *  - bulk wMaxPacketSize ≥ 1024 (only legal at SuperSpeed), or
         *  - the device descriptor's bMaxPacketSize0 == 9, which is the encoded
         *    512-byte SuperSpeed ep0 size (a kernel accepts 9 only on an SS port).
         * Reporting an SS drive as high-speed makes the guest's vhci_hcd reject
         * ep0 maxpacket 9 and fail enumeration — "unable to enumerate USB
         * device" — so the drive never appears (#506).
         * [ep0MaxPacket] is bMaxPacketSize0 from the 18-byte device descriptor.
         */
        internal fun detectSpeed(config: ByteArray, ep0MaxPacket: Int): Int {
            var i = 0
            var maxPkt = 0
            while (i + 1 < config.size) {
                val len = config[i].toInt() and 0xFF
                if (len < 2) break
                val type = config[i + 1].toInt() and 0xFF
                if (type == 0x05 && i + 5 < config.size) { // ENDPOINT descriptor
                    val mps = ((config[i + 4].toInt() and 0xFF) or
                        ((config[i + 5].toInt() and 0xFF) shl 8)) and 0x7FF // bits 0-10 = size
                    if (mps > maxPkt) maxPkt = mps
                }
                i += len
            }
            if (maxPkt >= 1024 || ep0MaxPacket == 9) return USB_SPEED_SUPER
            return if (maxPkt >= 512) USB_SPEED_HIGH else USB_SPEED_FULL
        }

        private const val TAG = "UsbIpServer"
        const val USBIP_PORT = 3240
        private const val BACKLOG = 4
        private const val USB_SPEED_FULL = 2
        private const val USB_SPEED_HIGH = 3
        internal const val USB_SPEED_SUPER = 4
        /** Upper bound for a single OUT/control transfer; real ones complete in ms. */
        private const val TRANSFER_TIMEOUT_MS = 5_000
        /** Slice for polling an interrupt/bulk IN so an idle read never wedges its lane. */
        private const val IN_POLL_MS = 250

        /**
         * Translate one SUBMIT into a backend transfer and encode the
         * RET_SUBMIT reply. Pure but for [backend]; unit-tested with a fake.
         */
        fun bridgeSubmit(urb: UsbIpProtocol.Urb.Submit, backend: UsbIpBackend, timeoutMs: Int): ByteArray = try {
            val isIn = urb.direction == UsbIpProtocol.DIR_IN
            val inData = if (urb.ep == 0) {
                // Control: the 8-byte setup packet is USB-native little-endian.
                val s = urb.setup
                fun u8(i: Int) = s[i].toInt() and 0xFF
                val rt = u8(0); val req = u8(1)
                val wValue = u8(2) or (u8(3) shl 8)
                val wIndex = u8(4) or (u8(5) shl 8)
                val wLength = u8(6) or (u8(7) shl 8)
                if (rt == 0x80 && req == 0x06 && (wValue shr 8) == 0x02) {
                    // GET_DESCRIPTOR(CONFIGURATION): export only interface 0 (FIDO).
                    // Fetch the real descriptor in full, strip the CCID/other
                    // interfaces, then honour the client's requested length.
                    val full = backend.control(0x80, 0x06, wValue, wIndex, ByteArray(0), 0xFF, timeoutMs)
                    filterToFidoInterface(full).let { it.copyOf(minOf(it.size, wLength)) }
                } else {
                    backend.control(rt, req, wValue, wIndex, urb.out, wLength, timeoutMs)
                }
            } else {
                val address = urb.ep or if (isIn) 0x80 else 0x00
                backend.bulk(address, urb.out, urb.transferBufferLength, timeoutMs)
            }
            val actualLength = if (isIn) inData.size else urb.transferBufferLength
            UsbIpProtocol.encodeSubmitReply(urb.seqnum, status = 0, actualLength = actualLength, inData = if (isIn) inData else ByteArray(0))
        } catch (e: Exception) {
            Log.w(TAG, "URB seq=${urb.seqnum} ep=${urb.ep} failed: ${e.message}")
            UsbIpProtocol.encodeSubmitReply(urb.seqnum, status = -EPIPE, actualLength = 0, inData = ByteArray(0))
        }

        /**
         * Service an interrupt/bulk IN URB by polling in [pollMs] slices, keeping
         * it pending (no reply) until data arrives or the client unlinks it. This
         * is the correct interrupt-endpoint semantic and, given Android serializes
         * transfers per connection, stops an idle standing read (e.g. a composite
         * key's CCID status poll, or a FIDO key between commands) from wedging its
         * lane or disrupting the endpoint with a long timeout-failure. Returns the
         * RET_SUBMIT bytes once data arrives, or null if unlinked/interrupted first.
         */
        fun pollIn(urb: UsbIpProtocol.Urb.Submit, backend: UsbIpBackend, cancelled: Set<Int>, pollMs: Int): ByteArray? {
            val address = urb.ep or 0x80
            // backend.isAlive() guard: a standing interrupt-IN poll on an idle
            // connection must exit when the device is unplugged, not spin on
            // failing transfers until the client happens to send another URB.
            while (urb.seqnum !in cancelled && !Thread.currentThread().isInterrupted && backend.isAlive()) {
                val data = try {
                    backend.bulk(address, urb.out, urb.transferBufferLength, pollMs)
                } catch (e: Exception) {
                    ByteArray(0) // NAK / no data this slice — keep the URB pending
                }
                if (data.isNotEmpty()) {
                    return UsbIpProtocol.encodeSubmitReply(urb.seqnum, status = 0, actualLength = data.size, inData = data)
                }
            }
            return null
        }

        /**
         * Reduce a full CONFIGURATION descriptor to just interface 0 (the FIDO
         * HID function), dropping a composite key's CCID/other interfaces. The
         * remote host then never binds or polls them — their traffic (e.g. pcscd
         * hammering the smartcard interface) would otherwise starve FIDO on
         * Android's serialized [UsbDeviceConnection]. Left unchanged if [config]
         * isn't a configuration descriptor or is too short to parse.
         */
        fun filterToFidoInterface(config: ByteArray): ByteArray {
            if (config.size < 9 || (config[1].toInt() and 0xFF) != 0x02) return config
            // Interface-0-only is the FIDO case (a composite key whose CCID interface
            // would starve FIDO on Android's serialized connection). Any other device
            // keeps its whole config — e.g. a CDC serial adapter's data interface is
            // #1, and stripping it drops the bulk endpoints the host's cdc_acm needs.
            if (!interface0IsHid(config)) return config
            val body = ArrayList<Byte>()
            var i = 9
            var keep = false
            while (i + 2 <= config.size) {
                val len = config[i].toInt() and 0xFF
                if (len < 2 || i + len > config.size) break
                if ((config[i + 1].toInt() and 0xFF) == 0x04) { // INTERFACE descriptor
                    keep = (config[i + 2].toInt() and 0xFF) == 0 // keep only interface 0
                }
                if (keep) for (j in i until i + len) body.add(config[j])
                i += len
            }
            if (body.isEmpty()) return config // no interface 0 found — don't break enumeration
            val total = 9 + body.size
            val out = ByteArray(total)
            System.arraycopy(config, 0, out, 0, 9)
            for (k in body.indices) out[9 + k] = body[k]
            out[2] = (total and 0xFF).toByte()         // wTotalLength lo
            out[3] = ((total shr 8) and 0xFF).toByte() // wTotalLength hi
            out[4] = 1                                  // bNumInterfaces
            return out
        }

        /**
         * True when the CONFIGURATION descriptor's interface 0 is HID (class 0x03) —
         * the only case we strip to interface-0-only (a FIDO key). Walks to the first
         * INTERFACE descriptor with bInterfaceNumber 0 and reads its bInterfaceClass.
         */
        fun interface0IsHid(config: ByteArray): Boolean {
            if (config.size < 9 || (config[1].toInt() and 0xFF) != 0x02) return false
            var i = 9
            while (i + 2 <= config.size) {
                val len = config[i].toInt() and 0xFF
                if (len < 2 || i + len > config.size) break
                if ((config[i + 1].toInt() and 0xFF) == 0x04 && len >= 6 &&
                    (config[i + 2].toInt() and 0xFF) == 0
                ) {
                    return (config[i + 5].toInt() and 0xFF) == 0x03
                }
                i += len
            }
            return false
        }

        /** -EPIPE: the closest generic "transfer failed" errno the vhci client maps. */
        private const val EPIPE = 32
    }
}

/** Transfer backend the URB bridge drives — [BrokerBackend] in prod, a fake in tests. */
interface UsbIpBackend {
    /** Endpoint-0 control transfer; returns IN data (empty for OUT). Throws on failure. */
    fun control(requestType: Int, request: Int, value: Int, index: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray

    /** Bulk/interrupt on the full endpoint address; returns IN data (empty for OUT). Throws on failure. */
    fun bulk(endpointAddress: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray

    /** False once the device handle is gone (unplug / re-enumeration), so standing polls bail. */
    fun isAlive(): Boolean
}

/** [UsbIpBackend] backed by the Android [UsbBroker]. */
class BrokerBackend(private val broker: UsbBroker, private val deviceName: String) : UsbIpBackend {
    override fun control(requestType: Int, request: Int, value: Int, index: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray =
        broker.controlTransfer(deviceName, requestType, request, value, index, out, length, timeoutMs).data

    override fun bulk(endpointAddress: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray =
        broker.bulkTransfer(deviceName, endpointAddress, out, length, timeoutMs).data

    override fun isAlive(): Boolean = broker.isOpen(deviceName)
}
