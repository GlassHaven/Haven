package sh.haven.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import sh.haven.core.security.BiometricAuthenticator

/**
 * What the lock screen should render for a given biometric availability.
 *
 * Sealed so that adding a new state forces every site (composable +
 * unit tests) to handle it explicitly. Critically, no variant means
 * "auto unlock" — that's what the C2 fix enforces. Future refactors
 * cannot accidentally re-introduce a silent-bypass branch without
 * adding a new sealed subtype.
 */
internal sealed class BiometricLockState {
    /** Biometric/device-credential is available — render the prompt. */
    object Prompt : BiometricLockState()

    /**
     * Authentication cannot be performed at all (no enrolment, or no
     * hardware, or no host activity). Render an explanation and a
     * recovery path. Never call onUnlocked from this state.
     */
    data class Blocked(
        val title: String,
        val body: String,
        val canOpenSettings: Boolean,
    ) : BiometricLockState()
}

/**
 * Pure decision function for the lock screen UI. Kept non-composable
 * and non-Android so it can be exercised by plain JVM unit tests
 * (see BiometricLockStateTest).
 */
internal fun biometricLockStateFor(
    availability: BiometricAuthenticator.Availability,
    hasFragmentActivity: Boolean,
): BiometricLockState {
    if (!hasFragmentActivity) {
        return BiometricLockState.Blocked(
            title = "Haven is locked",
            body = "Cannot show authentication prompt in this context.",
            canOpenSettings = false,
        )
    }
    return when (availability) {
        BiometricAuthenticator.Availability.AVAILABLE -> BiometricLockState.Prompt
        BiometricAuthenticator.Availability.NOT_ENROLLED -> BiometricLockState.Blocked(
            title = "Set up a screen lock",
            body = "Haven app-lock is enabled, but this device has no screen lock " +
                "or biometric enrolled. Set one up in device Settings to unlock.",
            canOpenSettings = true,
        )
        BiometricAuthenticator.Availability.NO_HARDWARE -> BiometricLockState.Blocked(
            title = "Authentication unavailable",
            body = "This device cannot authenticate (no biometric hardware and no " +
                "device credential). Set up a PIN or password in Settings to unlock.",
            canOpenSettings = true,
        )
    }
}

@Composable
fun BiometricLockScreen(
    authenticator: BiometricAuthenticator,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Re-check availability whenever the activity resumes so a user who
    // visits Settings to set up a screen lock is picked up on return.
    var availability by remember {
        mutableStateOf(authenticator.checkAvailability(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                availability = authenticator.checkAvailability(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val lockState = biometricLockStateFor(availability, hasFragmentActivity = activity != null)
    if (lockState is BiometricLockState.Blocked) {
        val maybeActivity = activity
        LockedSurface(
            title = lockState.title,
            body = lockState.body,
            primaryLabel = if (lockState.canOpenSettings) "Open device settings" else null,
            onPrimary = if (lockState.canOpenSettings && maybeActivity != null) {
                {
                    runCatching {
                        maybeActivity.startActivity(
                            Intent(Settings.ACTION_SECURITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            } else null,
        )
        return
    }

    // lockState is Prompt — biometricLockStateFor() guarantees this only
    // happens when activity != null, but the compiler can't see through
    // the sealed-class branch. Bind a local non-null ref.
    val promptActivity = activity!!

    // Trigger counter: increment to re-launch authentication
    var authTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(authTrigger) {
        errorMessage = null
        when (val result = authenticator.authenticate(promptActivity)) {
            is BiometricAuthenticator.AuthResult.Success -> onUnlocked()
            is BiometricAuthenticator.AuthResult.Failure -> errorMessage = result.message
            is BiometricAuthenticator.AuthResult.Cancelled -> {
                // User cancelled — send them back to the home screen
                promptActivity.moveTaskToBack(true)
            }
        }
    }

    LockedSurface(
        title = "Haven is locked",
        body = "Authenticate to continue",
        errorMessage = errorMessage,
        primaryLabel = "Unlock",
        onPrimary = { authTrigger++ },
    )
}

@Composable
private fun LockedSurface(
    title: String,
    body: String,
    errorMessage: String? = null,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Screen lock",
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            if (primaryLabel != null && onPrimary != null) {
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onPrimary) {
                    Text(primaryLabel)
                }
            }
        }
    }
}
