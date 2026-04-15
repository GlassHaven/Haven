package sh.haven.feature.sftp

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// ── Bottom sheet opening the media action picker ────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaActionsSheet(
    entry: SftpEntry,
    onDismiss: () -> Unit,
    onMediaInfo: () -> Unit,
    onTrim: () -> Unit,
    onExtractAudio: () -> Unit,
    onContactSheet: () -> Unit,
    onConvert: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Divider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.sftp_media_info)) },
            supportingContent = { Text(stringResource(R.string.sftp_media_info_sub)) },
            leadingContent = { Icon(Icons.Filled.Info, null) },
            modifier = Modifier.clickable { onMediaInfo(); onDismiss() },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.sftp_trim)) },
            supportingContent = { Text(stringResource(R.string.sftp_trim_sub)) },
            leadingContent = { Icon(Icons.Filled.ContentCut, null) },
            modifier = Modifier.clickable { onTrim(); onDismiss() },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.sftp_extract_audio)) },
            supportingContent = { Text(stringResource(R.string.sftp_extract_audio_sub)) },
            leadingContent = { Icon(Icons.Filled.MusicNote, null) },
            modifier = Modifier.clickable { onExtractAudio(); onDismiss() },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.sftp_contact_sheet)) },
            supportingContent = { Text(stringResource(R.string.sftp_contact_sheet_sub)) },
            leadingContent = { Icon(Icons.Filled.GridOn, null) },
            modifier = Modifier.clickable { onContactSheet(); onDismiss() },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.sftp_convert)) },
            supportingContent = { Text(stringResource(R.string.sftp_convert_sub)) },
            leadingContent = { Icon(Icons.Filled.Transform, null) },
            modifier = Modifier.clickable { onConvert(); onDismiss() },
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ── Media info dialog ───────────────────────────────────────────────────

@Composable
internal fun MediaInfoDialog(
    state: SftpViewModel.MediaInfoState,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (state) {
                    is SftpViewModel.MediaInfoState.Loading -> state.entry.name
                    is SftpViewModel.MediaInfoState.Loaded -> state.entry.name
                    is SftpViewModel.MediaInfoState.Failed -> state.entry.name
                },
                maxLines = 2,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (state) {
                    is SftpViewModel.MediaInfoState.Loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(12.dp))
                            Text(stringResource(R.string.sftp_media_info_probing))
                        }
                    }
                    is SftpViewModel.MediaInfoState.Failed -> {
                        Text(
                            stringResource(R.string.sftp_media_info_failed, state.reason),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is SftpViewModel.MediaInfoState.Loaded -> {
                        val info = state.info
                        MediaInfoRow(stringResource(R.string.sftp_media_info_container), info.formatName ?: "?")
                        info.durationSeconds?.let {
                            MediaInfoRow(stringResource(R.string.sftp_media_info_duration), formatDuration(it))
                        }
                        info.bitrateBps?.let {
                            MediaInfoRow(
                                stringResource(R.string.sftp_media_info_bitrate),
                                "${it / 1000} kbps",
                            )
                        }
                        info.sizeBytes?.let {
                            MediaInfoRow(
                                stringResource(R.string.sftp_media_info_size),
                                Formatter.formatFileSize(context, it),
                            )
                        }
                        if (info.videoStreams.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.sftp_media_info_video_streams),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            info.videoStreams.forEachIndexed { i, v ->
                                val tag = if (v.isAttachedPic) " (cover art)" else ""
                                MediaInfoRow(
                                    "${stringResource(R.string.sftp_media_info_video)} $i$tag",
                                    buildString {
                                        append(v.codec ?: "?")
                                        if (v.width != null && v.height != null) append("  ${v.width}×${v.height}")
                                        v.frameRate?.let { append("  %.2f fps".format(it)) }
                                        v.bitrateBps?.let { append("  ${it / 1000}k") }
                                    },
                                )
                            }
                        }
                        if (info.audioStreams.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.sftp_media_info_audio_streams),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            info.audioStreams.forEachIndexed { i, a ->
                                MediaInfoRow(
                                    "${stringResource(R.string.sftp_media_info_audio)} $i",
                                    buildString {
                                        append(a.codec ?: "?")
                                        a.channels?.let { append("  ${it}ch") }
                                        a.sampleRate?.let { append("  ${it / 1000} kHz") }
                                        a.bitrateBps?.let { append("  ${it / 1000}k") }
                                        a.language?.let { append("  [$it]") }
                                    },
                                )
                            }
                        }
                        if (info.subtitleStreams.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.sftp_media_info_subtitle_streams),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            info.subtitleStreams.forEachIndexed { i, s ->
                                MediaInfoRow(
                                    "${stringResource(R.string.sftp_media_info_subtitle)} $i",
                                    "${s.codec ?: "?"}${s.language?.let { "  [$it]" } ?: ""}${s.title?.let { "  $it" } ?: ""}",
                                )
                            }
                        }
                        if (info.chapters.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.sftp_media_info_chapters, info.chapters.size),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = {
            if (state is SftpViewModel.MediaInfoState.Loaded) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(state.info.rawJson))
                }) { Text(stringResource(R.string.sftp_media_info_copy)) }
            }
        },
    )
}

