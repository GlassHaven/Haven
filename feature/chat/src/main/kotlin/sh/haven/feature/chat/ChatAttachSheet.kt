package sh.haven.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Options surfaced by the attach key in the chat composer: gallery picker or
 * camera capture, both feeding [ChatViewModel.stageImage]. Mirrors the
 * terminal attach sheet's enum pattern so the screen's `when` stays
 * exhaustive.
 */
enum class ChatAttachOption { GALLERY, CAMERA }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAttachSheet(
    onDismiss: () -> Unit,
    onSelect: (ChatAttachOption) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.chat_attach_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )

            SheetRow(
                icon = Icons.Filled.PhotoLibrary,
                label = R.string.chat_attach_gallery,
                description = R.string.chat_attach_gallery_desc,
                onClick = { onSelect(ChatAttachOption.GALLERY) },
            )
            SheetRow(
                icon = Icons.Filled.PhotoCamera,
                label = R.string.chat_attach_camera,
                description = R.string.chat_attach_camera_desc,
                onClick = { onSelect(ChatAttachOption.CAMERA) },
            )
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    label: Int,
    description: Int,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = {
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}