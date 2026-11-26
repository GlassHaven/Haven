package sh.haven.core.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.launch

/**
 * OutlinedTextField for password / passphrase / secret entry with a trailing eye
 * icon that toggles between masked and cleartext display. Visibility
 * state is per-field, defaults to masked, and is preserved across config
 * changes via [rememberSaveable].
 *
 * Wraps the field in [PrivateKeyboard] so Android's integer
 * `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` (`0x01000000`) flag is set on
 * `EditorInfo.imeOptions`, disables autocorrect, and uses
 * [KeyboardType.PasswordVisible] (never plain [KeyboardType.Text]) when revealed
 * so revealed secrets are never fed into IME learning dictionaries or suggestion strips.
 *
 * [onRevealRequest], when non-null, gates the masked→visible transition: the
 * eye tap awaits it and only reveals if it returns true (e.g. a biometric
 * prompt for a stored secret, #274). Hiding is always free; when null the eye
 * toggles directly as before.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    textStyle: TextStyle = LocalTextStyle.current,
    onRevealRequest: (suspend () -> Boolean)? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PrivateKeyboard {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = label?.let { { androidx.compose.material3.Text(it) } },
            placeholder = placeholder?.let { { androidx.compose.material3.Text(it) } },
            supportingText = supportingText?.let { { androidx.compose.material3.Text(it) } },
            textStyle = textStyle,
            singleLine = true,
            enabled = enabled,
            isError = isError,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = if (visible) KeyboardType.PasswordVisible else KeyboardType.Password,
                imeAction = imeAction,
                platformImeOptions = PlatformImeOptions("flagNoPersonalizedLearning"),
            ),
            keyboardActions = remember(onImeAction) {
                KeyboardActions(
                    onDone = { onImeAction?.invoke() },
                    onGo = { onImeAction?.invoke() },
                    onNext = { onImeAction?.invoke() },
                    onSend = { onImeAction?.invoke() },
                    onSearch = { onImeAction?.invoke() },
                )
            },
            trailingIcon = {
                IconButton(onClick = {
                    when {
                        visible -> visible = false
                        onRevealRequest == null -> visible = true
                        else -> scope.launch { if (onRevealRequest()) visible = true }
                    }
                }) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Hide password" else "Show password",
                    )
                }
            },
            modifier = modifier,
        )
    }
}
