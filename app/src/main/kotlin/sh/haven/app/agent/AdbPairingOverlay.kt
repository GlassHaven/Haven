package sh.haven.app.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The pairing code box, drawn as a floating window on top of Android's own
 * pairing dialog (#575).
 *
 * It has to be an overlay, not a screen. The six-digit code is generated and
 * displayed by the system dialog, and no shell-uid API exposes it — so the user
 * must be able to *read the system dialog and type into Haven at the same
 * time*. An Activity cannot do that: bringing Haven forward sends the dialog to
 * the background and takes the code with it. A `TYPE_APPLICATION_OVERLAY`
 * window sits above the dialog while it stays visible underneath.
 *
 * Built from plain Views rather than Compose on purpose. A `ComposeView` added
 * straight to `WindowManager` has no `ViewTreeLifecycleOwner` /
 * `ViewTreeSavedStateRegistryOwner` and crashes unless a synthetic owner is
 * hung off it; this window has one text field and two buttons, and the failure
 * mode of getting that wrong is a crash on top of a system dialog.
 */
internal class AdbPairingOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null

    /** True when the user has granted "Display over other apps". */
    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Show the box. [onSubmit] receives the typed code; [onCancel] fires on
     * dismiss. Idempotent — showing twice replaces the first window rather than
     * stacking two.
     */
    @SuppressLint("SetTextI18n")
    fun show(
        endpoint: AdbPairingDiscovery.Endpoint,
        onSubmit: (String) -> Unit,
        onCancel: () -> Unit,
    ): Boolean {
        if (!canDraw()) {
            Log.w(TAG, "overlay permission not granted")
            return false
        }
        hide()

        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 8f * context.resources.displayMetrics.density
                setColor(Color.parseColor("#EE202124"))
            }
        }

        panel.addView(
            TextView(context).apply {
                text = "Pair this device"
                setTextColor(Color.WHITE)
                textSize = 16f
            },
        )
        panel.addView(
            TextView(context).apply {
                // The port is the part nobody can be expected to know — it is
                // freshly allocated for this dialog and gone when it closes.
                text = "${endpoint.host}:${endpoint.port}\nType the 6-digit code shown behind this box."
                setTextColor(Color.parseColor("#BBBBBB"))
                textSize = 12f
                setPadding(0, pad / 2, 0, pad / 2)
            },
        )

        val field = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "000000"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#777777"))
            textSize = 20f
        }
        panel.addView(field)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(
            Button(context).apply {
                text = "Cancel"
                setOnClickListener {
                    hide()
                    onCancel()
                }
            },
        )
        row.addView(
            Button(context).apply {
                text = "Pair"
                setOnClickListener {
                    val code = field.text?.toString()?.trim().orEmpty()
                    if (code.length != CODE_LENGTH) {
                        field.error = "6 digits"
                        return@setOnClickListener
                    }
                    hide()
                    onSubmit(code)
                }
            },
        )
        panel.addView(row)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // FOCUSABLE is required — the field takes keyboard input. The
            // system dialog underneath loses focus but stays on screen, which
            // is the whole point: the code must remain readable while typing.
            // NOT_TOUCH_MODAL keeps touches outside this window going to
            // whatever is behind it, so the dialog is still dismissible.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            // Bottom, so it does not cover the code the dialog draws mid-screen.
            gravity = Gravity.BOTTOM
        }

        return try {
            windowManager.addView(panel, params)
            root = panel
            true
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
            false
        }
    }

    fun hide() {
        root?.let { v ->
            runCatching { windowManager.removeView(v) }
                .onFailure { Log.d(TAG, "removeView: ${it.message}") }
        }
        root = null
    }

    internal companion object {
        const val TAG = "AdbPairingOverlay"
        const val CODE_LENGTH = 6

        /** Why the code box did not appear — reported so the caller can act. */
        const val REASON_DISABLED = "disabled-by-caller"
        const val REASON_PERMISSION = "permission-missing"
        const val REASON_ADD_FAILED = "add-view-failed"

        /**
         * Map an attempt to a machine-readable reason. Pure so the mapping is
         * testable without a window manager: an `overlayShown: false` with no
         * explanation is a dead end for the caller, which is the whole point.
         */
        fun skipReason(wantOverlay: Boolean, canDraw: Boolean, added: Boolean): String? = when {
            !wantOverlay -> REASON_DISABLED
            !canDraw -> REASON_PERMISSION
            !added -> REASON_ADD_FAILED
            else -> null
        }

        /** Overlay permission cannot be granted at all until the user lifts it. */
        const val STATE_RESTRICTED = "restricted-settings-block"
        const val STATE_GRANTED = "granted"
        const val STATE_DENIED = "denied"

        /**
         * Distinguish "not granted yet" from "cannot be granted yet".
         *
         * Android 13+ blocks SYSTEM_ALERT_WINDOW for apps installed from an
         * unknown source until the user picks "Allow restricted settings" on
         * the App info page. Haven is sideloaded for most users (GitHub APK,
         * and F-Droid without the privileged extension), so this is the common
         * case, not the exotic one — and `canDrawOverlays` returns false
         * identically either way, which sends the user to a toggle that cannot
         * move. AppOps reports the restricted op as MODE_ERRORED rather than
         * MODE_IGNORED, which is the only signal available to tell them apart.
         */
        /**
         * Granted, or not. There is deliberately no third "restricted" state.
         *
         * Two attempts at detecting Android's restricted-settings block from
         * inside the app both failed, measured on a blocked device:
         *   - system_alert_window sits at MODE_DEFAULT (3), not MODE_ERRORED —
         *     so testing for ERRORED reported plain denial while the toggle was
         *     inert.
         *   - access_restricted_settings cannot be read at all: passing that op
         *     name to unsafeCheckOpNoThrow throws, so the check returns null.
         *     `adb shell appops get` can read it (it reports `ignore`), because
         *     that goes through a system API the app does not have.
         *
         * So the app cannot tell "not granted yet" from "cannot be granted
         * yet". Rather than branch on a signal that does not exist, [routeFor]
         * sends every ungranted case to the App info page, which is a superset:
         * both the ⋮ "Allow restricted settings" item and the permission entry
         * are reachable from there, whereas the overlay-toggle deep link is a
         * dead end whenever the block is in force.
         */
        fun grantState(context: Context): String =
            if (Settings.canDrawOverlays(context)) STATE_GRANTED else STATE_DENIED

        /** `android:access_restricted_settings` — hidden, so referenced by name. */
        const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

        private fun opMode(context: Context, op: String): Int? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val ops = context.getSystemService(android.app.AppOpsManager::class.java) ?: return null
            // Throws IllegalArgumentException for an op this Android build does
            // not know — a null reading is "cannot tell", never "not restricted".
            return runCatching {
                ops.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), context.packageName)
            }.getOrNull()
        }

        /**
         * Raw AppOps modes for the two candidate signals, so one round trip on a
         * real device settles which one tracks the restricted-settings block
         * instead of another build-install-restart per guess. Reported as
         * diagnostics; nothing branches on it.
         *
         * AppOps modes: 0=ALLOWED 1=IGNORED 2=ERRORED 3=DEFAULT 4=FOREGROUND.
         */
        fun opModes(context: Context): Map<String, Int?> = mapOf(
            "system_alert_window" to opMode(
                context,
                android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            ),
            "access_restricted_settings" to opMode(context, OP_ACCESS_RESTRICTED_SETTINGS),
        )

        /**
         * Where to send the user for a given state. Pure so the routing is
         * testable: sending someone to the overlay toggle when the real block
         * is on the App info page is the failure this exists to prevent.
         */
        fun routeFor(state: String): String? = when (state) {
            // App info, never the overlay toggle: the toggle cannot move while
            // Android's sideload restriction is in force, and the app has no
            // way to know whether it is. App info reaches both the ⋮ "Allow
            // restricted settings" item and the permission itself, so it is
            // correct in both cases instead of right in one and a dead end in
            // the other.
            STATE_RESTRICTED, STATE_DENIED -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> null
        }

        /** App info page — where the ⋮ "Allow restricted settings" item lives. */
        fun appInfoIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Deep-link to Haven's own row in "Display over other apps" rather than
         * the top of the list — the settings screen is a per-app list and
         * landing on it unfiltered means hunting.
         *
         * Deliberately NOT launched from inside the pairing flow: it is a
         * full-screen settings activity, and opening it would send Android's
         * pairing dialog to the background, taking the code with it and
         * expiring the ephemeral port. Grant first, then pair.
         */
        fun grantIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
