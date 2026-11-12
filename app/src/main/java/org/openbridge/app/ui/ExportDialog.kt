package org.openbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.ExportDestination
import org.openbridge.app.domain.ExportDialogDraft
import org.openbridge.app.domain.ExportEffects
import org.openbridge.app.domain.ExportFormat
import org.openbridge.app.domain.ExportOptions
import org.openbridge.app.domain.ExportPreferences
import org.openbridge.app.domain.FrameSnapshot
import org.openbridge.app.domain.FrameTemplate
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PhotoEditTab
import org.openbridge.app.domain.defaultExportEffects
import org.openbridge.app.domain.supportsRendering
import org.openbridge.app.domain.canEdit

@Composable
fun ExportDialog(
    assets: List<PhotoAsset>, look: ColorLookSettings, frame: FrameTemplate?, preferences: ExportPreferences,
    onChange: (PhotoEditTab, ExportDialogDraft) -> Unit, onExport: (ExportOptions) -> Unit, onDismiss: () -> Unit,
    initial: ExportDialogDraft? = null
) {
    val defaults = defaultExportEffects(look, frame, assets)
    val editable = assets.isNotEmpty() && assets.all { it.canEdit }
    val output = initial?.preferences ?: preferences
    var colorOverride by rememberSaveable { mutableStateOf(initial?.color) }
    var frameOverride by rememberSaveable { mutableStateOf(initial?.frame) }
    var format by rememberSaveable { mutableStateOf(output.format) }
    val original = format == ExportFormat.ORIGINAL
    val effects = ExportEffects(colorOverride ?: defaults.color, frameOverride ?: defaults.frame).forFormat(format)
    val applyLook = effects.color
    val applyFrame = effects.frame
    var destination by rememberSaveable { mutableStateOf(output.destination) }
    var quality by rememberSaveable { mutableStateOf(output.jpegQuality.toFloat()) }
    var maxEdge by rememberSaveable { mutableStateOf(output.maxEdge) }
    fun change(tab: PhotoEditTab) {
        onChange(tab, ExportDialogDraft(ExportPreferences(format, quality.toInt(), maxEdge, destination), colorOverride, frameOverride))
    }
    val unavailable = look.usage.unavailableReason
    val compatible = !look.usesLog || assets.all { it.format == MediaFormat.RAW }
    val unsupported = assets.count { !it.format.supportsRendering }
    val requiresRaw = applyLook && !compatible
    BridgeSheet(title = if (assets.size == 1) "导出照片" else "导出 ${assets.size} 张照片", onDismiss = onDismiss, compact = true,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.weight(1f))
            PrimaryAction(if (destination == ExportDestination.GALLERY) "导出到相册" else "选择文件夹",
                enabled = assets.isNotEmpty() && (original || unsupported == 0) && !requiresRaw && (!applyLook || unavailable == null),
                onClick = {
                    onExport(ExportOptions(format, quality.toInt(), maxEdge, look.takeIf { applyLook && !it.isIdentity },
                        destination, frame?.takeIf { applyFrame }?.let(::FrameSnapshot)))
                    onDismiss()
                }, glyph = BridgeGlyph.SHARE)
        }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (assets.size == 1) Text(assets.single().name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("调色 · " + if (look.isIdentity) "原图" else look.name, Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Switch(checked = applyLook && !look.isIdentity, enabled = !original && !look.isIdentity && unavailable == null,
                    onCheckedChange = { colorOverride = it })
                TextButton(onClick = { change(PhotoEditTab.COLOR) }, enabled = editable) { Text("更改") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("边框 · " + (frame?.name ?: "无"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                Switch(checked = applyFrame && frame != null, enabled = !original && frame != null, onCheckedChange = { frameOverride = it })
                TextButton(onClick = { change(PhotoEditTab.FRAME) }, enabled = editable) { Text("更改") }
            }
            OptionRow("格式", ExportFormat.entries, format, { it.label() }) { format = it }
            if (original) Text("原件复制不应用调色和边框", style = MaterialTheme.typography.bodySmall)
            else OptionRow("长边", listOf(2048, 4096, 0), maxEdge, { if (it == 0) "原尺寸" else "$it px" }) { maxEdge = it }
            if (format == ExportFormat.JPEG) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("质量 ${quality.toInt()}", Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge)
                Slider(value = quality, onValueChange = { quality = it }, valueRange = 1f..100f, modifier = Modifier.weight(1f))
            }
            OptionRow("保存到", ExportDestination.entries, destination, {
                if (it == ExportDestination.GALLERY) "系统相册" else "文件夹"
            }) { destination = it }
            if (!original && unsupported > 0) Text("$unsupported 张照片暂不支持转换，请选择原件复制。", color = MaterialTheme.colorScheme.error)
            if (requiresRaw) Text("Log 输入需要 RAW 原片", color = MaterialTheme.colorScheme.error)
            if (applyLook && unavailable != null) Text(unavailable, color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> OptionRow(title: String, values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            values.forEachIndexed { index, value ->
                SegmentedButton(selected = value == selected, onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, values.size)) { Text(label(value), maxLines = 1) }
            }
        }
    }
}

internal fun ExportFormat.label(): String = when (this) {
    ExportFormat.ORIGINAL -> "原件复制"
    ExportFormat.JPEG -> "JPEG"
    ExportFormat.PNG -> "PNG"
}
