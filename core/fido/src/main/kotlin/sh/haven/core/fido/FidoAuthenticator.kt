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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FidoAuthenticator"
private const val ACTION_USB_PERMISSION = "sh.haven.core.fido.USB_PERMISSION"
private const val USB_PERMISSION_TIMEOUT_MS = 30_000L

data class FidoAssertionResult(
    val signature: ByteArray,
    val flags: Byte,
    val counter: Int,
)

/**
 * UI state for an in-flight FIDO2 assertion. The UI should show a modal
 * prompt while [FidoAuthenticator.touchPrompt] is non-null and dismiss it
 * when the flow returns to null.
 */
sealed class FidoTouchPrompt {
    /** Discovery active — waiting for the user to plug in or tap a key. */
    data object WaitingForKey : FidoTouchPrompt()
    /** Key detected; CTAP2 in flight; waiting for the user to physically touch it. */
    data object TouchKey : FidoTouchPrompt()
    /**
     * Key requires PIN (verify-required SK key). UI should show a password
     * field and call [submit] with the entered PIN, or [submit] with null
     * to cancel. [retriesRemaining] is the authenticator-reported count
     * after a previous wrong PIN attempt; null on the first attempt.
     */
    data class EnterPin(
        val submit: (String?) -> Unit,
        val retriesRemaining: Int? = null,
    ) : FidoTouchPrompt()
}

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
 * For verify-required SK keys (`ssh-keygen -O verify-required`), the
 * [getAssertion] caller passes `requireUv = true`, which triggers a
 * full CTAP2 PIN/UV Auth Protocol exchange (see [Ctap2PinProtocol]) before
 * the GetAssertion call. The user is prompted via the
 * [FidoTouchPrompt.EnterPin] state.
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

    private val _touchPrompt = MutableStateFlow<FidoTouchPrompt?>(null)
    /**
     * Current FIDO2 prompt state observable by the UI. Non-null while an
     * assertion is in flight; transitions [FidoTouchPrompt.WaitingForKey]
     * → [FidoTouchPrompt.TouchKey] → null. Observers should render a
     * modal prompt while non-null.
     */
    val touchPrompt: StateFlow<FidoTouchPrompt?> = _touchPrompt.asStateFlow()

    /** Last assertion error message, readable by the ViewModel for user-facing display. */
    @Volatile var lastAssertionError: String? = null

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
     * @param rpId         the relying party ID (for SSH this is "ssh:" or
     *                     a custom string set with `ssh-keygen -O application=…`)
     * @param message      the SSH sign data to hash and sign
     * @param credentialId the credential ID (key handle) from the SK key file
     * @param requireUv    true when the SK key was registered with
     *                     verify-required (`SSH_SK_USER_VERIFICATION_REQUIRED`).
     *                     Triggers the CTAP2 clientPIN exchange before the
     *                     actual GetAssertion call.
     */
    suspend fun getAssertion(
        rpId: String,
        message: ByteArray,
        credentialId: ByteArray,
        requireUv: Boolean = false,
    ): FidoAssertionResult = withContext(Dispatchers.IO) {
        lastAssertionError = null
        Log.d(TAG, "FIDO2 assertion requested: rpId=$rpId, message=${message.size}b, " +
            "credId=${credentialId.size}b, requireUv=$requireUv")

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

        // If a USB key was already plugged in, we already completed the
        // deferred above — skip straight to TouchKey. Otherwise tell the
        // UI we're waiting for the user to plug in / tap.
        _touchPrompt.value = if (deferred.isCompleted) FidoTouchPrompt.TouchKey
        else FidoTouchPrompt.WaitingForKey

        try {
            Log.d(TAG, "Waiting for security key (USB${if (nfcEnabled) " or NFC" else ""})...")
            val device = deferred.await()

            // Device just landed — for the non-UV path, switch the prompt to
            // "touch your key now" before sending GetAssertion. For the UV
            // path, performXxxAssertion will toggle EnterPin then TouchKey
            // at the right moments.
            if (!requireUv) {
                _touchPrompt.value = FidoTouchPrompt.TouchKey
            }

            val result = when (device) {
                is ConnectedDevice.Usb -> performUsbAssertion(
                    device.device, rpId, clientDataHash, credentialId, requireUv,
                )
                is ConnectedDevice.Nfc -> performNfcAssertion(
                    device.tag, rpId, clientDataHash, credentialId, requireUv,
                )
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
            _touchPrompt.value = null
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
        requireUv: Boolean,
    ): FidoAssertionResult {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // Request USB permission if needed
        if (!usbManager.hasPermission(device)) {
            val deviceName = device.productName ?: "(unknown product)"
            Log.d(TAG, "Requesting USB permission for $deviceName")
            val permDeferred = CompletableDeferred<Boolean>()
            val permReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == ACTION_USB_PERMISSION) {
                        val g = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Log.d(TAG, "USB permission callback: granted=$g")
                        permDeferred.complete(g)
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
            // Android 14+ (targetSdk 34+) rejects FLAG_MUTABLE PendingIntents
            // built around an implicit Intent. UsbManager fills the granted-flag
            // extra back into the intent so it must stay MUTABLE — make the
            // intent explicit by package instead. See #15 (olmari, Pixel 9 Fold).
            val permIntent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            usbManager.requestPermission(
                device,
                PendingIntent.getBroadcast(context, 0, permIntent, flags),
            )
            val granted = try {
                withTimeoutOrNull(USB_PERMISSION_TIMEOUT_MS) { permDeferred.await() }
            } finally {
                try { context.unregisterReceiver(permReceiver) } catch (_: IllegalArgumentException) {}
            }
            if (granted == null) {
                Log.e(TAG, "USB permission timed out for $deviceName")
                throw IOException("USB permission timed out — no response from system dialog")
            }
            if (!granted) {
                Log.e(TAG, "USB permission denied for $deviceName")
                throw IOException("USB permission denied")
            }
            Log.d(TAG, "USB permission granted for $deviceName")
        } else {
            Log.d(TAG, "USB permission already held for ${device.productName}")
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

        try {
            CtapHidTransport(connection, endpointIn, endpointOut).use { transport ->
                Log.d(TAG, "CTAPHID init...")
                transport.init()

                val (pinUvAuthParam, pinProtocol) = if (requireUv) {
                    runUvPinProtocol(rpId, clientDataHash) { transport.sendCborCommand(it) }
                } else null to null

                Log.d(TAG, "CTAPHID init complete, sending GetAssertion (rpId=$rpId, uv=${pinUvAuthParam != null})")

                _touchPrompt.value = FidoTouchPrompt.TouchKey
                val command = Ctap2Cbor.encodeGetAssertionCommand(
                    rpId = rpId,
                    clientDataHash = clientDataHash,
                    credentialId = credentialId,
                    pinUvAuthParam = pinUvAuthParam,
                    pinUvAuthProtocol = pinProtocol,
                )
                val response = transport.sendCborCommand(command) {
                    _touchPrompt.value = FidoTouchPrompt.TouchKey
                }

                Log.d(TAG, "CTAP response: ${response.size} bytes, status=0x${
                    if (response.isNotEmpty()) "%02x".format(response[0]) else "empty"
                }")
                return parseCtap2AssertionResponse(response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB FIDO assertion failed: ${e.javaClass.simpleName}: ${e.message}")
            lastAssertionError = e.message
            throw e
        }
    }

    private suspend fun performNfcAssertion(
        tag: Tag,
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
        requireUv: Boolean,
    ): FidoAssertionResult {
        val isoDep = IsoDep.get(tag) ?: throw IOException("Tag does not support ISO-DEP")

        CtapNfcTransport(isoDep).use { transport ->
            transport.connect()
            transport.select()

            val (pinUvAuthParam, pinProtocol) = if (requireUv) {
                runUvPinProtocol(rpId, clientDataHash) { transport.sendCborCommand(it) }
            } else null to null

            _touchPrompt.value = FidoTouchPrompt.TouchKey
            val command = Ctap2Cbor.encodeGetAssertionCommand(
                rpId = rpId,
                clientDataHash = clientDataHash,
                credentialId = credentialId,
                pinUvAuthParam = pinUvAuthParam,
                pinUvAuthProtocol = pinProtocol,
            )
            val response = transport.sendCborCommand(command)
            return parseCtap2AssertionResponse(response)
        }
    }

    /**
     * Run the CTAP2 clientPIN protocol against an authenticator over [send]
     * (one round-trip per CBOR command, status byte preserved). Returns
     * `(pinUvAuthParam, protocolVersion)` to be included in the subsequent
     * GetAssertion. Throws [IOException] if the key has no PIN configured,
     * the user cancels, or PIN entry exhausts the authenticator's retry
     * counter.
     */
    private suspend fun runUvPinProtocol(
        rpId: String,
        clientDataHash: ByteArray,
        send: (ByteArray) -> ByteArray,
    ): Pair<ByteArray, Int> {
        // 1. authenticatorGetInfo to learn supported protocols and PIN state
        val infoResp = send(Ctap2Cbor.encodeGetInfoCommand())
        ensureOk(infoResp, "GetInfo")
        val info = Ctap2Cbor.decodeGetInfoResponse(infoResp.copyOfRange(1, infoResp.size))
        Log.d(TAG, "GetInfo: pinProtocols=${info.pinUvAuthProtocols}, " +
            "clientPinSet=${info.clientPinSet}, uvBuiltIn=${info.uvBuiltIn}")

        if (!info.clientPinSet) {
            throw IOException(
                "This SK key requires verification, but the security key has no " +
                "PIN configured. Set a PIN with `ykman fido access change-pin` " +
                "(YubiKey) or your manufacturer's tool, then try again."
            )
        }

        val protocol = Ctap2PinProtocol.pick(info.pinUvAuthProtocols)
            ?: throw IOException("Authenticator does not support PIN protocol v1 or v2")
        Log.d(TAG, "Using PIN/UV auth protocol v${protocol.version}")

        // 2. clientPIN getKeyAgreement → authenticator's COSE_Key
        val kaResp = send(Ctap2Cbor.encodeClientPinGetKeyAgreement(protocol.version))
        ensureOk(kaResp, "clientPIN getKeyAgreement")
        val cose = Ctap2Cbor.decodeClientPinKeyAgreementResponse(kaResp.copyOfRange(1, kaResp.size))
        val authenticatorPub = protocol.coseKeyToEcPublic(cose.x, cose.y)

        // 3. ECDH on platform side → shared secret
        val ephemeral = protocol.generateEphemeralKeyPair()
        val z = protocol.ecdh(ephemeral.private as ECPrivateKey, authenticatorPub)
        val sharedSecret = protocol.deriveSharedSecret(z)
        val (ephX, ephY) = protocol.ecPublicToCoseCoords(ephemeral.public as ECPublicKey)
        val platformKa = Ctap2Cbor.CoseEcdhPubKey(ephX, ephY)

        // 4. Loop: prompt PIN → getPinUvAuthToken. On wrong PIN, retry until
        //    the user cancels or the authenticator returns a hard failure.
        var retriesNote: Int? = null
        while (true) {
            val pin = promptPin(retriesNote)
                ?: throw IOException("PIN entry cancelled")
            if (pin.length < 4) throw IOException("PIN must be at least 4 characters")

            val pinHash = MessageDigest.getInstance("SHA-256")
                .digest(pin.toByteArray(Charsets.UTF_8))
                .copyOfRange(0, 16)
            val pinHashEnc = protocol.encrypt(sharedSecret, pinHash)

            val tokReq = Ctap2Cbor.encodeClientPinGetTokenWithPermissions(
                protocol = protocol.version,
                platformKeyAgreement = platformKa,
                pinHashEnc = pinHashEnc,
                permissions = Ctap2Cbor.PERMISSION_GET_ASSERTION,
                rpId = rpId,
            )
            val tokResp = send(tokReq)
            when (val status = tokResp[0]) {
                Ctap2Cbor.STATUS_OK -> {
                    val encToken = Ctap2Cbor.decodeClientPinTokenResponse(
                        tokResp.copyOfRange(1, tokResp.size)
                    )
                    val token = protocol.decrypt(sharedSecret, encToken)
                    val authParam = protocol.authenticate(token, clientDataHash)
                    Log.d(TAG, "PIN verified; pinUvAuthParam=${authParam.size}b")
                    return authParam to protocol.version
                }
                Ctap2Cbor.STATUS_PIN_INVALID -> {
                    retriesNote = (retriesNote ?: 8) - 1
                    Log.w(TAG, "Wrong PIN; ~$retriesNote attempts remain (estimated)")
                    if (retriesNote <= 0) {
                        throw IOException("Too many wrong PIN attempts.")
                    }
                    // Loop and prompt again.
                }
                Ctap2Cbor.STATUS_PIN_BLOCKED -> throw IOException(
                    "Security key PIN is blocked. Reset the FIDO2 application " +
                        "with the manufacturer's tool (e.g. `ykman fido reset`) " +
                        "and re-enroll."
                )
                Ctap2Cbor.STATUS_PIN_AUTH_BLOCKED -> throw IOException(
                    "PIN auth temporarily blocked. Unplug and replug the key, " +
                        "then try again."
                )
                Ctap2Cbor.STATUS_PIN_NOT_SET -> throw IOException(
                    "Security key has no PIN set; configure one and try again."
                )
                else -> throw IOException(
                    "PIN exchange failed: CTAP2 error 0x${"%02x".format(status)}"
                )
            }
        }
        // unreachable — the while(true) only exits via return or throw
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * Show the PIN entry dialog and await the user's response. Returns the
     * entered PIN, or null when the user cancels.
     */
    private suspend fun promptPin(retriesRemaining: Int?): String? {
        val deferred = CompletableDeferred<String?>()
        _touchPrompt.value = FidoTouchPrompt.EnterPin(
            submit = { pin -> if (!deferred.isCompleted) deferred.complete(pin) },
            retriesRemaining = retriesRemaining,
        )
        return try {
            deferred.await()
        } finally {
            // Caller flips _touchPrompt to TouchKey or null next; clearing here
            // would briefly hide the dialog. Leaving the EnterPin state in place
            // is fine since the next line of the caller reassigns it.
        }
    }

    /** Throw a descriptive IOException if [response] does not lead with STATUS_OK. */
    private fun ensureOk(response: ByteArray, context: String) {
        if (response.isEmpty()) throw IOException("$context: empty CTAP2 response")
        val status = response[0]
        if (status != Ctap2Cbor.STATUS_OK) {
            throw IOException("$context: CTAP2 error 0x${"%02x".format(status)}")
        }
    }

    private fun parseCtap2AssertionResponse(response: ByteArray): FidoAssertionResult {
        require(response.isNotEmpty()) { "Empty CTAP2 response" }

        val status = response[0]
        if (status != Ctap2Cbor.STATUS_OK) {
            val desc = when (status) {
                Ctap2Cbor.STATUS_NO_CREDENTIALS ->
                    "No matching credential on this key (or the credential requires " +
                        "PIN verification — re-check that the PIN was accepted)"
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
