package sh.haven.core.fido

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FidoAuthenticator"
private const val ACTION_USB_PERMISSION = "sh.haven.core.fido.USB_PERMISSION"
private const val USB_PERMISSION_TIMEOUT_MS = 30_000L

/** Heads-up notification so an auth wait isn't mistaken for a hang (#286 follow-up). */
private const val AUTH_NOTIF_CHANNEL = "fido_auth_block"
private const val AUTH_NOTIF_ID = 0x46_44 // "FD"
/**
 * Hold a non-null prompt this long before notifying — an already-plugged key
 * that signs instantly would otherwise flash a notification for a few ms.
 */
private const val AUTH_NOTIF_DEBOUNCE_MS = 400L

/**
 * How many times a single [FidoAuthenticator.getAssertion] will re-prompt after
 * a wrong key (NO_CREDENTIALS) before giving up, counting the first try (#237).
 */
private const val MAX_MISMATCH_ATTEMPTS = 3

/**
 * How long to hold the "wrong key — present <name>" prompt before re-arming on
 * the non-PIN auto-retry path (#237 a/b → option (a)). Without it a still-present
 * wrong tap burns through the attempts in ~0.5s flashes that can't be read.
 */
private const val WRONG_KEY_HOLD_MS = 1500L

/**
 * How long a key wait (USB plug-in / NFC tap) may run before the assertion
 * fails with [FidoKeyNotPresentedException] (#732). Before this the wait was
 * unbounded: with agent forwarding on, a forwarded SIGN_REQUEST for a key the
 * user isn't carrying blocked the whole SSH session until the *server's* own
 * deadline, which reads as a hang. A finite, generous window gives the user
 * time to present the key on the interactive path, and lets the forwarded
 * path fail the single request cleanly (AgentSafeIdentity turns it into
 * SSH_AGENT_FAILURE) instead of stalling the connection.
 */
private const val KEY_PRESENTATION_TIMEOUT_MS = 60_000L

data class FidoAssertionResult(
    val signature: ByteArray,
    val flags: Byte,
    val counter: Int,
    /** Which credential the key signed with — set when the request offered
     *  several (the either/or flow); null for single-credential assertions. */
    val usedCredentialId: ByteArray? = null,
)

/**
 * UI state for an in-flight FIDO2 assertion. The UI should show a modal
 * prompt while [FidoAuthenticator.touchPrompt] is non-null and dismiss it
 * when the flow returns to null.
 */
sealed class FidoTouchPrompt {
    /**
     * Human label of the specific key this assertion targets (the profile's
     * key name, e.g. "YK-carry-A"), or null when discovering / unknown. Lets
     * the UI tell the user *which* of several listed keys to present, so a
     * wrong-key tap is avoidable rather than fatal (#237).
     */
    abstract val keyLabel: String?

    /** Discovery active — waiting for the user to plug in or tap a key. */
    data class WaitingForKey(override val keyLabel: String? = null) : FidoTouchPrompt()

    /**
     * Key detected; CTAP2 in flight; waiting for the user to physically
     * activate it. [transport] dictates the UI copy: USB users press the
     * key's button, NFC users hold the key against the phone for the
     * full exchange. The latter case is the load-bearing one — pulling
     * the key away too early raises `TagLostException` mid-CTAP and the
     * SSH auth path then disconnects with an opaque "Auth fail" error,
     * which is exactly the failure mode reported in `#15` for
     * SoloKey-via-NFC users.
     */
    data class TouchKey(
        val transport: Transport,
        override val keyLabel: String? = null,
    ) : FidoTouchPrompt() {
        enum class Transport { USB, NFC }
    }

    /**
     * The key just presented didn't hold the credential this assertion needs
     * (CTAP2 no-credentials). We're now waiting for the user to present the
     * *correct* key ([keyLabel]); [attemptsLeft] retries remain before the
     * assertion gives up. The wrong key is excluded from re-selection so the
     * user isn't auto-failed on the same one (#237).
     */
    data class WrongKey(
        override val keyLabel: String? = null,
        val attemptsLeft: Int = 0,
    ) : FidoTouchPrompt()

    /**
     * Key requires PIN (verify-required SK key). UI should show a password
     * field and call [submit] with the entered PIN, or [submit] with null
     * to cancel. [retriesRemaining] is the authenticator-reported count
     * after a previous wrong PIN attempt; null on the first attempt.
     *
     * When [settingNew] is true this is a *registration* prompt: the key has
     * no PIN configured and the user is choosing one (the UI should show a
     * "create a PIN" + confirm flow, not "enter your PIN").
     */
    data class EnterPin(
        val submit: (String?) -> Unit,
        val retriesRemaining: Int? = null,
        override val keyLabel: String? = null,
        val settingNew: Boolean = false,
    ) : FidoTouchPrompt()
}

/**
 * The presented FIDO key doesn't hold the credential the server asked to sign
 * (CTAP2 `STATUS_NO_CREDENTIALS`). Distinct from a generic [java.io.IOException]
 * so the assertion loop can re-prompt for the correct key instead of aborting
 * the whole SSH publickey method (#237).
 */
class FidoNoMatchingCredentialException(message: String) : java.io.IOException(message)

/**
 * Raised when the user cancels a pending FIDO key wait/touch from the dialog
 * (Cancel on the WaitingForKey / WrongKey / TouchKey prompt). Lets the awaiting
 * CTAP flow unwind cleanly instead of blocking until timeout.
 */
class FidoCancelledException(message: String = "Security key prompt cancelled") :
    java.io.IOException(message)

/**
 * No FIDO key was presented within [KEY_PRESENTATION_TIMEOUT_MS], or a
 * non-interactive (agent-forwarded) sign request arrived while no key was
 * already connected (#732). Distinct from a generic [java.io.IOException] so
 * the assertion loop never swallows it into the wrong-key retry, and so
 * AgentSafeIdentity can turn it into a clean SSH_AGENT_FAILURE for the single
 * forwarded request instead of stalling the SSH session.
 */
class FidoKeyNotPresentedException(message: String) : java.io.IOException(message)

/**
 * Map a CTAP2 GetAssertion response status byte to the exception it should
 * raise, or null when [status] is STATUS_OK. A wrong key answers
 * STATUS_NO_CREDENTIALS, which becomes a typed [FidoNoMatchingCredentialException]
 * so the assertion loop can re-prompt for the correct key instead of aborting
 * the SSH publickey method (#237); every other non-OK status is a plain
 * [java.io.IOException]. Top-level + internal so it's unit-testable without a
 * Context-bound [FidoAuthenticator].
 */
