package sh.haven.feature.vnc

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private const val PREFS_NAME = "vnc_compose"
private const val PREF_PRESS_ENTER = "press_enter"

/**
 * Whether [ch] survives [sh.haven.core.vnc.VncClient.typeText]: printable
 * ASCII plus the control characters it maps to keys (newline → Return,
 * tab → Tab). Anything else goes out as a raw keysym the server most likely
 * cannot render, so the compose bar refuses to send it.
 */
internal fun isVncTypeable(ch: Char): Boolean =
    ch == '\n' || ch == '\r' || ch == '\t' || ch in ' '..'~'

/** True if every character of [text] can be typed over VNC. */
internal fun isVncTypeable(text: String): Boolean = text.all(::isVncTypeable)

/**
 * The exact keystrokes to type for [text]: CRLF / CR collapse to one newline
 * (each newline is one Return), plus a trailing Return when [pressEnter].
 */
internal fun vncComposePayload(text: String, pressEnter: Boolean): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    return if (pressEnter) normalized + "\n" else normalized
}

/** Marks characters that cannot be typed over VNC so the user sees them before sending. */
private class HighlightUntypeable(private val style: SpanStyle) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            text.text.forEach { ch ->
                if (isVncTypeable(ch)) append(ch) else withStyle(style) { append(ch) }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/**
 * Compose-then-send input bar for the VNC viewer: type and check the text in
 * a local field — passwords included — and send it to the remote in one go,
 * instead of every keystroke landing on the remote as it is typed.
 *
 * Text is typed as keystrokes only (never via the remote clipboard, so a
 * password is not left there). The field disables the IME's autocorrect,
 * suggestions, auto-capitalisation and learning, which is what used to slip
 * in an extra space or a "corrected" word. Characters VNC cannot type are
 * highlighted and block Send. Nothing is kept: a send clears and closes the
 * bar, and the text is not saved across process death.
 *
 * @param onSend receives the exact keystrokes to type (see [vncComposePayload]).
 */
@Composable
internal fun VncComposeBar(
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    // Plain remember, not rememberSaveable: the text may be a password and
    // must not be written into the saved-instance-state bundle.
    var text by remember { mutableStateOf("") }
    var masked by rememberSaveable { mutableStateOf(false) }
    var pressEnter by remember { mutableStateOf(prefs.getBoolean(PREF_PRESS_ENTER, false)) }
    val focusRequester = remember { FocusRequester() }

    val typeable = isVncTypeable(text)
    val canSend = text.isNotEmpty() && typeable
    val highlight = SpanStyle(
        color = MaterialTheme.colorScheme.onErrorContainer,
        background = MaterialTheme.colorScheme.errorContainer,
    )
    val send = {
        if (canSend) {
            onSend(vncComposePayload(text, pressEnter))
            text = ""
            onDismiss()
        }
    }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.vnc_compose_placeholder)) },
                    visualTransformation = if (masked) {
                        PasswordVisualTransformation()
                    } else {
                        HighlightUntypeable(highlight)
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        // Password type is what makes IMEs drop suggestions and
                        // stop learning; visibility is the eye toggle's job.
                        keyboardType = KeyboardType.Password,
                    ),
                    singleLine = false,
                    maxLines = 4,
                    isError = !typeable,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                )
                IconButton(onClick = { masked = !masked }) {
                    Icon(
                        if (masked) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = stringResource(
                            if (masked) R.string.vnc_compose_show else R.string.vnc_compose_hide,
                        ),
                    )
                }
                IconButton(onClick = send, enabled = canSend) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.vnc_compose_send),
                    )
                }
            }
            if (!typeable) {
                Text(
                    stringResource(R.string.vnc_compose_untypeable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = pressEnter,
                        onCheckedChange = {
                            pressEnter = it
                            prefs.edit().putBoolean(PREF_PRESS_ENTER, it).apply()
                        },
                    )
                    Text(
                        stringResource(R.string.vnc_compose_press_enter),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.vnc_action_close))
                }
            }
        }
    }
}
