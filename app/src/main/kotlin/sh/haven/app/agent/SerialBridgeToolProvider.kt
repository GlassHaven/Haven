package sh.haven.app.agent

import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.bleserial.BleSerialSessionManager
import sh.haven.core.btserial.BtSerialSessionManager
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.usbserial.UsbSerialSessionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * serial↔TCP bridge tools: expose a live serial terminal session (BT-SPP,
 * BLE-UART, or USB-CDC) as a loopback TCP port, so a serial device joins the
 * rest of Haven's fabric — add_port_forward (remote-forward) or a tunnel can
 * then carry it off-phone. The session keeps its terminal tab; the bridge is a
 * read-through tap plus a write-back path, raw bytes both ways.
 *
 * The three managers are nullable so the many manual [McpTools] test
 * constructions compile without wiring a serial backend; the tools error
 * cleanly when a session can't be resolved.
 */
internal class SerialBridgeToolProvider(
    private val btSerial: BtSerialSessionManager?,
    private val bleSerial: BleSerialSessionManager?,
    private val usbSerial: UsbSerialSessionManager?,
) : ToolProvider {

    // sessionId -> live bridge. One bridge per session; start is idempotent.
    private val bridges = ConcurrentHashMap<String, SerialTcpBridge>()

    override fun tools(): Map<String, ToolHandler> = toolMapOf(
        "bridge_serial_to_tcp" to ToolHandler(
            description = "Expose a live serial terminal session (BTSERIAL / BLESERIAL / USBSERIAL — sessionId from list_sessions) as a raw-byte TCP server on 127.0.0.1, and return the bound port. Feed that port to add_port_forward (a remote-forward) or a tunnel to reach the device off-phone — this is how a serial device joins Haven's routing fabric. The terminal tab keeps working; the bridge taps the same stream. Bytes are raw with no framing: every connected client sees the device output and any client can write back to it. Pass an explicit `port`, or omit for an OS-assigned free port. Idempotent — a second call for the same session returns the existing port. Open the device's terminal first (connect_profile / a serial tab); a session with no open terminal has nothing to bridge.",
            inputSchema = objectSchema {
                string("sessionId", "The live serial session to expose (from list_sessions).", required = true)
                integer("port", "Loopback TCP port to bind (1024–65535). Omit for an OS-assigned free port.")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { "Expose serial session ${it.optString("sessionId").take(8)}… on a local TCP port?" },
        ) { args -> startBridge(args) },

        "stop_serial_bridge" to ToolHandler(
            description = "Tear down a serial↔TCP bridge started by bridge_serial_to_tcp, closing its listen port and every connected client. The underlying serial session and its terminal tab are unaffected.",
            inputSchema = objectSchema {
                string("sessionId", "The bridged session id (from list_serial_bridges).", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { "Stop the TCP bridge for serial session ${it.optString("sessionId").take(8)}…?" },
        ) { args -> stopBridge(args) },

        "list_serial_bridges" to ToolHandler(
            description = "List active serial↔TCP bridges: sessionId, bound loopback host/port, and connected-client count. Read-only. Bridges whose serial session has died (device unplugged / out of range) are pruned here.",
            inputSchema = objectSchema(),
        ) { _ -> listBridges() },
    )

    /** Adapt whichever concrete serial session owns [sessionId] to a [SerialEndpoint]. */
    private fun resolve(sessionId: String): SerialEndpoint? {
        btSerial?.sessions?.value?.get(sessionId)?.session?.let { s ->
            return object : SerialEndpoint {
                override fun write(bytes: ByteArray) = s.sendInput(bytes)
                override fun setTap(tap: ((ByteArray, Int, Int) -> Unit)?) = s.setTap(tap)
            }
        }
        bleSerial?.sessions?.value?.get(sessionId)?.session?.let { s ->
            return object : SerialEndpoint {
                override fun write(bytes: ByteArray) = s.sendInput(bytes)
                override fun setTap(tap: ((ByteArray, Int, Int) -> Unit)?) = s.setTap(tap)
            }
        }
        usbSerial?.sessions?.value?.get(sessionId)?.session?.let { s ->
            return object : SerialEndpoint {
                override fun write(bytes: ByteArray) = s.sendInput(bytes)
                override fun setTap(tap: ((ByteArray, Int, Int) -> Unit)?) = s.setTap(tap)
            }
        }
        return null
    }

    private fun startBridge(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId").ifBlank {
            throw IllegalArgumentException("sessionId required")
        }
        bridges[sessionId]?.let { return describe(sessionId, it, alreadyRunning = true) }
        val endpoint = resolve(sessionId)
            ?: throw IllegalStateException("No live serial session: $sessionId (open its terminal first)")
        val bridge = SerialTcpBridge(endpoint, args.optInt("port", 0)).also { it.start() }
        bridges[sessionId] = bridge
        return describe(sessionId, bridge, alreadyRunning = false)
    }

    private fun stopBridge(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId").ifBlank {
            throw IllegalArgumentException("sessionId required")
        }
        val bridge = bridges.remove(sessionId)
            ?: return JSONObject().apply { put("stopped", false); put("reason", "no bridge for $sessionId") }
        bridge.close()
        return JSONObject().apply { put("stopped", true); put("sessionId", sessionId) }
    }

    private fun listBridges(): JSONObject {
        val arr = JSONArray()
        for ((sessionId, bridge) in bridges) {
            // Prune a bridge whose session vanished — its tap stopped firing, so
            // the listener is dead weight; close it and free the port.
            if (resolve(sessionId) == null) {
                bridges.remove(sessionId)
                bridge.close()
                continue
            }
            arr.put(JSONObject().apply {
                put("sessionId", sessionId)
                put("host", "127.0.0.1")
                put("port", bridge.port)
                put("clients", bridge.clientCount)
            })
        }
        return JSONObject().apply {
            put("count", arr.length())
            put("bridges", arr)
        }
    }

    private fun describe(sessionId: String, bridge: SerialTcpBridge, alreadyRunning: Boolean) =
        JSONObject().apply {
            put("sessionId", sessionId)
            put("host", "127.0.0.1")
            put("port", bridge.port)
            if (alreadyRunning) put("alreadyRunning", true)
        }
}
