package sh.haven.feature.sftp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.haven.core.ffmpeg.AudioFilter
import sh.haven.core.ffmpeg.FilterPresets
import sh.haven.core.ffmpeg.VideoFilter
import kotlin.math.roundToInt

/**
 * Filter configuration state for the Convert dialog.
 */
class FilterState {
    // Presets
    var activePresets by mutableStateOf(setOf<String>())

    // Video adjustments (non-default = active)
    var brightness by mutableFloatStateOf(0f)
    var contrast by mutableFloatStateOf(1f)
    var saturation by mutableFloatStateOf(1f)
    var gamma by mutableFloatStateOf(1f)
    var sharpen by mutableFloatStateOf(0f)
    var denoise by mutableFloatStateOf(0f)
    var stabilize by mutableStateOf(false)
    var autoColor by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    var rotation by mutableStateOf(0) // 0=none, 1=90CW, 2=180, 3=90CCW

    // Audio adjustments
    var volume by mutableFloatStateOf(0f)
    var normalizeAudio by mutableStateOf(false)

    fun buildVideoFilters(): List<VideoFilter> = buildList {
        if (stabilize) add(VideoFilter.Stabilize())
        if (autoColor) add(VideoFilter.AutoColor)
        if (brightness != 0f) add(VideoFilter.Brightness(brightness))
        if (contrast != 1f) add(VideoFilter.Contrast(contrast))
        if (saturation != 1f) add(VideoFilter.Saturation(saturation))
        if (gamma != 1f) add(VideoFilter.Gamma(gamma))
        if (sharpen != 0f) add(VideoFilter.Sharpen(sharpen))
        if (denoise > 0f) add(VideoFilter.Denoise(denoise.roundToInt()))
        if (speed != 1f) add(VideoFilter.Speed(speed))
        when (rotation) {
            1 -> add(VideoFilter.Transpose(1))   // 90 CW
            2 -> { add(VideoFilter.Transpose(1)); add(VideoFilter.Transpose(1)) } // 180
            3 -> add(VideoFilter.Transpose(0))   // 90 CCW
        }
    }

    fun buildAudioFilters(): List<AudioFilter> = buildList {
        if (normalizeAudio) add(AudioFilter.Normalize)
        if (volume != 0f) add(AudioFilter.Volume(volume))
        if (speed != 1f) add(AudioFilter.Speed(speed))
    }

    /** Reset manual color adjustments to defaults (used when auto color is enabled). */
    fun resetColorSliders() {
        brightness = 0f; contrast = 1f; saturation = 1f; gamma = 1f
    }

    fun hasFilters(): Boolean =
        brightness != 0f || contrast != 1f || saturation != 1f || gamma != 1f ||
            sharpen != 0f || denoise > 0f || stabilize || autoColor ||
            speed != 1f || rotation != 0 || volume != 0f || normalizeAudio

    /** Human-readable preview of the filter chain. */
    fun preview(): String {
        val vf = buildVideoFilters()
        val af = buildAudioFilters()
        return buildString {
            if (vf.isNotEmpty()) append("-vf \"${VideoFilter.chain(vf)}\"")
            if (vf.isNotEmpty() && af.isNotEmpty()) append("  ")
            if (af.isNotEmpty()) append("-af \"${AudioFilter.chain(af)}\"")
        }.ifEmpty { "No filters" }
    }

    companion object {
        /** Saver for rememberSaveable — survives configuration changes (rotation). */
        val Saver: Saver<FilterState, *> = listSaver(
            save = { listOf(
                it.brightness, it.contrast, it.saturation, it.gamma,
                it.sharpen, it.denoise, it.speed,
                if (it.stabilize) 1f else 0f,
                if (it.autoColor) 1f else 0f,
                it.rotation.toFloat(),
                it.volume,
                if (it.normalizeAudio) 1f else 0f,
            ) },
            restore = { list ->
                FilterState().apply {
                    brightness = list[0]; contrast = list[1]
                    saturation = list[2]; gamma = list[3]
                    sharpen = list[4]; denoise = list[5]
                    speed = list[6]
                    stabilize = list[7] != 0f; autoColor = list[8] != 0f
                    rotation = list[9].toInt()
                    volume = list[10]; normalizeAudio = list[11] != 0f
                }
            },
        )
    }
}

