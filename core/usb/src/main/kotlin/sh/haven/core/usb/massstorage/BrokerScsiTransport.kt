package sh.haven.core.usb.massstorage

import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.TransferResult

/**
 * [ScsiTransport] over a brokered USB device. The broker owns the connection
 * lifecycle (permission, open/close, detach events) and per-call locking;
 * command serialisation lives in [MassStorageDevice]'s engine lock.
 */
class BrokerScsiTransport(
    private val deviceName: String,
    private val broker: UsbBroker,
) : ScsiTransport {
    override fun bulk(endpointAddress: Int, data: ByteArray?, length: Int, timeoutMs: Int): TransferResult =
        broker.bulkTransfer(deviceName, endpointAddress, data, length, timeoutMs)

    override fun isAlive(): Boolean = broker.isOpen(deviceName)
}