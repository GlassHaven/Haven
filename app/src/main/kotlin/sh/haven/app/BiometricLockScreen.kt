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

    // No host activity — refuse to unlock. Better to be stuck on a blank
    // screen than to hand out credentials.
    if (activity == null) {
        LockedSurface(
            title = "Haven is locked",
            body = "Cannot show authentication prompt in this context.",
        )
        return
    }

    // Biometric/device-credential not available — show a clear blocked
    // state with a path to fix it. NEVER auto-unlock: the user enabled
    // app-lock for a reason, and silently bypassing it on hardware
    // unenrollment turns the lock into security theatre.
    if (availability != BiometricAuthenticator.Availability.AVAILABLE) {
        val (heading, explanation) = when (availability) {
            BiometricAuthenticator.Availability.NOT_ENROLLED ->
                "Set up a screen lock" to
                    "Haven app-lock is enabled, but this device has no screen lock " +
                    "or biometric enrolled. Set one up in device Settings to unlock."
            BiometricAuthenticator.Availability.NO_HARDWARE ->
                "Authentication unavailable" to
                    "This device cannot authenticate (no biometric hardware and no " +
                    "device credential). Set up a PIN or password in Settings to unlock."
            else -> "Haven is locked" to "Authenticate to continue"
        }
        LockedSurface(
            title = heading,
            body = explanation,
            primaryLabel = "Open device settings",
            onPrimary = {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
        return
    }

    // Trigger counter: increment to re-launch authentication
    var authTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(authTrigger) {
        errorMessage = null
        when (val result = authenticator.authenticate(activity)) {
            is BiometricAuthenticator.AuthResult.Success -> onUnlocked()
            is BiometricAuthenticator.AuthResult.Failure -> errorMessage = result.message
            is BiometricAuthenticator.AuthResult.Cancelled -> {
                // User cancelled — send them back to the home screen
                (activity as? android.app.Activity)?.moveTaskToBack(true)
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
