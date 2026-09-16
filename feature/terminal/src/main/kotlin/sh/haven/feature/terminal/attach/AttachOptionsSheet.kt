package sh.haven.feature.terminal.attach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.TextFields
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
import sh.haven.feature.terminal.R

/**
 * Options surfaced by the paperclip key on the keyboard toolbar.
 *
 * Each entry is a distinct action the screen handles — file upload via SAF,
 * or one of four image-recognition flows. Keeping it as an enum means the
 * screen's `when` is exhaustive at compile time, so adding a new option here
 * surfaces every missing call site.
 */
enum class AttachOption {
    SEND_FILE,
    TAKE_PHOTO,
    SCAN_CAMERA,
    SCAN_GALLERY,
    OCR_CAMERA,
    OCR_GALLERY,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachOptionsSheet(
    onDismiss: () -> Unit,
    onSelect: (AttachOption) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.terminal_attach_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )

            SheetRow(
                icon = Icons.Filled.AttachFile,
                label = R.string.terminal_attach_send_file,
                description = R.string.terminal_attach_send_file_desc,
                onClick = { onSelect(AttachOption.SEND_FILE) },
            )
            SheetRow(
                icon = Icons.Filled.Photo,
                label = R.string.terminal_attach_take_photo,
                description = R.string.terminal_attach_take_photo_desc,
                onClick = { onSelect(AttachOption.TAKE_PHOTO) },
            )
            SheetRow(
                icon = Icons.Filled.QrCodeScanner,
                label = R.string.terminal_attach_scan_camera,
                description = R.string.terminal_attach_scan_camera_desc,
                onClick = { onSelect(AttachOption.SCAN_CAMERA) },
            )
            SheetRow(
                icon = Icons.Filled.PhotoLibrary,
                label = R.string.terminal_attach_scan_gallery,
                description = R.string.terminal_attach_scan_gallery_desc,
                onClick = { onSelect(AttachOption.SCAN_GALLERY) },
            )
            SheetRow(
                icon = Icons.Filled.PhotoCamera,
                label = R.string.terminal_attach_ocr_camera,
                description = R.string.terminal_attach_ocr_camera_desc,
                onClick = { onSelect(AttachOption.OCR_CAMERA) },
            )
            SheetRow(
                icon = Icons.Filled.TextFields,
                label = R.string.terminal_attach_ocr_gallery,
                description = R.string.terminal_attach_ocr_gallery_desc,
                onClick = { onSelect(AttachOption.OCR_GALLERY) },
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
