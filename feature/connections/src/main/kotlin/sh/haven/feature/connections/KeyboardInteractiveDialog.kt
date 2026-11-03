package sh.haven.feature.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import sh.haven.core.ssh.KeyboardInteractiveChallenge
import sh.haven.core.ssh.extractAuthBannerUrls
import sh.haven.core.ui.PasswordField

/**
 * Collect responses for a server-issued keyboard-interactive round.
 * Renders one field per prompt, masking the fields the server marked
 * as no-echo (passwords, TOTP codes). Submitting returns the responses
 * to JSch via [ConnectionsViewModel.submitKeyboardInteractiveResponses];
 * dismissing cancels the auth attempt.
 *
 * The dialog is not auto-dismissible — the SSH IO thread is blocked
 * waiting for a response, and the user should consciously either
 * answer or cancel. See #100.
 */
@Composable
fun KeyboardInteractiveDialog(
    challenge: KeyboardInteractiveChallenge,
    onSubmit: (List<String>) -> Unit,
    onCancel: () -> Unit,
    bannerMessage: String? = null,
) {
    val responses = remember(challenge) {
        android.util.Log.d(
            "HavenKI",
            "Dialog: remember(challenge) → NEW responses list for prompts=${challenge.prompts.size} first='${challenge.prompts.firstOrNull()?.text}'",
        )
        // Pre-fill from challenge.prefilled where present (#178 confirm-OTP
        // path supplies the generated code); blank otherwise.
        mutableStateListOf<String>().apply {
            addAll(List(challenge.prompts.size) { i -> challenge.prefilled.getOrNull(i) ?: "" })
        }
    }

    DisposableEffect(challenge) {
        android.util.Log.d("HavenKI", "Dialog: ENTER challenge prompts=${challenge.prompts.size} first='${challenge.prompts.firstOrNull()?.text}'")
        onDispose {
            android.util.Log.d("HavenKI", "Dialog: LEAVE challenge first='${challenge.prompts.firstOrNull()?.text}'")
        }
    }

    val submit: () -> Unit = {
        android.util.Log.d(
            "HavenKI",
            "Dialog: submit tapped, responses=${responses.toList().map { it.length }}",
        )
        onSubmit(responses.toList())
    }

    val titleAdditional = stringResource(R.string.connections_ki_title_additional)
    val titleServer = stringResource(R.string.connections_ki_title_server)
    val title = when {
        challenge.name.isNotBlank() -> challenge.name
        challenge.prompts.any { !it.echo } -> titleAdditional
        else -> titleServer
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (challenge.destination.isNotBlank()) {
                    Text(
                        challenge.destination,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (!bannerMessage.isNullOrBlank()) {
                    BannerTextWithLinks(text = bannerMessage)
                    Spacer(Modifier.height(12.dp))
                }
                if (challenge.instruction.isNotBlank()) {
                    BannerTextWithLinks(text = challenge.instruction)
                    Spacer(Modifier.height(12.dp))
                }

                challenge.prompts.forEachIndexed { i, prompt ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    val isLast = i == challenge.prompts.lastIndex
                    val imeAction = if (isLast) ImeAction.Go else ImeAction.Next
                    if (prompt.echo) {
                        OutlinedTextField(
                            value = responses[i],
                            onValueChange = { responses[i] = it },
                            label = { Text(prompt.text.ifBlank { stringResource(R.string.connections_ki_response_fallback) }) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = imeAction,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        PasswordField(
                            value = responses[i],
                            onValueChange = { responses[i] = it },
                            label = prompt.text.ifBlank { stringResource(R.string.connections_ki_response_fallback) },
                            imeAction = imeAction,
                            onImeAction = if (isLast) submit else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit) { Text(stringResource(R.string.connections_ki_submit)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Displays an `SSH_MSG_USERAUTH_BANNER` while an SSH connection is in progress
 * (#781, e.g. Tailscale SSH `check` mode). Any `http://` or `https://` URLs in
 * the banner are clickable inline and via the primary action button so the user
 * can open the approval page in their browser while JSch waits for
 * `SSH_MSG_USERAUTH_SUCCESS`. Tapping Cancel aborts the in-flight connect.
 */
@Composable
fun SshAuthBannerDialog(
    banner: ConnectionsViewModel.PendingSshAuthBanner,
    onCancel: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val primaryUrl = banner.urls.firstOrNull()

    AlertDialog(
        onDismissRequest = { /* Keep visible while connect waits; explicit Cancel aborts (#781) */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(R.string.connections_auth_banner_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (banner.destination.isNotBlank()) {
                    Text(
                        banner.destination,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                BannerTextWithLinks(
                    text = banner.message,
                    monospace = true,
                )
            }
        },
        confirmButton = {
            if (primaryUrl != null) {
                TextButton(onClick = { runCatching { uriHandler.openUri(primaryUrl) } }) {
                    Text(stringResource(R.string.connections_auth_banner_open_link))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
internal fun BannerTextWithLinks(
    text: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val urls = remember(text) { extractAuthBannerUrls(text) }
    val annotated = remember(text, urls, linkColor) {
        buildAnnotatedString {
            append(text)
            val linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                ),
            )
            for (url in urls) {
                var searchFrom = 0
                while (searchFrom < text.length) {
                    val idx = text.indexOf(url, startIndex = searchFrom)
                    if (idx < 0) break
                    val end = idx + url.length
                    addLink(
                        LinkAnnotation.Url(
                            url = url,
                            styles = linkStyles,
                            linkInteractionListener = {
                                runCatching { uriHandler.openUri(url) }
                            },
                        ),
                        start = idx,
                        end = end,
                    )
                    searchFrom = end
                }
            }
        }
    }
    SelectionContainer(modifier = modifier) {
        Text(
            text = annotated,
            style = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
    }
}
