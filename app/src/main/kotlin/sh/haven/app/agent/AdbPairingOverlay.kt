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