/**
 * Collapsible filter section for the Convert dialog.
 */
@Composable
fun FilterSection(state: FilterState, isAudioOnly: Boolean = false, onFilterChanged: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    var showVideo by remember { mutableStateOf(true) }
    var showAudio by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header toggle
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            Spacer(Modifier.width(4.dp))
            Text("Filters")
            if (state.hasFilters()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "(active)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (!expanded) return@Column

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        ) {
            // Quick presets
            Text("Quick presets", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilterPresets.all.forEach { preset ->
                    if (isAudioOnly && preset.videoFilters.isNotEmpty()) return@forEach
                    FilterChip(
                        selected = preset.key in state.activePresets,
                        onClick = {
                            val newPresets = state.activePresets.toMutableSet()
                            if (preset.key in newPresets) {
                                newPresets.remove(preset.key)
                                // Undo preset effects
                                when (preset.key) {
                                    "stabilize" -> state.stabilize = false
                                    "fix_colors" -> state.autoColor = false
                                    "enhance" -> { state.autoColor = false; state.sharpen = 0f }
                                    "normalize_audio" -> state.normalizeAudio = false
                                }
                            } else {
                                newPresets.add(preset.key)
                                when (preset.key) {
                                    "stabilize" -> state.stabilize = true
                                    "fix_colors" -> { state.autoColor = true; state.resetColorSliders() }
                                    "enhance" -> { state.autoColor = true; state.resetColorSliders(); state.sharpen = 0.5f }
                                    "normalize_audio" -> state.normalizeAudio = true
                                }
                            }
                            state.activePresets = newPresets
                            onFilterChanged()
                        },
                        label = { Text(preset.label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            // Video section
            if (!isAudioOnly) {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Video", showVideo) { showVideo = it }
                if (showVideo) {
                    ToggleRow("Stabilize (deshake)", state.stabilize) { state.stabilize = it; onFilterChanged() }
                    ToggleRow("Auto color correction", state.autoColor) {
                        state.autoColor = it
                        if (it) state.resetColorSliders()
                        onFilterChanged()
                    }
                    LabeledSlider("Brightness", state.brightness, -1f..1f) { state.brightness = it; onFilterChanged() }
                    LabeledSlider("Contrast", state.contrast, 0f..3f) { state.contrast = it; onFilterChanged() }
                    LabeledSlider("Saturation", state.saturation, 0f..3f) { state.saturation = it; onFilterChanged() }
                    LabeledSlider("Gamma", state.gamma, 0.1f..5f) { state.gamma = it; onFilterChanged() }
                    LabeledSlider("Sharpen", state.sharpen, -1.5f..3f) { state.sharpen = it; onFilterChanged() }
                    LabeledSlider("Denoise", state.denoise, 0f..20f) { state.denoise = it; onFilterChanged() }
                    LabeledSlider("Speed", state.speed, 0.25f..4f) { state.speed = it; onFilterChanged() }
                    RotationPicker(state.rotation) { state.rotation = it; onFilterChanged() }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }

            // Audio section
            Spacer(Modifier.height(8.dp))
            SectionHeader("Audio", showAudio) { showAudio = it }
            if (showAudio) {
                ToggleRow("Normalize loudness (EBU R128)", state.normalizeAudio) { state.normalizeAudio = it; onFilterChanged() }
                LabeledSlider("Volume (dB)", state.volume, -20f..20f) { state.volume = it; onFilterChanged() }
                if (!isAudioOnly) {
                    Text(
                        "Audio speed synced with video speed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    )
                }
            }

            // Preview
            if (state.hasFilters()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text(
                    state.preview(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!expanded) }
            .padding(vertical = 4.dp),
    ) {
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            null,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp, horizontal = 8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                String.format("%.2f", value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RotationPicker(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf("None" to 0, "90\u00B0" to 1, "180\u00B0" to 2, "270\u00B0" to 3)
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text("Rotation", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            options.forEach { (label, value) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}
