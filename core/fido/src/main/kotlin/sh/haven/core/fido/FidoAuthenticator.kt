package sh.haven.core.fido

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FidoAuthenticator"
private const val ACTION_USB_PERMISSION = "sh.haven.core.fido.USB_PERMISSION"

data class FidoAssertionResult(
    val signature: ByteArray,
    val flags: Byte,
    val counter: Int,
)

/**
 * Manages FIDO2 authenticator interactions using the generic CTAP2 protocol.
 * Works with any FIDO2 security key over USB HID or NFC ISO-DEP —
 * YubiKey, Nitrokey, SoloKeys, Feitian, Trezor, Google Titan, etc.
 *
 * Discovery is self-driven: calling [getAssertion] transparently enumerates
 * already-plugged USB devices, registers a receiver for USB attach events,
 * and — if a host [Activity] has been published via [setActiveActivity] —
 * enables NFC reader mode for the duration of the assertion. All of that
 * is torn down when the assertion completes or fails.
 *
 * This replaces the pre-fix design where `startUsbDiscovery` and
 * `startNfcDiscovery` were public entry points that the rest of the app
 * never actually called — meaning security keys were never detected and
 * the SSH auth path hung forever on `pendingDevice.await()`. See #15.
 */
@Singleton
class FidoAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Sealed type for connected security key transport. */
    private sealed class ConnectedDevice {
        data class Usb(val device: UsbDevice) : ConnectedDevice()
        data class Nfc(val tag: Tag) : ConnectedDevice()
    }

    private var pendingDevice: CompletableDeferred<ConnectedDevice>? = null

    /**
     * Weak reference to the currently-resumed foreground [Activity], used
     * as the host for NFC reader mode during an in-flight assertion. Set
     * by the activity's `onResume` / cleared on `onPause`. NFC reader
     * mode is not available outside of a foreground activity context —
     * USB discovery still works without one.
     */
    private var activeActivity: WeakReference<Activity>? = null

    /** Callback for UI to show/hide "touch your security key" prompt. */
    var onTouchRequired: ((Boolean) -> Unit)? = null

    /**
     * Publish the foreground activity from `Activity.onResume`. Required to
     * enable NFC reader mode during [getAssertion]; USB-only flows work
     * without it.
     */
    fun setActiveActivity(activity: Activity) {
        activeActivity = WeakReference(activity)
    }

    /**
     * Clear the foreground activity reference from `Activity.onPause`. Only
     * clears when [activity] matches the currently-published one — guards
     * against races where a new activity resumes before the old pauses.
     */
    fun clearActiveActivity(activity: Activity) {
        if (activeActivity?.get() === activity) {
            activeActivity = null
        }
    }

    /**
     * Perform a FIDO2 assertion. Blocks until a security key is connected
     * (USB) or tapped (NFC) and the user touches it to authorise signing.
     *
     * Discovery is started inline and cleaned up in the finally block —
     * callers do not need to pre-arm anything. USB keys already plugged in
     * at call time are detected immediately; otherwise a broadcast receiver
     * catches the attach event.
     *
     * @param rpId         the relying party ID (for SSH this is "ssh:")
     * @param message      the SSH sign data to hash and sign
     * @param credentialId the credential ID (key handle) from the SK key file
     */
    suspend fun getAssertion(
        rpId: String,
        message: ByteArray,
        credentialId: ByteArray,
    ): FidoAssertionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "FIDO2 assertion requested: rpId=$rpId, message=${message.size}b, credId=${credentialId.size}b")

        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(message)

        val deferred = CompletableDeferred<ConnectedDevice>()
        pendingDevice = deferred

        // ----- USB: check already connected, else register attach receiver
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val alreadyConnectedUsb = usbManager.deviceList.values.firstOrNull { isFidoHidDevice(it) }
        var usbReceiver: BroadcastReceiver? = null
        if (alreadyConnectedUsb != null) {
            Log.d(TAG, "FIDO key already plugged in: ${alreadyConnectedUsb.productName ?: "(unknown product)"}")
            deferred.complete(ConnectedDevice.Usb(alreadyConnectedUsb))
        } else {
            usbReceiver = registerUsbAttachReceiver(deferred)
            Log.d(TAG, "Registered USB attach receiver; waiting for key to be plugged in")
        }

        // ----- NFC: if an activity is in the foreground, enable reader mode
        val nfcActivity = activeActivity?.get()
        var nfcEnabled = false
        if (nfcActivity != null && !deferred.isCompleted) {
            nfcEnabled = startNfcReaderModeOnMain(nfcActivity, deferred)
            Log.d(TAG, "NFC reader mode ${if (nfcEnabled) "enabled" else "unavailable"} for current activity")
        } else if (nfcActivity == null) {
            Log.d(TAG, "No foreground activity — NFC path disabled, USB only")
        }

        onTouchRequired?.invoke(true)

        try {
            Log.d(TAG, "Waiting for security key (USB${if (nfcEnabled) " or NFC" else ""})...")
            val device = deferred.await()

            val result = when (device) {
                is ConnectedDevice.Usb -> performUsbAssertion(device.device, rpId, clientDataHash, credentialId)
                is ConnectedDevice.Nfc -> performNfcAssertion(device.tag, rpId, clientDataHash, credentialId)
            }

            Log.d(TAG, "FIDO2 assertion success: sig=${result.signature.size}b, flags=0x${
                "%02x".format(result.flags)
            }, counter=${result.counter}")

            result
        } finally {
            pendingDevice = null
            usbReceiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (_: IllegalArgumentException) {
                    // already unregistered
                }
            }
            if (nfcEnabled && nfcActivity != null) {
                stopNfcReaderModeOnMain(nfcActivity)
            }
            onTouchRequired?.invoke(false)
        }
    }

    /**
     * Register a broadcast receiver for USB attach events that completes
     * [deferred] when a FIDO HID device is plugged in. Returns the receiver
     * for later unregistration.
     */
    private fun registerUsbAttachReceiver(
        deferred: CompletableDeferred<ConnectedDevice>,
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device != null && isFidoHidDevice(device)) {
                    Log.d(TAG, "USB FIDO device attached: ${device.productName ?: "(unknown product)"}")
                    deferred.complete(ConnectedDevice.Usb(device))
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return receiver
    }

    /**
     * Enable NFC reader mode on [activity] to catch a FIDO tap and complete
     * [deferred]. NFC APIs must be called on the main thread, hence the
     * handler hop. Returns true if reader mode was successfully enabled,
     * false if the device lacks NFC or the call failed.
     */
    private suspend fun startNfcReaderModeOnMain(
        activity: Activity,
        deferred: CompletableDeferred<ConnectedDevice>,
    ): Boolean {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context) ?: run {
            Log.d(TAG, "NFC not available on this device")
            return false
        }
        return withContext(Dispatchers.Main) {
            try {
                nfcAdapter.enableReaderMode(
                    activity,
                    { tag ->
                        if (IsoDep.get(tag) != null) {
                            Log.d(TAG, "NFC FIDO tag detected")
                            deferred.complete(ConnectedDevice.Nfc(tag))
                        }
                    },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable NFC reader mode: ${e.message}")
                false
            }
        }
    }

    /**
     * Disable NFC reader mode on [activity]. Must be called on the main thread.
     * Swallows exceptions — NFC teardown errors should not mask the assertion
     * result.
     */
    private suspend fun stopNfcReaderModeOnMain(activity: Activity) {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context) ?: return
        withContext(Dispatchers.Main) {
            try {
                nfcAdapter.disableReaderMode(activity)
            } catch (_: Exception) {
                // best effort
            }
        }
    }

    private suspend fun performUsbAssertion(
        device: UsbDevice,
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
    ): FidoAssertionResult {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // Request USB permission if needed
        if (!usbManager.hasPermission(device)) {
            Log.d(TAG, "Requesting USB permission for ${device.productName ?: "(unknown product)"}")
            val permDeferred = CompletableDeferred<Boolean>()
            val permReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == ACTION_USB_PERMISSION) {
                        permDeferred.complete(
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        )
                    }
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(permReceiver, filter)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(
                device,
                PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags),
            )
            val granted = permDeferred.await()
            try {
                context.unregisterReceiver(permReceiver)
            } catch (_: IllegalArgumentException) {}
            if (!granted) throw IOException("USB permission denied")
        }

        // Find HID interface and endpoints
        val hidInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: throw IOException("No HID interface on FIDO device")

        var endpointIn: android.hardware.usb.UsbEndpoint? = null
        var endpointOut: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until hidInterface.endpointCount) {
            val ep = hidInterface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT) endpointOut = ep
        }
        requireNotNull(endpointIn) { "No IN endpoint on HID interface" }
        requireNotNull(endpointOut) { "No OUT endpoint on HID interface" }

        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open USB device")
        connection.claimInterface(hidInterface, true)

        CtapHidTransport(connection, endpointIn, endpointOut).use { transport ->
            transport.init()

            val command = Ctap2Cbor.encodeGetAssertionCommand(rpId, clientDataHash, credentialId)
            val response = transport.sendCborCommand(command) {
                onTouchRequired?.invoke(true)
            }

            return parseCtap2Response(response)
        }
    }

    private fun performNfcAssertion(
        tag: Tag,
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
    ): FidoAssertionResult {
        val isoDep = IsoDep.get(tag) ?: throw IOException("Tag does not support ISO-DEP")

        CtapNfcTransport(isoDep).use { transport ->
            transport.connect()
            transport.select()

            val command = Ctap2Cbor.encodeGetAssertionCommand(rpId, clientDataHash, credentialId)
            val response = transport.sendCborCommand(command)

            return parseCtap2Response(response)
        }
    }

    private fun parseCtap2Response(response: ByteArray): FidoAssertionResult {
        require(response.isNotEmpty()) { "Empty CTAP2 response" }

        val status = response[0]
        if (status != Ctap2Cbor.STATUS_OK) {
            val desc = when (status) {
                Ctap2Cbor.STATUS_NO_CREDENTIALS -> "No matching credential on this key"
                Ctap2Cbor.STATUS_ACTION_TIMEOUT -> "User did not touch the key in time"
                else -> "CTAP2 error 0x${"%02x".format(status)}"
            }
            throw IOException("FIDO2 assertion failed: $desc")
        }

        val parsed = Ctap2Cbor.decodeGetAssertionResponse(response.copyOfRange(1, response.size))

        val authData = parsed.authData
        require(authData.size >= 37) { "authenticatorData too short: ${authData.size}" }
        val flags = authData[32]
        val counter = ((authData[33].toInt() and 0xFF) shl 24) or
            ((authData[34].toInt() and 0xFF) shl 16) or
            ((authData[35].toInt() and 0xFF) shl 8) or
            (authData[36].toInt() and 0xFF)

        return FidoAssertionResult(
            signature = parsed.signature,
            flags = flags,
            counter = counter,
        )
    }

    /** Check if a USB device exposes a HID interface (FIDO keys always do). */
    private fun isFidoHidDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) {
                return true
            }
        }
        return false
    }
}