@Composable
private fun MediaInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.size(width = 96.dp, height = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ── Trim dialog ─────────────────────────────────────────────────────────

@Composable
internal fun TrimDialog(
    entry: SftpEntry,
    onDismiss: () -> Unit,
    onConfirm: (startSec: Double, endSec: Double, outName: String) -> Unit,
) {
    var startText by remember { mutableStateOf("00:00:00") }
    var endText by remember { mutableStateOf("00:00:10") }
    val defaultOut = remember(entry) {
        val dot = entry.name.lastIndexOf('.')
        if (dot > 0) entry.name.substring(0, dot) + "_trim" + entry.name.substring(dot)
        else entry.name + "_trim"
    }
    var outName by remember { mutableStateOf(defaultOut) }

    val startSec = parseHms(startText)
    val endSec = parseHms(endText)
    val valid = startSec != null && endSec != null && endSec > startSec && outName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sftp_trim_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sftp_trim_explain),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text(stringResource(R.string.sftp_trim_start)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = startSec == null,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text(stringResource(R.string.sftp_trim_end)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = endSec == null || (startSec != null && endSec != null && endSec <= startSec),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = outName,
                    onValueChange = { outName = it },
                    label = { Text(stringResource(R.string.sftp_output_filename)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(startSec!!, endSec!!, outName) },
            ) { Text(stringResource(R.string.sftp_trim_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Parse "H:MM:SS[.ms]", "MM:SS", or "SS" into seconds, returning null on failure. */
internal fun parseHms(text: String): Double? {
    if (text.isBlank()) return null
    val parts = text.trim().split(":")
    return try {
        when (parts.size) {
            1 -> parts[0].toDouble()
            2 -> parts[0].toLong() * 60 + parts[1].toDouble()
            3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble()
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}

// ── Extract audio dialog ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtractAudioDialog(
    entry: SftpEntry,
    onDismiss: () -> Unit,
    onConfirm: (codec: String, bitrate: String, outName: String) -> Unit,
) {
    data class Format(val label: String, val codec: String, val ext: String, val usesBitrate: Boolean)
    val formats = remember {
        listOf(
            Format("MP3", "libmp3lame", "mp3", true),
            Format("AAC (m4a)", "aac", "m4a", true),
            Format("Opus", "libopus", "opus", true),
            Format("FLAC", "flac", "flac", false),
        )
    }
    var formatIndex by remember { mutableStateOf(0) }
    var bitrateKbps by remember { mutableStateOf(192f) }
    var menuExpanded by remember { mutableStateOf(false) }
    val format = formats[formatIndex]

    val baseName = remember(entry) {
        val dot = entry.name.lastIndexOf('.')
        if (dot > 0) entry.name.substring(0, dot) else entry.name
    }
    var outName by remember(formatIndex) { mutableStateOf("${baseName}_audio.${format.ext}") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sftp_extract_audio_title)) },
        text = {
            Column {
                Box {
                    OutlinedTextField(
                        readOnly = true,
                        value = format.label,
                        onValueChange = {},
                        label = { Text(stringResource(R.string.sftp_extract_audio_format)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { menuExpanded = true },
                        enabled = false,
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        formats.forEachIndexed { i, f ->
                            DropdownMenuItem(
                                text = { Text(f.label) },
                                onClick = {
                                    formatIndex = i
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (format.usesBitrate) {
                    Text(stringResource(R.string.sftp_extract_audio_bitrate, bitrateKbps.toInt()))
                    Slider(
                        value = bitrateKbps,
                        onValueChange = { bitrateKbps = it },
                        valueRange = 64f..320f,
                        steps = ((320 - 64) / 16) - 1,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = outName,
                    onValueChange = { outName = it },
                    label = { Text(stringResource(R.string.sftp_output_filename)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = outName.isNotBlank(),
                onClick = {
                    val br = "${(bitrateKbps.toInt() / 16) * 16}k"
                    onConfirm(format.codec, br, outName)
                },
            ) { Text(stringResource(R.string.sftp_extract_audio_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

// ── Contact sheet dialog ────────────────────────────────────────────────

@Composable
internal fun ContactSheetDialog(
    entry: SftpEntry,
    onDismiss: () -> Unit,
    onConfirm: (cols: Int, rows: Int, tileW: Int, tileH: Int, outName: String) -> Unit,
) {
    var cols by remember { mutableStateOf(4) }
    var rows by remember { mutableStateOf(4) }
    var tileW by remember { mutableStateOf(320) }
    var tileH by remember { mutableStateOf(180) }
    val baseName = remember(entry) {
        val dot = entry.name.lastIndexOf('.')
        if (dot > 0) entry.name.substring(0, dot) else entry.name
    }
    var outName by remember { mutableStateOf("${baseName}_sheet.png") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sftp_contact_sheet_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sftp_contact_sheet_explain),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                SteppedRow(
                    label = stringResource(R.string.sftp_contact_sheet_cols),
                    value = cols,
                    onChange = { cols = it },
                    range = 1..8,
                )
                SteppedRow(
                    label = stringResource(R.string.sftp_contact_sheet_rows),
                    value = rows,
                    onChange = { rows = it },
                    range = 1..8,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.sftp_contact_sheet_tile, tileW, tileH))
                Slider(
                    value = tileW.toFloat(),
                    onValueChange = {
                        tileW = it.toInt().coerceIn(160, 640)
                        tileH = (tileW * 9 / 16).coerceAtLeast(90)
                    },
                    valueRange = 160f..640f,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = outName,
                    onValueChange = { outName = it },
                    label = { Text(stringResource(R.string.sftp_output_filename)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = outName.isNotBlank(),
                onClick = { onConfirm(cols, rows, tileW, tileH, outName) },
            ) { Text(stringResource(R.string.sftp_contact_sheet_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun SteppedRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(
            enabled = value > range.first,
            onClick = { onChange((value - 1).coerceIn(range)) },
        ) { Text("-") }
        Box(modifier = Modifier.size(width = 32.dp, height = 24.dp), contentAlignment = Alignment.Center) {
            Text(value.toString())
        }
        IconButton(
            enabled = value < range.last,
            onClick = { onChange((value + 1).coerceIn(range)) },
        ) { Text("+") }
    }
}
