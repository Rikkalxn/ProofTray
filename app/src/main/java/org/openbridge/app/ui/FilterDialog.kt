package org.openbridge.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.hasAdvancedConditions
import org.openbridge.app.domain.pickerDayMillis
import org.openbridge.app.domain.filterDayMillis
import org.openbridge.app.domain.FilterSpec
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.OrientationFilter
import org.openbridge.app.domain.PickState
import org.openbridge.app.domain.SortOrder
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

private const val MEBIBYTE = 1024.0 * 1024.0

@Composable
fun FilterDialog(initial: FilterSpec, onApply: (FilterSpec) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var fields by remember(initial) { mutableStateOf(initial.toFields()) }
    var advanced by remember(initial) { mutableStateOf(initial.hasAdvancedConditions()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(key: String, value: String) { fields = fields + (key to value); error = null }
    BridgeSheet(
        title = "筛选照片", onDismiss = onDismiss,
        actions = {
            TextButton(onClick = {
                draft = FilterSpec(sortOrder = initial.sortOrder); fields = draft.toFields(); advanced = false; error = null
            }) { Text("重置") }
            Spacer(Modifier.weight(1f))
            PrimaryAction("应用筛选", onClick = {
                runCatching { draft.withFields(fields) }.onSuccess { onApply(it); onDismiss() }
                    .onFailure { error = it.message ?: "请检查筛选范围" }
            })
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ChoiceRow("文件格式", MediaFormat.entries, draft.formats, { it.label() }) {
                    draft = draft.copy(formats = draft.formats.toggle(it))
                }
                Text("评分 ${draft.minimumRating} — ${draft.maximumRating} 星")
                RangeSlider(
                    value = draft.minimumRating.toFloat()..draft.maximumRating.toFloat(), valueRange = 0f..5f, steps = 4,
                    onValueChange = { draft = draft.copy(minimumRating = it.start.roundToInt(), maximumRating = it.endInclusive.roundToInt()) }
                )
                ChoiceRow("选片标记", PickState.entries, draft.pickStates, { it.label() }) {
                    draft = draft.copy(pickStates = draft.pickStates.toggle(it))
                }
                ChoiceRow("原片状态", DownloadState.entries, draft.downloadStates, { it.label() }) {
                    draft = draft.copy(downloadStates = draft.downloadStates.toggle(it))
                }
                OutlinedTextField(
                    value = draft.text, onValueChange = { draft = draft.copy(text = it) },
                    label = { Text("文件名 / 标签关键词") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                ExpandableSection("更多条件", advanced, { advanced = !advanced }) {
                    CaptureDateRange(draft) { from, until -> draft = draft.copy(capturedAfterMillis = from, capturedBeforeMillis = until) }
                    RangeInputs("文件大小（MiB）", "size", fields, ::update)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("拍摄信息", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = draft.camera, onValueChange = { draft = draft.copy(camera = it) }, label = { Text("相机型号包含") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = draft.lens, onValueChange = { draft = draft.copy(lens = it) }, label = { Text("镜头名称包含") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    RangeInputs("ISO", "iso", fields, ::update)
                    RangeInputs("光圈 f/", "aperture", fields, ::update)
                    RangeInputs("曝光时间（秒，可填 1/250）", "exposure", fields, ::update)
                    RangeInputs("焦距（mm）", "focal", fields, ::update)
                    ChoiceRow("画面方向", OrientationFilter.entries, setOf(draft.orientation), { it.label() }) {
                        draft = draft.copy(orientation = it)
                    }
                    OutlinedTextField(
                        value = fields["tags"].orEmpty(), onValueChange = { update("tags", it) },
                        label = { Text("同时包含标签（逗号分隔）") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("显示方式", style = MaterialTheme.typography.titleMedium)
                    CheckRow("仅有 RAW + JPEG 配对的照片", draft.pairedRawJpegOnly) { draft = draft.copy(pairedRawJpegOnly = it) }
                    CheckRow("保留元数据未知的照片", draft.includeUnknownMetadata) { draft = draft.copy(includeUnknownMetadata = it) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureDateRange(filter: FilterSpec, onChange: (Long?, Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val hasDate = filter.capturedAfterMillis != null || filter.capturedBeforeMillis != null
    Column {
        Text("拍摄日期", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
                Text(if (!hasDate) "选择日期范围" else filter.capturedAfterMillis.toDate().ifEmpty { "不限" } + " — " +
                    filter.capturedBeforeMillis.toDate().ifEmpty { "不限" })
            }
            if (hasDate) TextButton(onClick = { onChange(null, null) }) { Text("清除") }
        }
    }
    if (open) {
        val zone = remember { ZoneId.systemDefault() }
        val first = pickerDayMillis(filter.capturedAfterMillis, zone)
        val last = pickerDayMillis(filter.capturedBeforeMillis, zone)
        val picker = rememberDateRangePickerState(initialSelectedStartDateMillis = first,
            initialSelectedEndDateMillis = last.takeIf { first != null }, initialDisplayedMonthMillis = first ?: last)
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null, onClick = {
                onChange(filterDayMillis(picker.selectedStartDateMillis, false, zone), filterDayMillis(picker.selectedEndDateMillis, true, zone))
                open = false
            }) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } }) {
            DateRangePicker(picker, Modifier.heightIn(max = 520.dp), title = { Text("拍摄日期", Modifier.padding(24.dp)) })
        }
    }
}

@Composable
private fun <T> ChoiceRow(title: String, values: List<T>, selected: Set<T>, label: (T) -> String, onClick: (T) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { value ->
                FilterChip(selected = value in selected, onClick = { onClick(value) }, label = { Text(label(value)) })
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RangeInputs(
    title: String, key: String, fields: Map<String, String>, onChange: (String, String) -> Unit,
    lowerHint: String = "最小", upperHint: String = "最大"
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fields["${key}Min"].orEmpty(), onValueChange = { onChange("${key}Min", it) },
                label = { Text(lowerHint) }, modifier = Modifier.weight(1f), singleLine = true
            )
            OutlinedTextField(
                value = fields["${key}Max"].orEmpty(), onValueChange = { onChange("${key}Max", it) },
                label = { Text(upperHint) }, modifier = Modifier.weight(1f), singleLine = true
            )
        }
    }
}

private fun FilterSpec.toFields(): Map<String, String> = mapOf(
    "sizeMin" to (minimumSizeBytes?.div(MEBIBYTE)?.toString() ?: ""),
    "sizeMax" to (maximumSizeBytes?.div(MEBIBYTE)?.toString() ?: ""),
    "isoMin" to (minimumIso?.toString() ?: ""), "isoMax" to (maximumIso?.toString() ?: ""),
    "apertureMin" to (minimumAperture?.toString() ?: ""), "apertureMax" to (maximumAperture?.toString() ?: ""),
    "exposureMin" to (minimumExposureSeconds?.toString() ?: ""), "exposureMax" to (maximumExposureSeconds?.toString() ?: ""),
    "focalMin" to (minimumFocalLengthMm?.toString() ?: ""), "focalMax" to (maximumFocalLengthMm?.toString() ?: ""),
    "tags" to tags.joinToString(", ")
)

private fun FilterSpec.withFields(fields: Map<String, String>): FilterSpec {
    fun number(key: String, label: String, allowZero: Boolean = false): Double? {
        val text = fields[key].orEmpty().trim()
        if (text.isEmpty()) return null
        val parts = text.split('/')
        val value = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val numerator = parts[0].trim().toDoubleOrNull()
                val denominator = parts[1].trim().toDoubleOrNull()
                if (numerator == null || denominator == null || denominator <= 0) null else numerator / denominator
            }
            else -> null
        }
        require(value != null && value.isFinite() && if (allowZero) value >= 0 else value > 0) { "$label 需要填写有效数值" }
        return value
    }
    fun iso(key: String): Int? {
        val value = number(key, "ISO") ?: return null
        require(value <= Int.MAX_VALUE && value % 1.0 == 0.0) { "ISO 必须为正整数" }
        return value.toInt()
    }
    fun bytes(key: String): Long? {
        val value = number(key, "文件大小", allowZero = true) ?: return null
        require(value < Long.MAX_VALUE / MEBIBYTE) { "文件大小超出范围" }
        return (value * MEBIBYTE).toLong()
    }
    val result = copy(
        minimumSizeBytes = bytes("sizeMin"), maximumSizeBytes = bytes("sizeMax"),
        minimumIso = iso("isoMin"), maximumIso = iso("isoMax"),
        minimumAperture = number("apertureMin", "光圈"), maximumAperture = number("apertureMax", "光圈"),
        minimumExposureSeconds = number("exposureMin", "曝光时间"), maximumExposureSeconds = number("exposureMax", "曝光时间"),
        minimumFocalLengthMm = number("focalMin", "焦距"), maximumFocalLengthMm = number("focalMax", "焦距"),
        tags = fields["tags"].orEmpty().split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    )
    checkRange(result.capturedAfterMillis, result.capturedBeforeMillis, "拍摄日期")
    checkRange(result.minimumSizeBytes, result.maximumSizeBytes, "文件大小")
    checkRange(result.minimumIso, result.maximumIso, "ISO")
    checkRange(result.minimumAperture, result.maximumAperture, "光圈")
    checkRange(result.minimumExposureSeconds, result.maximumExposureSeconds, "曝光时间")
    checkRange(result.minimumFocalLengthMm, result.maximumFocalLengthMm, "焦距")
    return result
}

private fun <T : Comparable<T>> checkRange(minimum: T?, maximum: T?, label: String) {
    require(minimum == null || maximum == null || minimum <= maximum) { "$label 的最小值不能大于最大值" }
}

private fun Long?.toDate(): String = this?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: ""
private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

internal fun MediaFormat.label(): String = when (this) {
    MediaFormat.JPEG -> "JPEG"
    MediaFormat.RAW -> "RAW"
    MediaFormat.PNG -> "PNG"
    MediaFormat.HEIF -> "HEIF"
    MediaFormat.TIFF -> "TIFF"
    MediaFormat.OTHER -> "其他"
}

internal fun PickState.label(): String = when (this) {
    PickState.UNMARKED -> "未标记"
    PickState.PICKED -> "入选"
    PickState.REJECTED -> "排除"
}

internal fun DownloadState.label(): String = when (this) {
    DownloadState.REMOTE -> "相机原片"
    DownloadState.QUEUED -> "待导入"
    DownloadState.DOWNLOADED -> "本地原片"
}

internal fun OrientationFilter.label(): String = when (this) {
    OrientationFilter.ANY -> "不限"
    OrientationFilter.LANDSCAPE -> "横向"
    OrientationFilter.PORTRAIT -> "纵向"
    OrientationFilter.SQUARE -> "方形"
}

internal fun SortOrder.label(): String = when (this) {
    SortOrder.NEWEST -> "最新优先"
    SortOrder.OLDEST -> "最早优先"
    SortOrder.RATING -> "评分优先"
    SortOrder.NAME -> "文件名"
    SortOrder.SIZE -> "文件大小"
}