internal fun ctap2AssertionErrorForStatus(status: Byte): Exception? = when (status) {
    Ctap2Cbor.STATUS_OK -> null
    Ctap2Cbor.STATUS_NO_CREDENTIALS -> FidoNoMatchingCredentialException(
        "FIDO2 assertion failed: this security key has no matching credential — " +
            "wrong key for this profile, try another",
    )
    Ctap2Cbor.STATUS_ACTION_TIMEOUT ->
        java.io.IOException("FIDO2 assertion failed: User did not touch the key in time")
    else ->
        java.io.IOException("FIDO2 assertion failed: CTAP2 error 0x${"%02x".format(status)}")
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
    // Pauses the USB/IP export's transfers on the key while we authenticate on
    // our own connection, so their CTAPHID frames don't interleave (#FIDO-storm).
    private val usbAccessGate: sh.haven.core.usb.UsbAccessGate,
) {
    /** Sealed type for connected security key transport. */
    private sealed class ConnectedDevice {
        data class Usb(val device: UsbDevice) : ConnectedDevice()
        data class Nfc(val tag: Tag) : ConnectedDevice()
    }

    private var pendingDevice: CompletableDeferred<ConnectedDevice>? = null

    /**
     * Test seam for [KEY_PRESENTATION_TIMEOUT_MS]. Production code never
     * touches it; unit tests shorten the key-presentation wait so the
     * timeout path runs in milliseconds instead of a real minute (the wait
     * lives inside withContext(Dispatchers.IO), where runTest's virtual
     * clock does not apply).
     */
    @Volatile internal var keyPresentationTimeoutMs = KEY_PRESENTATION_TIMEOUT_MS

    /**
     * The transport of the in-flight assertion (USB CTAPHID or NFC ISO-DEP),
     * set while a GetAssertion is waiting for the user's touch. [pendingDevice]
     * only covers the pre-discovery wait; closing this transport is what lets
     * Cancel abort the **touch-wait** too — e.g. when an already-plugged key was
     * auto-selected and the sign prompt is sitting there waiting for a touch
     * (#237). Closing it makes the blocking transceive error out so the flow
     * unwinds.
     */
    private val activeTransport = java.util.concurrent.atomic.AtomicReference<AutoCloseable?>(null)

    /**
     * One-tap NFC either/or (#237): an NFC ISO-DEP transport left OPEN after
     * [detectPresentSkKey] identifies the tapped key, so the follow-on sign in
     * [getAssertion] reuses the same field instead of asking for a second tap.
     * Best-effort — survives only while the tag stays on the phone; if it's
     * lifted (or the link is slow enough that the RF link drops) the sign falls
     * back to a fresh tap. Always released by [releaseHeldNfc] after the connect
     * attempt so a half-held field can't leak past one connect.
     */
    private var heldNfcTransport: CtapNfcTransport? = null

    /** Activity whose NFC reader mode is kept on for a [heldNfcTransport]; stopped by [releaseHeldNfc]. */
    private var heldNfcActivity: Activity? = null

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

    // A FIDO touch wait is invisible when the app is backgrounded or on a
    // screen that doesn't host the prompt dialog (e.g. Desktop/Terminal), so
    // the connect reads as a hang. Mirror touchPrompt to a heads-up
    // notification — for USB/NFC the action is physical (touch the key), so the
    // notification alone is actionable. collectLatest debounces instant signs.
    private val notifScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        notifScope.launch {
            touchPrompt.collectLatest { prompt ->
                if (prompt == null) {
                    cancelAuthNotification()
                } else {
                    delay(AUTH_NOTIF_DEBOUNCE_MS) // collectLatest cancels this if the prompt changes/clears first
                    postAuthNotification(prompt)
                }
            }
        }
    }

    /** Last assertion error message, readable by the ViewModel for user-facing display. */
    @Volatile var lastAssertionError: String? = null

    /**
     * Label of the key the in-flight assertion targets (#237). Set at the top
     * of [getAssertion] and read by the touch-prompt construction sites so the
     * UI can name *which* key to present, without threading it through every
     * deep CTAP helper signature. Null when discovering (resident-cred enum).
     */
    @Volatile private var currentKeyLabel: String? = null

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

    /** Post (or refresh) the heads-up notification describing what the key wait needs. */
    private fun postAuthNotification(prompt: FidoTouchPrompt) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (!nm.areNotificationsEnabled()) return // best-effort; the dialog still covers foreground

        if (nm.getNotificationChannel(AUTH_NOTIF_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    AUTH_NOTIF_CHANNEL,
                    "Security key prompts",
                    NotificationManager.IMPORTANCE_HIGH, // heads-up so it surfaces over any screen
                ).apply { description = "Shown while a connection is waiting for your security key" },
            )
        }

        val key = prompt.keyLabel?.takeIf { it.isNotBlank() }
        val text = when (prompt) {
            is FidoTouchPrompt.WaitingForKey -> "Insert or tap your security key" + (key?.let { " ($it)" } ?: "")
            is FidoTouchPrompt.TouchKey -> when (prompt.transport) {
                FidoTouchPrompt.TouchKey.Transport.NFC -> "Hold your security key to the phone" + (key?.let { " ($it)" } ?: "")
                FidoTouchPrompt.TouchKey.Transport.USB -> "Touch your security key" + (key?.let { " ($it)" } ?: "")
            }
            is FidoTouchPrompt.WrongKey -> "Wrong key — present ${key ?: "the correct key"}"
            is FidoTouchPrompt.EnterPin -> "Open Haven to enter your security-key PIN"
        }

        // Tap brings Haven forward (REORDER_TO_FRONT preserves the existing task
        // / dynamic theme rather than relaunching). For USB/NFC the touch itself
        // is physical, so the notification text alone is enough to act on.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK) }
        val pending = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notif = Notification.Builder(context, AUTH_NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Security key required")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_CALL) // time-sensitive: the connect is blocked on it
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // don't buzz again on WaitingForKey → TouchKey transitions
            .apply { pending?.let { setContentIntent(it) } }
            .build()
        nm.notify(AUTH_NOTIF_ID, notif)
    }

    private fun cancelAuthNotification() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(AUTH_NOTIF_ID)
    }

    /**
     * Cancel an in-flight assertion / discovery / registration that is waiting
     * for a key to be presented (the WaitingForKey / WrongKey / TouchKey
     * dialog). Completes the pending discovery exceptionally so the awaiting
     * CTAP flow unwinds — its `finally` clears the touch prompt and tears down
     * USB/NFC discovery. A no-op if nothing is pending. The PIN-entry dialog
     * has its own cancel (submit(null)).
     */
    fun cancelPending() {
        pendingDevice?.completeExceptionally(FidoCancelledException())
        // Also tear down an in-flight assertion's transport so a touch-wait
        // (e.g. an auto-selected plugged key) actually cancels.
        activeTransport.getAndSet(null)?.let { runCatching { it.close() } }
        releaseHeldNfc()
    }

    /**
     * Close any NFC transport held open across the detect→sign hand-off (the
     * one-tap either/or path, see [heldNfcTransport]). Idempotent; called by
     * [getAssertion] once it has consumed the held field and by the SSH connect
     * path in a finally so a held field never outlives one connect attempt.
     */
    fun releaseHeldNfc() {
        heldNfcTransport?.let {
            activeTransport.compareAndSet(it, null)
            runCatching { it.close() }
        }
        heldNfcTransport = null
        // Reader mode was kept on to hold the field alive; stop it now. Posts to
        // the main looper since NFC APIs are main-thread-only and this is called
        // from non-suspend contexts (cancel, the SSH connect finally).
        val activity = heldNfcActivity ?: return
        heldNfcActivity = null
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context) ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { nfcAdapter.disableReaderMode(activity) }
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
        keyLabel: String? = null,
        /** Agent-forwarded sign request (#732): fail up front when the key is
         *  provably absent instead of waiting for a user who isn't driving this
         *  connect. See [withDiscoveredFidoDevice]. */
        nonInteractive: Boolean = false,
    ): FidoAssertionResult = withContext(Dispatchers.IO) {
        lastAssertionError = null
        currentKeyLabel = keyLabel
        Log.d(TAG, "FIDO2 assertion requested: rpId=$rpId, message=${message.size}b, " +
            "credId=${credentialId.size}b, requireUv=$requireUv, keyLabel=${keyLabel ?: "(none)"}")

        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(message)

        // For a verify-required key, collect the PIN UP FRONT — before the key
        // is tapped — so the whole assertion runs as one continuous tap. Over
        // NFC you can't hold the key on the phone's back AND type the PIN, so a
        // mid-exchange PIN prompt drops the tag ("Tag is out of date"); USB is
        // unaffected (the PIN dialog just appears before "touch your key"). A
        // wrong PIN then fails the attempt cleanly instead of looping
        // mid-exchange. Mirrors the registration (makeCredential) flow.
        val prePin: String? = if (requireUv) {
            promptPin(null) ?: throw IOException("PIN entry cancelled")
        } else {
            null
        }

        // One-tap NFC either/or (#237): if detection left an NFC field open on
        // the key the user already tapped, sign on that same transport so they
        // don't tap twice. Best-effort — if the tag has lifted the transceive
        // errors out and we fall through to the normal fresh-tap flow below.
        heldNfcTransport?.let { held ->
            try {
                activeTransport.set(held)
                val result = runGetAssertionExchange(
                    rpId, clientDataHash, listOf(credentialId), requireUv, prePin,
                    FidoTouchPrompt.TouchKey.Transport.NFC,
                ) { cmd -> held.sendCborCommand(cmd) }
                Log.d(TAG, "FIDO2 assertion success (one-tap held NFC): sig=${result.signature.size}b")
                return@withContext result
            } catch (e: Exception) {
                Log.w(TAG, "Held-NFC one-tap sign failed (${e.javaClass.simpleName}: ${e.message}); " +
                    "falling back to a fresh tap")
            } finally {
                releaseHeldNfc() // close the held transport + stop the kept-on reader mode
                _touchPrompt.value = null
            }
        }

        // A key that doesn't hold this credential answers NO_CREDENTIALS. Rather
        // than let that abort the whole SSH publickey method (the user tapped the
        // wrong one of several listed keys, #237), re-prompt for the correct key
        // and retry — excluding the already-failed USB key so the user isn't
        // auto-failed on the same one, and re-arming NFC for a fresh tap. Bounded
        // so a key that simply never matches still terminates.
        val failedUsbIds = mutableSetOf<Int>()
        try {
            var attempt = 0
            while (true) {
                attempt++
                var thisUsbId: Int? = null
                try {
                    return@withContext withDiscoveredFidoDevice(
                        excludeUsbDeviceIds = failedUsbIds,
                        wrongKeyAttemptsLeft = if (attempt > 1) MAX_MISMATCH_ATTEMPTS - attempt + 1 else null,
                        nonInteractive = nonInteractive,
                    ) { device ->
                        thisUsbId = (device as? ConnectedDevice.Usb)?.device?.deviceId
                        // Device just landed — for the non-UV path, switch the
                        // prompt to "touch your key now", naming the target key
                        // so the right one is presented. The UV path goes through
                        // `performXxxAssertion` which toggles EnterPin/TouchKey.
                        if (!requireUv) {
                            _touchPrompt.value = FidoTouchPrompt.TouchKey(
                                when (device) {
                                    is ConnectedDevice.Usb -> FidoTouchPrompt.TouchKey.Transport.USB
                                    is ConnectedDevice.Nfc -> FidoTouchPrompt.TouchKey.Transport.NFC
                                },
                                currentKeyLabel,
                            )
                        }

                        val result = when (device) {
                            is ConnectedDevice.Usb -> performUsbAssertion(
                                device.device, rpId, clientDataHash, listOf(credentialId), requireUv, prePin,
                            )
                            is ConnectedDevice.Nfc -> performNfcAssertion(
                                device.tag, rpId, clientDataHash, listOf(credentialId), requireUv, prePin,
                            )
                        }

                        Log.d(TAG, "FIDO2 assertion success: sig=${result.signature.size}b, flags=0x${
                            "%02x".format(result.flags)
                        }, counter=${result.counter}")

                        result
                    }
                } catch (e: FidoNoMatchingCredentialException) {
                    thisUsbId?.let { failedUsbIds += it }
                    // Verify-required (PIN) keys: CTAP2 runs the PIN/UV exchange
                    // BEFORE the assertion can check the credential, so an
                    // auto-retry on a wrong key is "PIN → wrong → PIN → …". Don't
                    // loop — surface one clear, labelled error and stop (#237 a/b →
                    // option (b) for PIN keys). Non-PIN keys keep the tap-again
                    // auto-retry (option (a)) below.
                    if (requireUv) {
                        val name = currentKeyLabel?.let { "\"$it\"" } ?: "the configured key"
                        val msg = "That security key isn't $name — reconnect and present $name."
                        lastAssertionError = msg
                        Log.w(TAG, "Verify-required wrong key — not auto-retrying to avoid a PIN loop")
                        throw FidoNoMatchingCredentialException(msg)
                    }
                    if (attempt >= MAX_MISMATCH_ATTEMPTS) {
                        lastAssertionError = e.message
                        Log.w(TAG, "No matching credential after $attempt attempt(s); giving up")
                        throw e
                    }
                    Log.w(TAG, "Wrong key (attempt $attempt/$MAX_MISMATCH_ATTEMPTS) — " +
                        "re-prompting for ${currentKeyLabel ?: "the correct key"}")
                    // (a) Hold the "present <name>" message long enough to read
                    // before re-arming; the previous discovery's finally cleared
                    // the prompt, so re-show WrongKey for the pause.
                    _touchPrompt.value = FidoTouchPrompt.WrongKey(currentKeyLabel, MAX_MISMATCH_ATTEMPTS - attempt)
                    delay(WRONG_KEY_HOLD_MS)
                    // Loop: withDiscoveredFidoDevice re-arms discovery excluding
                    // the failed USB key and shows the WrongKey prompt.
                } catch (e: FidoCancelledException) {
                    // User cancelled the key wait — never swallow into the
                    // fall-through below; let the connect abort.
                    throw e
                } catch (e: FidoKeyNotPresentedException) {
                    // The key wait timed out (#732). Retrying would just wait
                    // another full window for a key that isn't there — and on
                    // the agent-forwarded path the caller (AgentSafeIdentity)
                    // wants this single request to fail cleanly, not loop.
                    lastAssertionError = e.message
                    throw e
                } catch (e: IOException) {
                    // A USB device was *selected* but couldn't be used as a key:
                    // it's HID-with-interrupt-IN+OUT but not actually CTAPHID,
                    // CTAPHID init failed, USB permission was denied, or the
                    // device errored. Rather than abort the whole assertion,
                    // exclude this USB device and re-arm — which enables NFC
                    // (no usable USB FIDO device remains) so the user can tap
                    // their NFC key. This is the "fall through even for a FIDO-
                    // looking device when no key is recognised on it" path, and
                    // the safety net behind the isFidoHidDevice filter: a non-key
                    // USB device that slips the filter degrades to "try the next
                    // transport" instead of a hard fail. NFC errors propagate
                    // (thisUsbId is null for an NFC device); only fall through
                    // when there's an NFC-capable foreground activity to fall
                    // through TO, and stay bounded by the attempt cap.
                    val usbId = thisUsbId
                    val canFallThrough = usbId != null &&
                        activeActivity?.get() != null &&
                        attempt < MAX_MISMATCH_ATTEMPTS
                    if (!canFallThrough) {
                        lastAssertionError = e.message
                        throw e
                    }
                    failedUsbIds += usbId!!
                    Log.w(TAG, "USB device $usbId unusable as a FIDO key " +
                        "(${e.javaClass.simpleName}: ${e.message}) — excluding and " +
                        "re-arming so NFC / another key can be presented")
                    _touchPrompt.value = FidoTouchPrompt.WaitingForKey(currentKeyLabel)
                    // Loop: re-arm excluding the failed USB device → NFC is armed.
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            currentKeyLabel = null
        }
    }

    /**
     * Register a NEW SSH-SK credential on a connected security key via CTAP2
     * authenticatorMakeCredential, and return it as an [SkKeyData] ready for
     * the SSH key store (same shape as a file-imported or discovered key).
     *
     * Creates a *resident* (discoverable) Ed25519 credential under the
     * [application] RP id (the SSH "ssh:" convention). Resident creation
     * requires the key to have a FIDO2 PIN; if it has none, the user is
     * prompted to set one first ([FidoTouchPrompt.EnterPin] with
     * `settingNew = true`). When [verifyRequired] the stored flags include
     * user-verification so the SSH signing path later prompts for the PIN.
     *
     * Blocks until a key is plugged in (USB) or tapped (NFC), the user sets/
     * enters the PIN, and touches the key to authorise the registration.
     */
    suspend fun makeCredential(
        application: String = "ssh:",
        userName: String,
        userDisplayName: String = userName,
        verifyRequired: Boolean,
        pin: String,
        keyLabel: String? = null,
    ): SkKeyData = withContext(Dispatchers.IO) {
        lastAssertionError = null
        currentKeyLabel = keyLabel
        // The account name is deliberately absent: these logs get attached to
        // bug reports, and a reporter on #477 deleted three of his after
        // noticing his account name in them. `application` is the relying
        // party, which is what identifies the request.
        Log.d(TAG, "FIDO2 makeCredential requested: app=$application, " +
            "verifyRequired=$verifyRequired")
        try {
            withDiscoveredFidoDevice { device ->
                when (device) {
                    is ConnectedDevice.Usb -> withUsbCtapTransport(device.device) { transport ->
                        runMakeCredentialExchange(
                            application, userName, userDisplayName, verifyRequired, pin,
                            FidoTouchPrompt.TouchKey.Transport.USB,
                        ) { cmd ->
                            transport.sendCborCommand(cmd) {
                                _touchPrompt.value = FidoTouchPrompt.TouchKey(
                                    FidoTouchPrompt.TouchKey.Transport.USB, currentKeyLabel,
                                )
                            }
                        }
                    }
                    is ConnectedDevice.Nfc -> {
                        val isoDep = IsoDep.get(device.tag)
                            ?: throw IOException("Tag does not support ISO-DEP")
                        CtapNfcTransport(isoDep).use { transport ->
                            transport.connect()
                            transport.select()
                            runMakeCredentialExchange(
                                application, userName, userDisplayName, verifyRequired, pin,
                                FidoTouchPrompt.TouchKey.Transport.NFC,
                            ) { cmd -> transport.sendCborCommand(cmd) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "makeCredential failed: ${e.javaClass.simpleName}: ${e.message}")
            // NFC tag lost (TagLostException, or the framework's "Tag is out of
            // date" SecurityException) — the key left the field before the
            // exchange finished. With the PIN supplied up front the whole
            // exchange is one continuous burst, so this now just means "held
            // too briefly"; give a clear hold-it-still message.
            val nfcLost = e is android.nfc.TagLostException ||
                (e.message?.contains("out of date", ignoreCase = true) == true)
            if (nfcLost) {
                val msg = "NFC tap was lost before registration finished — hold the key flat " +
                    "on the phone's back until it completes (it takes a moment), or plug it in " +
                    "over USB."
                lastAssertionError = msg
                throw IOException(msg, e)
            }
            if (lastAssertionError == null) lastAssertionError = e.message
            throw e
        } finally {
            currentKeyLabel = null
        }
    }

    /**
     * Shared MakeCredential exchange (USB + NFC). Ensures a PIN exists
     * (setting one if needed), obtains a makeCredential-permission token,
     * sends authenticatorMakeCredential for a resident Ed25519 credential,
     * and parses the attested credential into an [SkKeyData].
     */
    private suspend fun runMakeCredentialExchange(
        application: String,
        userName: String,
        userDisplayName: String,
        verifyRequired: Boolean,
        pin: String,
        touchTransport: FidoTouchPrompt.TouchKey.Transport,
        send: (ByteArray) -> ByteArray,
    ): SkKeyData {
        // 1. GetInfo — PIN state + supported protocols.
        val infoResp = send(Ctap2Cbor.encodeGetInfoCommand())
        ensureOk(infoResp, "GetInfo")
        val info = Ctap2Cbor.decodeGetInfoResponse(infoResp.copyOfRange(1, infoResp.size))
        val protocol = Ctap2PinProtocol.pick(info.pinUvAuthProtocols)
            ?: throw IOException("Authenticator does not support PIN protocol v1 or v2")
        Log.d(TAG, "MakeCredential GetInfo: clientPinSet=${info.clientPinSet}, " +
            "protocols=${info.pinUvAuthProtocols}")

        // 2. A resident credential requires a PIN. Use the PIN the user supplied
        //    up front (set it if the key has none) — NO mid-exchange prompt, so
        //    the whole exchange runs as one continuous tap (NFC-safe).
        if (!info.clientPinSet) {
            Log.d(TAG, "Key has no PIN — setting the supplied one")
            setNewPin(protocol, send, pin)
        }

        // 3. makeCredential-permission token, scoped to this rpId, using the
        //    supplied PIN. A wrong PIN fails cleanly (no prompt loop).
        val (token, _) = runUvPinProtocol(
            rpId = application,
            permission = Ctap2Cbor.PERMISSION_MAKE_CREDENTIAL,
            send = send,
            knownPin = pin,
            allowPrompt = false,
        )

        // 4. Build + send MakeCredential. There is no server in the SSH
        //    registration flow, so the clientDataHash is a fresh random
        //    challenge; SSH only consumes the resulting handle + public key.
        val rng = SecureRandom()
        val clientDataHash = ByteArray(32).also { rng.nextBytes(it) }
        val userId = ByteArray(32).also { rng.nextBytes(it) }
        val pinUvAuthParam = protocol.authenticate(token, clientDataHash)

        _touchPrompt.value = FidoTouchPrompt.TouchKey(touchTransport, currentKeyLabel)
        Log.d(TAG, "Sending MakeCredential (rpId=$application, resident, uv)")
        val resp = send(
            Ctap2Cbor.encodeMakeCredentialCommand(
                clientDataHash = clientDataHash,
                rpId = application,
                rpName = application,
                userId = userId,
                userName = userName,
                userDisplayName = userDisplayName,
                algorithms = listOf(Ctap2Cbor.COSE_ALG_EDDSA),
                residentKey = true,
                pinUvAuthParam = pinUvAuthParam,
                pinUvAuthProtocol = protocol.version,
            )
        )
        if (resp.isEmpty()) throw IOException("MakeCredential: empty CTAP response")
        val status = resp[0]
        if (status != Ctap2Cbor.STATUS_OK) {
            throw IOException("MakeCredential failed: CTAP2 error 0x${"%02x".format(status)}")
        }
        val mc = Ctap2Cbor.decodeMakeCredentialResponse(resp.copyOfRange(1, resp.size))
        val attested = Ctap2Cbor.parseAttestedCredentialData(mc.authData)
        Log.d(TAG, "MakeCredential OK: credId=${attested.credentialId.size}b, " +
            "pubKey=${attested.publicKey.javaClass.simpleName}")

        // 5. Synthesise the SSH-SK key (same builder as the discover path).
        val flags: Byte = if (verifyRequired) {
            (Ctap2Cbor.AUTHDATA_FLAG_USER_PRESENT or Ctap2Cbor.AUTHDATA_FLAG_USER_VERIFIED).toByte()
        } else {
            Ctap2Cbor.AUTHDATA_FLAG_USER_PRESENT.toByte()
        }
        val credEntry = Ctap2Cbor.CredentialEntry(
            credentialId = attested.credentialId,
            publicKey = attested.publicKey,
            userId = userId,
            userName = userName,
            userDisplayName = userDisplayName,
        )
        return SkKeyParser.buildFromCtapCredential(credEntry, rpId = application, flags = flags)
    }

    /**
     * Configure the first FIDO2 PIN on a key that has none (CTAP2 clientPIN
     * setPIN) using the caller-supplied [newPin], derives the ECDH shared
     * secret, and submits the encrypted, zero-padded PIN. The PIN is collected
     * up front (in the registration dialog), not prompted mid-exchange, so the
     * whole CTAP exchange runs as one continuous tap — required for NFC.
     */
    private suspend fun setNewPin(
        protocol: Ctap2PinProtocol,
        send: (ByteArray) -> ByteArray,
        newPin: String,
    ) {
        val pinBytes = newPin.toByteArray(Charsets.UTF_8)
        if (pinBytes.size < 4 || pinBytes.size > 63) {
            throw IOException("PIN must be 4–63 characters")
        }
        // keyAgreement → ECDH shared secret (the setPIN auth key).
        val kaResp = send(Ctap2Cbor.encodeClientPinGetKeyAgreement(protocol.version))
        ensureOk(kaResp, "clientPIN getKeyAgreement")
        val cose = Ctap2Cbor.decodeClientPinKeyAgreementResponse(kaResp.copyOfRange(1, kaResp.size))
        val authenticatorPub = protocol.coseKeyToEcPublic(cose.x, cose.y)
        val ephemeral = protocol.generateEphemeralKeyPair()
        val z = protocol.ecdh(ephemeral.private as ECPrivateKey, authenticatorPub)
        val sharedSecret = protocol.deriveSharedSecret(z)
        val (ephX, ephY) = protocol.ecPublicToCoseCoords(ephemeral.public as ECPublicKey)
        val platformKa = Ctap2Cbor.CoseEcdhPubKey(ephX, ephY)

        // CTAP2: newPin is UTF-8, zero-padded to a minimum of 64 bytes.
        val padded = ByteArray(64)
        pinBytes.copyInto(padded)
        val newPinEnc = protocol.encrypt(sharedSecret, padded)
        val pinUvAuthParam = protocol.authenticate(sharedSecret, newPinEnc)

        val resp = send(
            Ctap2Cbor.encodeClientPinSetPin(
                protocol = protocol.version,
                platformKeyAgreement = platformKa,
                pinUvAuthParam = pinUvAuthParam,
                newPinEnc = newPinEnc,
            )
        )
        if (resp.isEmpty()) throw IOException("setPIN: empty CTAP response")
        when (val status = resp[0]) {
            Ctap2Cbor.STATUS_OK -> Log.d(TAG, "New FIDO2 PIN configured on key")
            Ctap2Cbor.STATUS_PIN_POLICY_VIOLATION -> throw IOException(
                "That PIN doesn't meet the key's policy (too short or too simple). " +
                    "Choose a longer PIN and try again."
            )
            else -> throw IOException("Failed to set PIN: CTAP2 error 0x${"%02x".format(status)}")
        }
    }

    /**
     * Open a CTAPHID transport over [device] (USB permission + HID interface
     * claim + CTAPHID init), run [block], and close the transport after. Used
     * by [makeCredential]; the assertion/enumerate paths inline the same steps
     * (a shared helper there is a follow-up).
     */
    private suspend fun <R> withUsbCtapTransport(
        device: UsbDevice,
        block: suspend (CtapHidTransport) -> R,
    ): R {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        ensureUsbPermission(usbManager, device)
        val (hidInterface, endpointIn, endpointOut) = findCtapHidInterface(device)
        usbAccessGate.acquire(device.deviceName)
        try {
            val connection = usbManager.openDevice(device)
                ?: throw IOException("Failed to open USB device")
            connection.claimInterface(hidInterface, true)
            return CtapHidTransport(connection, endpointIn, endpointOut).use { transport ->
                transport.init()
                block(transport)
            }
        } finally {
            usbAccessGate.release(device.deviceName)
        }
    }

    /**
     * Run [perform] once a FIDO key has been discovered via USB or NFC.
     * Handles the shared discovery / touch-prompt / cleanup lifecycle.
     */
    private suspend fun <R> withDiscoveredFidoDevice(
        excludeUsbDeviceIds: Set<Int> = emptySet(),
        wrongKeyAttemptsLeft: Int? = null,
        nonInteractive: Boolean = false,
        perform: suspend (ConnectedDevice) -> R,
    ): R {
        // ----- USB: check already connected, else register attach receiver.
        // Skip a key that already failed with NO_CREDENTIALS this call so a
        // wrong, still-plugged key isn't re-selected instantly — wait for the
        // user to present a different key instead (#237).
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val alreadyConnectedUsb = usbManager.deviceList.values.firstOrNull {
            isFidoHidDevice(it) && it.deviceId !in excludeUsbDeviceIds
        }

        // ----- NFC: if an activity is in the foreground, enable reader mode
        val nfcActivity = activeActivity?.get()

        // #732 fail-fast: an agent-forwarded sign request (nobody is driving
        // this connect interactively) for a key that is provably absent — not
        // plugged in, and no foreground activity that could answer an NFC tap
        // — must not wait at all. Fail the single request before arming anything
        // (no pendingDevice, no receiver, no NFC reader); AgentSafeIdentity turns
        // it into SSH_AGENT_FAILURE and the SSH session continues with the next
        // key instead of stalling until the server's deadline. When an activity
        // IS foreground the user may be holding the phone with the key on its
        // back, so the bounded wait below still gives NFC a chance.
        if (nonInteractive && alreadyConnectedUsb == null && nfcActivity == null) {
            val name = currentKeyLabel?.let { " \"$it\"" } ?: ""
            throw FidoKeyNotPresentedException(
                "No security key$name is connected and none can be tapped right now " +
                    "(the sign request came from a remote host through agent forwarding). " +
                    "Connect the key, or remove it from this connection's forwarded keys.",
            )
        }

        val deferred = CompletableDeferred<ConnectedDevice>()
        pendingDevice = deferred

        var usbReceiver: BroadcastReceiver? = null
        if (alreadyConnectedUsb != null) {
            Log.d(TAG, "FIDO key already plugged in: ${alreadyConnectedUsb.productName ?: "(unknown product)"}")
            deferred.complete(ConnectedDevice.Usb(alreadyConnectedUsb))
        } else {
            usbReceiver = registerUsbAttachReceiver(deferred, excludeUsbDeviceIds)
            Log.d(TAG, "Registered USB attach receiver; waiting for key to be plugged in")
        }
        var nfcEnabled = false
        if (nfcActivity != null && !deferred.isCompleted) {
            nfcEnabled = startNfcReaderModeOnMain(nfcActivity, deferred)
            Log.d(TAG, "NFC reader mode ${if (nfcEnabled) "enabled" else "unavailable"} for current activity")
        } else if (nfcActivity == null) {
            Log.d(TAG, "No foreground activity — NFC path disabled, USB only")
        }

        _touchPrompt.value = when {
            deferred.isCompleted ->
                FidoTouchPrompt.TouchKey(FidoTouchPrompt.TouchKey.Transport.USB, currentKeyLabel)
            wrongKeyAttemptsLeft != null ->
                FidoTouchPrompt.WrongKey(currentKeyLabel, wrongKeyAttemptsLeft)
            else ->
                FidoTouchPrompt.WaitingForKey(currentKeyLabel)
        }

        try {
            Log.d(TAG, "Waiting for security key (USB${if (nfcEnabled) " or NFC" else ""})...")
            val device = withTimeoutOrNull(keyPresentationTimeoutMs) { deferred.await() }
                ?: run {
                    val name = currentKeyLabel?.let { " \"$it\"" } ?: ""
                    throw FidoKeyNotPresentedException(
                        "No security key$name was presented within " +
                            "${keyPresentationTimeoutMs / 1000}s — connect the key, or remove it " +
                            "from this connection's forwarded keys.",
                    )
                }
            return perform(device)
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
                if (heldNfcTransport != null) {
                    // One-tap either/or: detection stashed an open ISO-DEP for the
                    // follow-on sign. Disabling reader mode powers the field down
                    // and the tag is lost, so KEEP it on — releaseHeldNfc() (after
                    // the sign, or in the connect-finally / on cancel) stops it.
                    heldNfcActivity = nfcActivity
                } else {
                    stopNfcReaderModeOnMain(nfcActivity)
                }
            }
            _touchPrompt.value = null
        }
    }

    /**
     * Enumerate resident SSH-SK credentials on an inserted/tapped security
     * key via CTAP 2.1 `authenticatorCredentialManagement`. Lets the user
     * import keys that were registered with `ssh-keygen -O resident`
     * without first having to run `ssh-keygen -K` to extract local stubs
     * (issue #152).
     *
     * Filters returned credentials to RPs whose `id` starts with
     * [rpIdPrefix] (default "ssh", which matches the canonical "ssh:"
     * application name `ssh-keygen` uses for SK keys). Pass a different
     * prefix when discovering WebAuthn-style credentials, or empty string
     * to return everything.
     *
     * Always uses PIN/UV — the credentialManagement command requires a
     * cm-permission token, and the authenticator gates token issuance on
     * PIN entry. A key without a PIN configured raises [IOException]
     * pointing the user at `ykman fido access change-pin`.
     *
     * Returns a list of (rpId, [SkKeyData]) pairs. Each SkKeyData is
     * synthesised from the COSE_Key in the CTAP response — algorithm
     * detected from kty/alg, public key blob built in OpenSSH wire
     * format, application set to the RP id, flags defaulted to 0x01
     * (user-presence). The caller persists the chosen ones into the
     * SSH key store via the same path as file-imported SK keys.
     */
    /**
     * One resident credential found on the security key.
     *
     * @param userName the credential's stored user name — the label the key
     *   was given when it was created. Null when the authenticator returns
     *   none, in which case callers fall back to a generated label.
     */
    data class ResidentCredential(
        val rpId: String,
        val userName: String?,
        val key: SkKeyData,
    )

    suspend fun enumerateResidentCredentials(
        rpIdPrefix: String = "ssh",
    ): List<ResidentCredential> = withContext(Dispatchers.IO) {
        lastAssertionError = null
        Log.d(TAG, "FIDO2 enumerateResidentCredentials requested: rpIdPrefix='$rpIdPrefix'")

        // Collect the PIN UP FRONT — before the key is tapped — so the whole
        // enumeration runs as one continuous tap, the same as getAssertion and
        // makeCredential already do (#449). Credential management always needs a
        // pinUvAuthToken, so this is never a wasted prompt on a key that can
        // enumerate at all; a key with no PIN set fails with the explicit
        // "no PIN configured" error either way.
        //
        // Over NFC a mid-exchange prompt is fatal, not just awkward: you cannot
        // hold the key against the phone and type a PIN at the same time, so the
        // tag lifts while the dialog is up and the next transceive dies with
        // "Tag ... is out of date" — which is exactly how this surfaced.
        val prePin = promptPin(null) ?: throw IOException("PIN entry cancelled")

        withDiscoveredFidoDevice { device ->
            when (device) {
                is ConnectedDevice.Usb -> performUsbEnumerate(device.device, rpIdPrefix, prePin)
                is ConnectedDevice.Nfc -> performNfcEnumerate(device.tag, rpIdPrefix, prePin)
            }
        }
    }

    /**
     * Register a broadcast receiver for USB attach events that completes
     * [deferred] when a FIDO HID device is plugged in. Returns the receiver
     * for later unregistration.
     */
    private fun registerUsbAttachReceiver(
        deferred: CompletableDeferred<ConnectedDevice>,
        excludeUsbDeviceIds: Set<Int> = emptySet(),
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
                if (device != null && isFidoHidDevice(device) && device.deviceId !in excludeUsbDeviceIds) {
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

    /**
     * Request USB permission for [device] if not already held, waiting for
     * the system-dialog response. Used by both the assertion and the
     * credentialManagement enumerate paths so they share the same FLAG_MUTABLE
     * + explicit-package fix from #15 (olmari, Pixel 9 Fold).
     */
    // The two-arg registerReceiver call is inside a SDK_INT < TIRAMISU guard
    // (the flags overload requires API 33), but lint can't see through the
    // version branch and reports UnspecifiedRegisterReceiverFlag anyway —
    // failing every PR's lint job since the check went error-severity.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private suspend fun ensureUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "USB permission already held for ${device.productName}")
            return
        }
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
    }

    /**
     * Either/or auto-select (#237 follow-up): present ANY of the [candidates]
     * security keys and return the one detected, so a profile listing several
     * SK keys accepts whichever the user has to hand. Whatever is plugged in (or
     * tapped) is auto-selected — one touch when a key is plugged — and the user
     * uses **Cancel** to bail out (e.g. to unplug a USB key and tap NFC instead;
     * Cancel works through the sign touch-wait, see [cancelPending]/[activeTransport]).
     *
     * Probes with a presence-free (`up = false`) GetAssertion whose allowList
     * holds every candidate's credential — the key answers with the credential
     * it owns, and the response's credential field tells us which. Only
     * **presence-only** candidates are probed; verify-required ones need a PIN
     * to even assert, so they're left to the order-based fallback. Credentials
     * are grouped by rpId (one GetAssertion can only carry one rpId).
     *
     * Returns the matched [SkKeyData], null if nothing was detected (caller
     * falls back to offering all keys in order), or throws
     * [FidoCancelledException] if the user cancels the key wait.
     */
    suspend fun detectPresentSkKey(
        candidates: List<SkKeyData>,
        keyLabel: String? = null,
    ): SkKeyData? = withContext(Dispatchers.IO) {
        val probeable = candidates.filter { (it.flags.toInt() and 0x04) == 0 }
        if (probeable.size < 2) return@withContext null
        currentKeyLabel = keyLabel
        val byRpId = probeable.groupBy { it.application }
        val dummyHash = ByteArray(32)
        Log.d(TAG, "Either/or detect: ${probeable.size} candidate key(s), ${byRpId.size} rpId group(s)")
        try {
            withDiscoveredFidoDevice { device ->
                when (device) {
                    is ConnectedDevice.Usb -> detectMatchUsb(device.device, byRpId, dummyHash)
                    is ConnectedDevice.Nfc -> detectMatchNfcHold(device.tag, byRpId, dummyHash)
                }
            }
        } catch (e: FidoCancelledException) {
            throw e // user cancelled the key wait — let the connect abort
        } catch (e: Exception) {
            Log.w(TAG, "Either/or key detection failed (${e.javaClass.simpleName}: ${e.message}); falling back to ordered keys")
            null
        } finally {
            _touchPrompt.value = null
        }
    }

    /** Probe each rpId group on a plugged USB key; return the matched candidate. */
    private suspend fun detectMatchUsb(
        device: UsbDevice,
        byRpId: Map<String, List<SkKeyData>>,
        dummyHash: ByteArray,
    ): SkKeyData? {
        for ((rpId, group) in byRpId) {
            val creds = group.map { it.credentialId }
            val result = try {
                performUsbAssertion(device, rpId, dummyHash, creds, requireUv = false, prePin = null, up = false)
            } catch (_: FidoNoMatchingCredentialException) {
                continue // the present key holds none of this rpId group — try the next
            }
            matchDetectedCredential(group, result, creds)?.let { return it }
        }
        return null
    }

    /**
     * Probe each rpId group on the tapped NFC key over ONE ISO-DEP session and,
     * on a match, leave that transport OPEN ([heldNfcTransport]) so the follow-on
     * sign reuses the same tap — one continuous tap instead of detect-tap +
     * sign-tap (#237 one-tap either/or). A generous transceive timeout covers the
     * SSH PK_OK round-trip between detect and sign.
     */
    private suspend fun detectMatchNfcHold(
        tag: Tag,
        byRpId: Map<String, List<SkKeyData>>,
        dummyHash: ByteArray,
    ): SkKeyData? {
        val isoDep = IsoDep.get(tag) ?: throw IOException("Tag does not support ISO-DEP")
        isoDep.timeout = 5000
        val transport = CtapNfcTransport(isoDep)
        transport.connect()
        transport.select()
        activeTransport.set(transport)
        var keep = false
        try {
            for ((rpId, group) in byRpId) {
                val creds = group.map { it.credentialId }
                val result = try {
                    runGetAssertionExchange(
                        rpId, dummyHash, creds, requireUv = false, prePin = null,
                        FidoTouchPrompt.TouchKey.Transport.NFC, up = false,
                    ) { cmd -> transport.sendCborCommand(cmd) }
                } catch (_: FidoNoMatchingCredentialException) {
                    continue
                }
                matchDetectedCredential(group, result, creds)?.let {
                    heldNfcTransport = transport // keep the field open for the sign
                    keep = true
                    return it
                }
            }
            return null
        } finally {
            if (!keep) {
                activeTransport.compareAndSet(transport, null)
                runCatching { transport.close() }
            }
        }
    }

    /**
     * Resolve which configured key answered a detection assertion: with >1
     * allowList entry the key reports the credential it used; with exactly one
     * the field is omitted, so it's that one.
     */
    private fun matchDetectedCredential(
        group: List<SkKeyData>,
        result: FidoAssertionResult,
        creds: List<ByteArray>,
    ): SkKeyData? {
        val used = result.usedCredentialId ?: creds.singleOrNull()
        return group.firstOrNull { used != null && it.credentialId.contentEquals(used) }?.also {
            Log.d(TAG, "Either/or detect: present key holds credential ${it.credentialId.size}b")
        }
    }

    private suspend fun performUsbAssertion(
        device: UsbDevice,
        rpId: String,
        clientDataHash: ByteArray,
        credentialIds: List<ByteArray>,
        requireUv: Boolean,
        prePin: String?,
        up: Boolean = true,
    ): FidoAssertionResult {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        ensureUsbPermission(usbManager, device)

        // Select the CTAPHID interface (HID with both IN+OUT), not the first
        // HID interface — a YubiKey OTP+FIDO+CCID's keyboard HID has no OUT.
        val (hidInterface, endpointIn, endpointOut) = findCtapHidInterface(device)

        usbAccessGate.acquire(device.deviceName)
        try {
            val connection = usbManager.openDevice(device)
                ?: throw IOException("Failed to open USB device")
            connection.claimInterface(hidInterface, true)
            CtapHidTransport(connection, endpointIn, endpointOut).use { transport ->
                Log.d(TAG, "CTAPHID init...")
                transport.init()
                activeTransport.set(transport) // so cancelPending() can break the touch-wait
                try {
                    return runGetAssertionExchange(
                        rpId, clientDataHash, credentialIds, requireUv, prePin,
                        FidoTouchPrompt.TouchKey.Transport.USB, up = up,
                    ) { cmd ->
                        transport.sendCborCommand(cmd) {
                            _touchPrompt.value =
                                FidoTouchPrompt.TouchKey(FidoTouchPrompt.TouchKey.Transport.USB, currentKeyLabel)
                        }
                    }
                } finally {
                    activeTransport.compareAndSet(transport, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB FIDO assertion failed: ${e.javaClass.simpleName}: ${e.message}")
            lastAssertionError = e.message
            throw e
        } finally {
            usbAccessGate.release(device.deviceName)
        }
    }

    private suspend fun performNfcAssertion(
        tag: Tag,
        rpId: String,
        clientDataHash: ByteArray,
        credentialIds: List<ByteArray>,
        requireUv: Boolean,
        prePin: String?,
        up: Boolean = true,
    ): FidoAssertionResult {
        val isoDep = IsoDep.get(tag) ?: throw IOException("Tag does not support ISO-DEP")

        CtapNfcTransport(isoDep).use { transport ->
            transport.connect()
            transport.select()
            activeTransport.set(transport) // so cancelPending() can break a stuck tap-wait
            try {
                return runGetAssertionExchange(
                    rpId, clientDataHash, credentialIds, requireUv, prePin,
                    FidoTouchPrompt.TouchKey.Transport.NFC, up = up,
                ) { cmd -> transport.sendCborCommand(cmd) }
            } finally {
                activeTransport.compareAndSet(transport, null)
            }
        }
    }

    /**
     * Shared GetAssertion exchange for both USB and NFC. When [requireUv]
     * (a verify-required SK key, `ssh-keygen -O verify-required`), runs the
     * CTAP2 clientPIN/UV exchange up front and attaches a pinUvAuthParam to
     * the assertion.
     *
     * always-uv fallback (#230): some authenticators are configured with a
     * global "always require user verification" policy
     * (`ykman fido config toggle-always-uv`). The credential itself was NOT
     * registered verify-required, so [requireUv] is false and the first
     * GetAssertion carries no pinUvAuthParam — but the key rejects it with
     * STATUS_PIN_REQUIRED (0x36). Detect that, run the clientPIN exchange,
     * and retry GetAssertion once with a derived pinUvAuthParam. Before this
     * fallback the user saw an opaque "CTAP2 error 0x36" and could only
     * connect by turning always-uv off.
     *
     * [send] is the transport round-trip (CTAPHID or ISO-DEP-NFC);
     * [touchTransport] selects the UI prompt copy.
     */
    private suspend fun runGetAssertionExchange(
        rpId: String,
        clientDataHash: ByteArray,
        credentialIds: List<ByteArray>,
        requireUv: Boolean,
        prePin: String?,
        touchTransport: FidoTouchPrompt.TouchKey.Transport,
        up: Boolean = true,
        send: (ByteArray) -> ByteArray,
    ): FidoAssertionResult {
        var pinUvAuthParam: ByteArray? = null
        var pinProtocol: Int? = null
        if (requireUv) {
            // Use the PIN collected up front (NFC-safe one-shot): a wrong PIN
            // fails cleanly here rather than prompting mid-exchange.
            val (token, proto) = runUvPinProtocol(
                rpId, Ctap2Cbor.PERMISSION_GET_ASSERTION, send,
                knownPin = prePin, allowPrompt = false,
            )
            pinUvAuthParam = proto.authenticate(token, clientDataHash)
            pinProtocol = proto.version
        }

        Log.d(TAG, "Sending GetAssertion (rpId=$rpId, uv=${pinUvAuthParam != null})")
        _touchPrompt.value = FidoTouchPrompt.TouchKey(touchTransport, currentKeyLabel)
        var response = send(
            Ctap2Cbor.encodeGetAssertionCommand(
                rpId = rpId,
                clientDataHash = clientDataHash,
                credentialIds = credentialIds,
                pinUvAuthParam = pinUvAuthParam,
                pinUvAuthProtocol = pinProtocol,
                up = up,
            )
        )

        if (pinUvAuthParam == null &&
            response.isNotEmpty() &&
            response[0] == Ctap2Cbor.STATUS_PIN_REQUIRED
        ) {
            Log.i(TAG, "GetAssertion returned PIN_REQUIRED (0x36) without UV — " +
                "authenticator has always-uv enabled; running clientPIN and retrying")
            val (token, proto) = runUvPinProtocol(rpId, Ctap2Cbor.PERMISSION_GET_ASSERTION, send)
            _touchPrompt.value = FidoTouchPrompt.TouchKey(touchTransport, currentKeyLabel)
            response = send(
                Ctap2Cbor.encodeGetAssertionCommand(
                    rpId = rpId,
                    clientDataHash = clientDataHash,
                    credentialIds = credentialIds,
                    pinUvAuthParam = proto.authenticate(token, clientDataHash),
                    pinUvAuthProtocol = proto.version,
                    up = up,
                )
            )
        }

        Log.d(TAG, "CTAP response: ${response.size} bytes, status=0x${
            if (response.isNotEmpty()) "%02x".format(response[0]) else "empty"
        }")
        return parseCtap2AssertionResponse(response)
    }

    // ---------- credentialManagement enumeration ----------

    private suspend fun performUsbEnumerate(
        device: UsbDevice,
        rpIdPrefix: String,
        prePin: String,
    ): List<ResidentCredential> {
        // Open the USB transport and run the shared enumerate. USB permission
        // handling is duplicated from performUsbAssertion — pulling it out
        // into a generic openUsbCtapTransport helper is a follow-up.
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        ensureUsbPermission(usbManager, device)

        val (hidInterface, endpointIn, endpointOut) = findCtapHidInterface(device)

        usbAccessGate.acquire(device.deviceName)
        try {
            val connection = usbManager.openDevice(device)
                ?: throw IOException("Failed to open USB device")
            connection.claimInterface(hidInterface, true)
            CtapHidTransport(connection, endpointIn, endpointOut).use { transport ->
                Log.d(TAG, "CTAPHID init (enumerate)...")
                transport.init()
                return runCredentialManagementEnumerate(
                    rpIdPrefix = rpIdPrefix,
                    touchTransport = FidoTouchPrompt.TouchKey.Transport.USB,
                    prePin = prePin,
                ) { transport.sendCborCommand(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB enumerate failed: ${e.javaClass.simpleName}: ${e.message}")
            lastAssertionError = e.message
            throw e
        } finally {
            usbAccessGate.release(device.deviceName)
        }
    }

    private suspend fun performNfcEnumerate(
        tag: Tag,
        rpIdPrefix: String,
        prePin: String,
    ): List<ResidentCredential> {
        val isoDep = IsoDep.get(tag) ?: throw IOException("Tag does not support ISO-DEP")
        CtapNfcTransport(isoDep).use { transport ->
            transport.connect()
            transport.select()
            return runCredentialManagementEnumerate(
                rpIdPrefix = rpIdPrefix,
                touchTransport = FidoTouchPrompt.TouchKey.Transport.NFC,
                prePin = prePin,
            ) { transport.sendCborCommand(it) }
        }
    }

    /**
     * Drive the CTAP2.1 `authenticatorCredentialManagement` enumeration loop:
     * PIN/UV exchange (cm-permission token) → enumerateRPs Begin/GetNext for
     * each registered RP → for each matching RP, enumerateCredentials
     * Begin/GetNext.
     *
     * The per-subcommand `pinUvAuthParam` is derived freshly from the token
     * each call — `LEFT(16, HMAC-SHA-256(token, subCommand_byte ||
     * subCommandParams_cbor))`. Subcommands without params (RPs Begin,
     * RPs GetNext, Creds GetNext) hash just the subCommand byte; the only
     * subcommand that takes params here is CredsBegin (rpIdHash map).
     *
     * Transport-agnostic — caller passes the `send` lambda wired to either
     * CTAPHID or ISO-DEP-NFC. Returns (rpId, [SkKeyData]) pairs ready for
     * the SSH key store.
     */
    private suspend fun runCredentialManagementEnumerate(
        rpIdPrefix: String,
        touchTransport: FidoTouchPrompt.TouchKey.Transport,
        prePin: String,
        send: (ByteArray) -> ByteArray,
    ): List<ResidentCredential> {
        // 1. PIN/UV exchange — cm token has no rpId scoping. The PIN was
        //    collected before the tap, so never prompt from in here: a dialog
        //    mid-exchange drops the NFC tag (#449).
        var info: Ctap2Cbor.GetInfoResponse? = null
        val (token, protocol) = runUvPinProtocol(
            rpId = null,
            permission = Ctap2Cbor.PERMISSION_CREDENTIAL_MANAGEMENT,
            send = send,
            knownPin = prePin,
            allowPrompt = false,
            onInfo = { info = it },
        )

        // Credential management exists in two forms. CTAP 2.1 standardised it
        // as command 0x0A; before that it shipped as the 2.1-PRE prototype
        // 0x41, which is all a YubiKey below firmware 5.5 answers — sending
        // 0x0A there returns CTAP1_ERR_INVALID_COMMAND (0x01) right after a
        // successful PIN exchange, which is what #449 hit on a 5.4.3 key.
        // Take the answer from GetInfo rather than probing: a key advertising
        // credMgmt gets the standard command, one advertising only
        // credentialMgmtPreview gets the prototype.
        val useStandard = info?.credMgmtSupported == true
        val usePreview = !useStandard && info?.credMgmtPreviewSupported == true
        if (!useStandard && !usePreview) {
            throw IOException(
                "This security key does not support listing its resident credentials " +
                    "(no credMgmt or credentialMgmtPreview in its capabilities). " +
                    "Import the key file from the machine that created it instead.",
            )
        }
        Log.d(
            TAG,
            "Credential management: ${if (usePreview) "0x41 (2.1-PRE preview)" else "0x0A (CTAP 2.1)"}",
        )

        // 2. enumerateRPs: Begin (subCmd 2) returns first RP + totalRPs,
        //    then GetNext (subCmd 3) for the rest. No subCommandParams.
        _touchPrompt.value = FidoTouchPrompt.TouchKey(touchTransport, currentKeyLabel)
        val firstRp = sendCmCommand(
            send, protocol, token,
            subCommand = Ctap2Cbor.CM_SUB_ENUMERATE_RPS_BEGIN,
            params = null,
            includeAuth = true,
            describe = "enumerateRPsBegin",
            preview = usePreview,
        )?.let { Ctap2Cbor.decodeEnumerateRPsResponse(it) }
            ?: return emptyList() // STATUS_NO_CREDENTIALS — no resident creds at all
        val rpList = mutableListOf(firstRp)
        val total = firstRp.totalRPs ?: 1
        for (i in 1 until total) {
            val resp = sendCmCommand(
                send, protocol, token,
                subCommand = Ctap2Cbor.CM_SUB_ENUMERATE_RPS_GET_NEXT,
                params = null,
                includeAuth = false,
                describe = "enumerateRPsGetNext[$i]",
                preview = usePreview,
            ) ?: break
            rpList += Ctap2Cbor.decodeEnumerateRPsResponse(resp)
        }
        Log.d(TAG, "Found ${rpList.size} RPs: ${rpList.map { it.id }}")

        // 3. For each RP whose id matches the prefix, enumerate its credentials.
        val out = mutableListOf<ResidentCredential>()
        for (rp in rpList) {
            if (rpIdPrefix.isNotEmpty() && !rp.id.startsWith(rpIdPrefix)) continue
            val paramsBytes = Ctap2Cbor.encodeEnumerateCredentialsParams(rp.rpIdHash)
            val firstCred = sendCmCommand(
                send, protocol, token,
                subCommand = Ctap2Cbor.CM_SUB_ENUMERATE_CREDS_BEGIN,
                params = paramsBytes,
                includeAuth = true,
                describe = "enumerateCredsBegin(${rp.id})",
                preview = usePreview,
            )?.let { Ctap2Cbor.decodeEnumerateCredentialsResponse(it) } ?: continue
            val creds = mutableListOf(firstCred)
            val totalCreds = firstCred.totalCredentials ?: 1
            for (i in 1 until totalCreds) {
                val resp = sendCmCommand(
                    send, protocol, token,
                    subCommand = Ctap2Cbor.CM_SUB_ENUMERATE_CREDS_GET_NEXT,
                    params = null,
                    includeAuth = false,
                    describe = "enumerateCredsGetNext[$i](${rp.id})",
                    preview = usePreview,
                ) ?: break
                creds += Ctap2Cbor.decodeEnumerateCredentialsResponse(resp)
            }
            Log.d(TAG, "RP ${rp.id}: ${creds.size} credentials")
            for (cred in creds) {
                // We can't tell from CTAP whether the key was registered with
                // verify-required, so default flags to 0x01 (user-presence).
                // If the user's key actually requires UV, the GetAssertion
                // path will still prompt for PIN when signing — the flags
                // bit is advisory metadata for the SSH stub, not a gate.
                val sk = SkKeyParser.buildFromCtapCredential(cred, rp.id, flags = 0x01)
                // The credential's user.name is the label the key was created
                // with — Haven writes it there in makeCredential. Carrying it
                // back is what lets import default to that name instead of a
                // generic "FIDO2: <rpId>" (#449).
                out += ResidentCredential(rp.id, cred.userName?.trim()?.ifBlank { null }, sk)
            }
        }
        Log.d(TAG, "Enumerate complete: ${out.size} SSH-SK credential(s) returned")
        return out
    }

    /**
     * Send one credentialManagement subcommand, derive the per-subcommand
     * pinUvAuthParam when [includeAuth] is true, and return the response
     * payload (without the status byte) or null on STATUS_NO_CREDENTIALS.
     */
    private fun sendCmCommand(
        send: (ByteArray) -> ByteArray,
        protocol: Ctap2PinProtocol,
        token: ByteArray,
        subCommand: Int,
        params: ByteArray?,
        includeAuth: Boolean,
        describe: String,
        preview: Boolean,
    ): ByteArray? {
        val authParam = if (includeAuth) {
            // pinUvAuthParam = LEFT(16, HMAC(token, subCommand_byte || params))
            val msg = byteArrayOf(subCommand.toByte()) + (params ?: ByteArray(0))
            protocol.authenticate(token, msg)
        } else null
        val cmd = Ctap2Cbor.encodeCredentialManagementCommand(
            subCommand = subCommand,
            subCommandParams = params,
            pinUvAuthProtocol = if (authParam != null) protocol.version else null,
            pinUvAuthParam = authParam,
            preview = preview,
        )
        val resp = send(cmd)
        if (resp.isEmpty()) throw IOException("$describe: empty CTAP response")
        val status = resp[0]
        if (status == Ctap2Cbor.STATUS_NO_CREDENTIALS) {
            Log.d(TAG, "$describe: STATUS_NO_CREDENTIALS")
            return null
        }
        if (status != Ctap2Cbor.STATUS_OK) {
            throw IOException("$describe: CTAP2 error 0x${"%02x".format(status)}")
        }
        return resp.copyOfRange(1, resp.size)
    }

    /**
     * Run the CTAP2 clientPIN protocol against an authenticator over [send]
     * (one round-trip per CBOR command, status byte preserved). Returns
     * `(pinUvAuthToken, protocol)` — the *raw* token, not a derived
     * authParam. Callers compute their own authParam(s) per command via
     * `protocol.authenticate(token, message)`:
     *   - GetAssertion uses `message = clientDataHash`.
     *   - CredentialManagement uses `message = subCommand_byte ||
     *     subCommandParams_cbor`, recomputed per subcommand.
     *
     * [permission] is one of the `Ctap2Cbor.PERMISSION_*` flags. The
     * token is scoped by the authenticator to that permission, so e.g. a
     * cm-permission token will be rejected if the caller then tries to
     * use it for a GetAssertion authParam (and vice versa).
     *
     * Throws [IOException] if the key has no PIN configured, the user
     * cancels, or PIN entry exhausts the authenticator's retry counter.
     */
    private suspend fun runUvPinProtocol(
        rpId: String?,
        permission: Int,
        send: (ByteArray) -> ByteArray,
        knownPin: String? = null,
        allowPrompt: Boolean = true,
        /** Receives the GetInfo result, for callers that need more than the token. */
        onInfo: ((Ctap2Cbor.GetInfoResponse) -> Unit)? = null,
    ): Pair<ByteArray, Ctap2PinProtocol> {
        // 1. authenticatorGetInfo to learn supported protocols and PIN state
        val infoResp = send(Ctap2Cbor.encodeGetInfoCommand())
        ensureOk(infoResp, "GetInfo")
        val info = Ctap2Cbor.decodeGetInfoResponse(infoResp.copyOfRange(1, infoResp.size))
        onInfo?.invoke(info)
        Log.d(TAG, "GetInfo: pinProtocols=${info.pinUvAuthProtocols}, " +
            "clientPinSet=${info.clientPinSet}, uvBuiltIn=${info.uvBuiltIn}, " +
            "pinUvAuthToken=${info.pinUvAuthTokenSupported}")

        if (!info.clientPinSet) {
            throw IOException(
                "This SK key requires verification, but the security key has no " +
                "PIN configured. Set a PIN with `ykman fido access change-pin` " +
                "(YubiKey) or your manufacturer's tool, then try again."
            )
        }

        val protocol = Ctap2PinProtocol.pick(info.pinUvAuthProtocols)
            ?: throw IOException("Authenticator does not support PIN protocol v1 or v2")
        // CTAP 2.1 keys advertise `pinUvAuthToken: true` and accept subCommand
        // 0x09 (with permissions). CTAP 2.0 / 2.1-PRE keys (e.g. older YubiKey 5
        // firmware) only support legacy 0x05; calling 0x09 returns INVALID_COMMAND.
        val useWithPermissions = info.pinUvAuthTokenSupported
        Log.d(TAG, "Using PIN/UV auth protocol v${protocol.version}, " +
            "subCmd=${if (useWithPermissions) "0x09 (with permissions)" else "0x05 (legacy)"}")

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
        //    the user cancels or the authenticator returns a hard failure. A
        //    [knownPin] (e.g. one collected up front for registration) is used
        //    for the first attempt without prompting. When [allowPrompt] is
        //    false (registration's one-shot, NFC-safe path) the supplied PIN is
        //    the only attempt — a wrong PIN fails cleanly instead of prompting
        //    mid-exchange, which would drop the NFC tap.
        var retriesNote: Int? = null
        var pinToTry: String? = knownPin
        while (true) {
            val pin = pinToTry
                ?: (if (allowPrompt) promptPin(retriesNote) else null)
                ?: throw IOException(
                    if (allowPrompt) "PIN entry cancelled" else "Incorrect security-key PIN",
                )
            pinToTry = null
            if (pin.length < 4) throw IOException("PIN must be at least 4 characters")

            val pinHash = MessageDigest.getInstance("SHA-256")
                .digest(pin.toByteArray(Charsets.UTF_8))
                .copyOfRange(0, 16)
            val pinHashEnc = protocol.encrypt(sharedSecret, pinHash)

            val tokReq = if (useWithPermissions) {
                Ctap2Cbor.encodeClientPinGetTokenWithPermissions(
                    protocol = protocol.version,
                    platformKeyAgreement = platformKa,
                    pinHashEnc = pinHashEnc,
                    permissions = permission,
                    rpId = rpId,
                )
            } else {
                Ctap2Cbor.encodeClientPinGetTokenLegacy(
                    protocol = protocol.version,
                    platformKeyAgreement = platformKa,
                    pinHashEnc = pinHashEnc,
                )
            }
            val tokResp = send(tokReq)
            when (val status = tokResp[0]) {
                Ctap2Cbor.STATUS_OK -> {
                    val encToken = Ctap2Cbor.decodeClientPinTokenResponse(
                        tokResp.copyOfRange(1, tokResp.size)
                    )
                    val token = protocol.decrypt(sharedSecret, encToken)
                    Log.d(TAG, "PIN verified; token=${token.size}b, permission=0x${"%02x".format(permission)}")
                    return token to protocol
                }
                Ctap2Cbor.STATUS_PIN_INVALID -> {
                    retriesNote = (retriesNote ?: 8) - 1
                    Log.w(TAG, "Wrong PIN; ~$retriesNote attempts remain (estimated)")
                    if (retriesNote <= 0) {
                        throw IOException("Too many wrong PIN attempts.")
                    }
                    if (!allowPrompt) {
                        // One-shot path (registration): don't loop/prompt — the
                        // supplied PIN was wrong; surface it and stop.
                        throw IOException(
                            "Incorrect security-key PIN (about $retriesNote attempts left " +
                                "before the key locks).",
                        )
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
        val result = deferred.await()
        // On submit (non-null) the caller advances _touchPrompt (TouchKey, or
        // null via withDiscoveredFidoDevice's finally) on its next step, so
        // leave the EnterPin in place to avoid a flicker. On CANCEL (null) the
        // caller THROWS instead of reassigning — e.g. the verify-required
        // pre-collect `promptPin(null) ?: throw` — so nothing else clears the
        // dialog and it would linger with a now-dead Cancel button. Clear it
        // here so cancel actually dismisses the popup (#237 pre-collect fix).
        if (result == null) {
            _touchPrompt.value = null
        }
        return result
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
        ctap2AssertionErrorForStatus(status)?.let { throw it }

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
            usedCredentialId = parsed.usedCredentialId,
        )
    }

    /**
     * Whether [device] is a usable FIDO2 CTAPHID authenticator, probed WITHOUT
     * opening it (so we don't request USB permission just to enumerate). True
     * iff it has a CTAPHID interface — see [findFidoCtapHidInterface].
     */
    private fun isFidoHidDevice(device: UsbDevice): Boolean =
        findFidoCtapHidInterface(device) != null

    /**
     * Find the FIDO CTAPHID interface on [device] and its interrupt IN/OUT
     * endpoints, or throw if it has none. Thin wrapper over the testable
     * top-level [findFidoCtapHidInterface] with a descriptive error.
     */
    private fun findCtapHidInterface(
        device: UsbDevice,
    ): Triple<UsbInterface, UsbEndpoint, UsbEndpoint> =
        findFidoCtapHidInterface(device) ?: throw IOException(
            "No FIDO CTAPHID interface on this device. A FIDO2 key has a HID " +
                "interface with an interrupt IN+OUT endpoint pair; this device " +
                "has none (e.g. a USB audio dongle's volume-button HID is " +
                "interrupt-IN only, an OTP keyboard HID has no OUT endpoint)."
        )
}

/**
 * The FIDO CTAPHID interface on [device] plus its interrupt IN/OUT endpoints,
 * or null if the device has none. Top-level + internal so the device-matching
 * heuristic is unit-testable without a Context-bound [FidoAuthenticator].
 *
 * A CTAPHID interface is **HID-class with a bidirectional interrupt endpoint
 * pair** — CTAPHID is a request/response report protocol, so a real FIDO2 key
 * always exposes both an interrupt IN and an interrupt OUT endpoint on its
 * FIDO interface. Two false-positive classes this rejects, both of which broke
 * NFC-key auth when plugged in:
 *
 *  - **USB audio dongles / DACs.** Their inline play/volume buttons are a HID
 *    *consumer-control* interface with an interrupt-IN endpoint only (no OUT).
 *    The old "any HID interface" check matched the dongle, Haven selected it as
 *    the key, [findCtapHidInterface] then failed (no OUT endpoint), and the
 *    NFC path was never armed — the reported "FIDO2 doesn't work with a USB
 *    audio device attached" bug.
 *  - **Composite OTP+FIDO+CCID keys' keyboard HID.** The OTP keyboard interface
 *    is interrupt-IN only; the *CTAPHID* interface on the same device has the
 *    IN+OUT pair, so this still matches such keys (via the right interface)
 *    while skipping the keyboard one.
 *
 * This is a permission-free heuristic, not a report-descriptor parse: it does
 * not read the HID usage page (0xF1D0), which would require opening the device.
 * A non-FIDO HID device that happens to expose an interrupt IN+OUT pair would
 * still match — but the assertion path now falls through to NFC / another key
 * when such a device yields no usable credential (see getAssertion), so a
 * false positive degrades to "try the next transport" rather than a hard fail.
 */
internal fun findFidoCtapHidInterface(
    device: UsbDevice,
): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
    for (i in 0 until device.interfaceCount) {
        val iface = device.getInterface(i)
        if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (j in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(j)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
            else if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
        }
        if (epIn != null && epOut != null) return Triple(iface, epIn, epOut)
    }
    return null
}

/**
 * Recognise transport failures worth explaining in the user's terms, or null to
 * fall back to the raw exception text (#449).
 *
 * Android words a lifted NFC tag as "Permission Denial: Tag ( ID: ... ) is out
 * of date", which reads like a permissions bug in Haven rather than "the key
 * left the field". It is the normal outcome of the key moving — including
 * moving it to type a PIN — so it needs to say so.
 */
fun fidoFailureMessage(e: Throwable): String? {
    val raw = e.message.orEmpty()
    return when {
        // Two different Android wordings for the same physical event — the key
        // left the field. "is out of date" is a stale Tag handle (the tag was
        // re-dispatched while we held the old one); "Tag was lost" is the field
        // breaking mid-transceive. Neither tells the user what to do.
        raw.contains("is out of date") || raw.contains("Tag was lost") ->
            "The security key moved out of range before Haven finished reading it. " +
                "Hold it flat against the phone without moving it until the operation " +
                "completes — this can take a few seconds — then try again."
        else -> null
    }
}
