package sh.haven.core.fido

import android.nfc.tech.IsoDep
import android.util.Log
import java.io.Closeable
import java.io.IOException

private const val TAG = "CtapNfc"

/** FIDO Alliance NFC applet AID: A0000006472F0001 */
private val FIDO_AID = byteArrayOf(
    0xA0.toByte(), 0x00, 0x00, 0x06, 0x47, 0x2F, 0x00, 0x01,
)

/** NFCCTAP command INS byte. */
private const val INS_NFCCTAP_MSG: Byte = 0x10

/** ISO 7816-4 GET RESPONSE INS byte. */
private const val INS_GET_RESPONSE: Byte = 0xC0.toByte()

/** ISO 7816-4 SELECT INS byte. */
private const val INS_SELECT: Byte = 0xA4.toByte()

/** NFC timeout for user presence (30s). */
private const val NFC_TIMEOUT_MS = 30_000

/**
 * CTAP2 over NFC ISO-DEP (ISO 7816-4 APDUs).
 * Implements FIDO NFC protocol: SELECT applet, then NFCCTAP_MSG commands.
 */
class CtapNfcTransport(
    private val isoDep: IsoDep,
) : Closeable {

    fun connect() {
        isoDep.timeout = NFC_TIMEOUT_MS
        isoDep.connect()
    }

    /**
     * Select the FIDO applet. Must be called before sendCborCommand.
     * @throws IOException if the applet is not present on this tag.
     */
    fun select() {
        selectApplet { isoDep.transceive(it) }
        Log.d(TAG, "FIDO applet selected")
    }

    /**
     * Send a CTAP2 CBOR command and return the response payload.
     * Handles response chaining (SW=61xx).
     */
    fun sendCborCommand(data: ByteArray): ByteArray {
        // NFCCTAP_MSG APDU: CLA=80 INS=10 P1=00 P2=00 Lc Data Le=00
        val apdu = if (data.size <= 255) {
            // Short APDU
            byteArrayOf(
                0x80.toByte(), INS_NFCCTAP_MSG, 0x00, 0x00,
                data.size.toByte(),
            ) + data + byteArrayOf(0x00) // Le=00 (max response)
        } else {
            // Extended length APDU
            byteArrayOf(
                0x80.toByte(), INS_NFCCTAP_MSG, 0x00, 0x00,
                0x00, // Extended length marker
                ((data.size shr 8) and 0xFF).toByte(),
                (data.size and 0xFF).toByte(),
            ) + data + byteArrayOf(0x00, 0x00) // Le=0000
        }

        var response = isoDep.transceive(apdu)
        val result = mutableListOf<Byte>()

        while (true) {
            val sw = extractSw(response)
            // Copy data (everything except last 2 SW bytes)
            for (i in 0 until response.size - 2) {
                result.add(response[i])
            }

            when {
                sw == 0x9000 -> break // Success, all data received
                (sw shr 8) == 0x61 -> {
                    // More data available — send GET RESPONSE
                    val remaining = sw and 0xFF
                    val getResponse = byteArrayOf(
                        0x00, INS_GET_RESPONSE, 0x00, 0x00,
                        remaining.toByte(),
                    )
                    response = isoDep.transceive(getResponse)
                }
                else -> throw IOException("NFCCTAP error: SW=${"%04x".format(sw)}")
            }
        }

        return result.toByteArray()
    }

    override fun close() {
        try {
            isoDep.close()
        } catch (_: Exception) {}
    }

    private fun extractSw(response: ByteArray): Int = extractSwStatic(response)

    companion object {
        /**
         * SELECT the FIDO applet over an arbitrary APDU transport, draining
         * ISO 7816-4 "more data available" (SW=61xx) chaining via GET RESPONSE.
         *
         * A SELECT can answer 61xx instead of 9000: the applet returns its FCI
         * template in chunks and expects GET RESPONSE to drain them. Some keys
         * — e.g. Nitrokey 3A — do exactly this (#623); treating 61xx as fatal
         * broke FIDO2-over-NFC key import for them. Only a genuine error status
         * word is fatal; completion (9000) after draining is success.
         *
         * Parameterised on the transceive call so it is unit-testable without
         * an IsoDep (no Robolectric in this module).
         *
         * @throws IOException if the applet is not present (error SW).
         */
        internal fun selectApplet(transceive: (ByteArray) -> ByteArray) {
            // SELECT APDU: CLA=00 INS=A4 P1=04 P2=00 Lc=08 Data=AID
            val apdu = byteArrayOf(
                0x00, INS_SELECT, 0x04, 0x00,
                FIDO_AID.size.toByte(),
            ) + FIDO_AID
            var response = transceive(apdu)
            var sw = extractSwStatic(response)
            // Drain any "more data available" (SW=61xx) chained response data.
            while (sw shr 8 == 0x61) {
                val remaining = sw and 0xFF
                val getResponse = byteArrayOf(
                    0x00, INS_GET_RESPONSE, 0x00, 0x00,
                    remaining.toByte(),
                )
                response = transceive(getResponse)
                sw = extractSwStatic(response)
            }
            if (sw != 0x9000) {
                throw IOException("FIDO applet SELECT failed: SW=${"%04x".format(sw)}")
            }
        }

        internal fun extractSwStatic(response: ByteArray): Int {
            require(response.size >= 2) { "NFC response too short: ${response.size}" }
            return ((response[response.size - 2].toInt() and 0xFF) shl 8) or
                (response[response.size - 1].toInt() and 0xFF)
        }
    }
}
