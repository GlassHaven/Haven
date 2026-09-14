package sh.haven.core.usb.massstorage

/**
 * The transport surface the BBB engine needs. Mirrors the `UsbIpBackend` seam
 * so tests run against a fake and [MassStorageDevice] stays free of Android
 * USB imports. Endpoint addresses are the engine's business (discovered from
 * the device descriptor); the transport only moves bytes.
 */
interface ScsiTransport {
    /**
     * One bulk transfer. [data] is the OUT payload for OUT endpoints; for IN
     * endpoints [length] sizes the read buffer. Returns the bytes transferred
     * (IN data in [sh.haven.core.usb.TransferResult.data]).
     */
    fun bulk(endpointAddress: Int, data: ByteArray?, length: Int, timeoutMs: Int): sh.haven.core.usb.TransferResult

    /** False once the underlying handle is gone (unplug) — the session must unwind. */
    fun isAlive(): Boolean
}