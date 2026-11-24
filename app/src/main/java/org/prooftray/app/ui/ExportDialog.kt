package org.prooftray.app.ui

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
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.ExportDestination
import org.prooftray.app.domain.ExportDialogDraft
import org.prooftray.app.domain.ExportEffects
import org.prooftray.app.domain.ExportFormat
import org.prooftray.app.domain.ExportOptions
import org.prooftray.app.domain.ExportPreferences
import org.prooftray.app.domain.FrameSnapshot
import org.prooftray.app.domain.FrameTemplate
import org.prooftray.app.domain.LogExportSettings
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.PhotoEditTab
import org.prooftray.app.domain.defaultExportEffects
import org.prooftray.app.domain.supportsRendering
import org.prooftray.app.domain.canEdit

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
    val log = format == ExportFormat.TIFF
    var photoFormat by rememberSaveable { mutableStateOf(output.format.takeIf { it in photoFormats } ?: ExportFormat.JPEG) }
    var logEncoding by rememberSaveable {
        mutableStateOf(look.usage.input?.takeIf { it in LogExportSettings.encodings && output.format != ExportFormat.TIFF }?.name
            ?: output.logEncoding)
    }
    var logGain by rememberSaveable { mutableStateOf(initial?.logGainEv ?: if (look.usesLog) look.logGainEv else 0f) }
    val effects = ExportEffects(colorOverride ?: defaults.color, frameOverride ?: defaults.frame).forFormat(format)
    val applyLook = effects.color
    val applyFrame = effects.frame
    var destination by rememberSaveable { mutableStateOf(output.destination) }
    val target = if (log) ExportDestination.DIRECTORY else destination
    var quality by rememberSaveable { mutableStateOf(output.jpegQuality.toFloat()) }
    var maxEdge by rememberSaveable { mutableStateOf(output.maxEdge) }
    fun change(tab: PhotoEditTab) {
        onChange(tab, ExportDialogDraft(ExportPreferences(format, quality.toInt(), maxEdge, target, logEncoding),
            colorOverride, frameOverride, logGain))
    }
    val unavailable = look.usage.unavailableReason
    val compatible = !look.usesLog || assets.all { it.format == MediaFormat.RAW }
    val unsupported = assets.count { !it.format.supportsRendering }
    val requiresRaw = applyLook && !compatible
    val logCompatible = assets.all { it.format == MediaFormat.RAW }
    AppSheet(title = if (assets.size == 1) "导出照片" else "导出 ${assets.size} 张照片", onDismiss = onDismiss, compact = true,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.weight(1f))
            PrimaryAction(if (target == ExportDestination.GALLERY) "导出到相册" else "选择文件夹",
                enabled = assets.isNotEmpty() && (original || unsupported == 0) && (!log || logCompatible) &&
                    !requiresRaw && (!applyLook || unavailable == null),
                onClick = {
                    onExport(ExportOptions(format, quality.toInt(), maxEdge, look.takeIf { applyLook && !it.isIdentity },
                        target, frame?.takeIf { applyFrame }?.let(::FrameSnapshot),
                        if (log) LogExportSettings(logEncoding, look.exposure, look.warmth, logGain, look.tint) else null))
                    onDismiss()
                }, glyph = AppGlyph.SHARE)
        }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (assets.size == 1) Text(assets.single().name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val kind = when { log -> ExportKind.LOG; original -> ExportKind.ORIGINAL; else -> ExportKind.PHOTO }
            OptionRow("导出", ExportKind.entries, kind, { it.label }) { selected ->
                format = when (selected) {
                    ExportKind.PHOTO -> photoFormat
                    ExportKind.ORIGINAL -> ExportFormat.ORIGINAL
                    ExportKind.LOG -> ExportFormat.TIFF
                }
                if (selected == ExportKind.LOG && !log) maxEdge = 0
            }
            if (log) {
                LogExportControls(logEncoding, logGain, look, { logEncoding = it }, { logGain = it },
                    onEdit = { change(PhotoEditTab.COLOR) }, canEdit = editable && logCompatible)
            } else {
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
            }
            if (!original && !log) OptionRow("格式", photoFormats, format, { it.label() }) { format = it; photoFormat = it }
            if (log) Text("16 位 TIFF · 全范围", style = MaterialTheme.typography.bodySmall)
            if (original) Text("原件复制不应用调色和边框", style = MaterialTheme.typography.bodySmall)
            else OptionRow("长边", listOf(2048, 4096, 0), maxEdge, { if (it == 0) "原尺寸" else "$it px" }) { maxEdge = it }
            if (format == ExportFormat.JPEG) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("质量 ${quality.toInt()}", Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge)
                Slider(value = quality, onValueChange = { quality = it }, valueRange = 1f..100f, modifier = Modifier.weight(1f))
            }
            if (log) Text("保存到文件夹，供支持 Log 的软件继续调色。", style = MaterialTheme.typography.bodySmall)
            else OptionRow("保存到", ExportDestination.entries, destination, {
                if (it == ExportDestination.GALLERY) "系统相册" else "文件夹"
            }) { destination = it }
            if (!original && unsupported > 0) Text("$unsupported 张照片暂不支持转换，请选择原件复制。", color = MaterialTheme.colorScheme.error)
            if (requiresRaw) Text("Log 输入需要 RAW 原片", color = MaterialTheme.colorScheme.error)
            if (log && !logCompatible) Text("Log 直出需要 RAW 原片，请只选择 RAW 照片。", color = MaterialTheme.colorScheme.error)
            if (applyLook && unavailable != null) Text(unavailable, color = MaterialTheme.colorScheme.error)
        }
    }
}

private enum class ExportKind(val label: String) { PHOTO("成片"), LOG("Log"), ORIGINAL("原件复制") }
private val photoFormats = listOf(ExportFormat.JPEG, ExportFormat.PNG)

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
    ExportFormat.TIFF -> "Log TIFF"
}
