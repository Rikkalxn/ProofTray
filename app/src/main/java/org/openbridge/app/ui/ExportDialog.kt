package org.openbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openbridge.app.domain.ExportFormat
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.ExportOptions
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset

@Composable
fun ExportDialog(assets: List<PhotoAsset>, look: ColorLookSettings, onExport: (ExportOptions) -> Unit, onDismiss: () -> Unit) {
    var format by remember { mutableStateOf(if (look.isIdentity) ExportFormat.ORIGINAL else ExportFormat.JPEG) }
    var applyLook by remember { mutableStateOf(!look.isIdentity) }
    var quality by remember { mutableStateOf(95f) }
    var maxEdge by remember { mutableStateOf(if (look.isIdentity) 0 else 4096) }
    val unsupportedCount = assets.count { it.format !in setOf(MediaFormat.JPEG, MediaFormat.PNG, MediaFormat.HEIF) }
    val blocked = format != ExportFormat.ORIGINAL && unsupportedCount > 0
    BridgeSheet(
        title = "导出 ${assets.size} 张照片", onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.weight(1f))
            PrimaryAction("选择文件夹", enabled = assets.isNotEmpty() && !blocked, onClick = {
                onExport(ExportOptions(format, quality.toInt(), maxEdge,
                    look = look.takeIf { applyLook && format != ExportFormat.ORIGINAL && !it.isIdentity }))
                onDismiss()
            }, glyph = BridgeGlyph.SHARE)
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("输出格式", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.forEach { option ->
                        FilterChip(selected = format == option, onClick = { format = option }, label = { Text(option.label()) })
                    }
                }
                if (format == ExportFormat.ORIGINAL) {
                    Text("逐字节复制选中的文件，保留 NEF / DNG 等原始格式。不会修改或删除源文件。")
                    if (!look.isIdentity) Text("原文件导出不应用调色预设。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("生成独立副本，原文件保持不变。", style = MaterialTheme.typography.bodyMedium)
                    if (!look.isIdentity) {
                        FilterChip(selected = applyLook, onClick = { applyLook = !applyLook }, label = { Text("应用预设：${look.name}") })
                    }
                    Text("输出最长边", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 2048, 4096).forEach { edge ->
                            FilterChip(
                                selected = maxEdge == edge, onClick = { maxEdge = edge },
                                label = { Text(if (edge == 0) "原尺寸" else "$edge px") }
                            )
                        }
                    }
                    Text("原尺寸转换上限为 1600 万像素，内存较小时会更低；超出限制请改为缩小尺寸。", style = MaterialTheme.typography.bodySmall)
                    if (format == ExportFormat.JPEG) {
                        Text("JPEG 质量 ${quality.toInt()}")
                        Slider(value = quality, onValueChange = { quality = it }, valueRange = 1f..100f)
                    } else {
                        Text("PNG 对渲染后的像素无损编码，文件可能较大；不等同于保留 RAW 数据。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (blocked) Text(
                    "包含 $unsupportedCount 张 RAW / TIFF 等暂不支持转换的文件，请调整选片或改为原文件。",
                    color = MaterialTheme.colorScheme.error
                )
                Text("同名文件会生成新名称。调色使用 SDR sRGB，暂不支持 RAW 渲染。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("导出期间请保持应用运行，可在“任务”页查看进度。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

internal fun ExportFormat.label(): String = when (this) {
    ExportFormat.ORIGINAL -> "原文件"
    ExportFormat.JPEG -> "JPEG"
    ExportFormat.PNG -> "PNG"
}
