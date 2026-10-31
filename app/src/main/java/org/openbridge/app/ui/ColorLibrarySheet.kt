package org.openbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openbridge.app.domain.ColorLookSettings

@Composable
fun ColorLibrarySheet(state: LibraryUiState, actions: LibraryActions, onPick: (ColorLookSettings) -> Unit, onDismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    BridgeSheet(title = "LUT 与预设", onDismiss = onDismiss, actions = {
        TextButton(onClick = onDismiss) { Text("关闭") }
        PrimaryAction(if (state.colorBusy) "正在导入" else "导入文件", actions.onImportColor, enabled = !state.colorBusy)
    }) {
        state.errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = actions.onDismissError) { Text("关闭提示") }
        }
        Text("支持 .cube、8 位 sRGB HaldCLUT PNG，以及可分享的 .oblook 预设。ZIP 先解压，再选择其中的文件。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(search, { search = it }, label = { Text("搜索名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("预设", style = MaterialTheme.typography.titleMedium)
        state.colorPresets.filter { it.name.contains(search, true) }.forEach { preset ->
            Row(Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { onPick(preset.look); onDismiss() }, modifier = Modifier.weight(1f)) { Text(preset.name) }
                TextButton(onClick = { actions.onExportColorPreset(preset) }) { Text("导出模板") }
            }
        }
        Text("导入的 LUT", style = MaterialTheme.typography.titleMedium)
        if (state.luts.isEmpty()) Text("还没有导入 LUT。可从下面的资源网站下载后导入。", style = MaterialTheme.typography.bodyMedium)
        state.luts.filter { it.name.contains(search, true) }.forEach { lut ->
            FilledTonalButton(onClick = { onPick(ColorLookSettings(lut.name, lut.id)); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(lut.name)
                    Text("${lut.sourceFormat} · ${lut.dimension}D / ${lut.size}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text("获取更多风格", style = MaterialTheme.typography.titleMedium)
        Text("请选择用于 sRGB 照片的 LUT。Log / HDR 转换表不能直接当作 JPEG 滤镜。免费使用与允许随应用分发并不相同。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { actions.onOpenColorSource("https://gmic.eu/color_presets/") }) { Text("G’MIC：预览与下载 CUBE / HaldCLUT") }
        TextButton(onClick = { actions.onOpenColorSource("https://rawpedia.pixls.us/film_simulation/") }) { Text("RawTherapee：胶片模拟集合") }
        TextButton(onClick = { actions.onOpenColorSource("https://github.com/cedeber/hald-clut") }) { Text("HaldCLUT 资源集合") }
    }
}
