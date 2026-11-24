package org.openbridge.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import java.util.Locale
import kotlin.math.pow
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.LogExportSettings

@Composable
internal fun LogExportControls(
    encoding: String, gain: Float, look: ColorLookSettings, onEncoding: (String) -> Unit, onGain: (Float) -> Unit,
    onEdit: () -> Unit, canEdit: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Log 编码", style = MaterialTheme.typography.labelLarge)
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { expanded = true }) {
                Text(LogExportSettings.encodings.find { it.name == encoding }?.label ?: "请选择编码")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                LogExportSettings.encodings.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.label) }, onClick = { onEncoding(choice.name); expanded = false })
                }
            }
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(Locale.ROOT, "额外增益 K  %+.1f EV · %.2f×", gain, 2.0.pow(gain.toDouble())),
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { onGain(0f) }, enabled = gain != 0f) { Text("重置") }
        }
        Slider(value = gain, onValueChange = onGain, valueRange = -3f..3f)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(String.format(Locale.ROOT, "曝光 %+.1f EV · 冷暖 %+.2f · 色调 %+.2f", look.exposure, look.warmth, look.tint),
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onEdit, enabled = canEdit) { Text("更改") }
    }
    Text("保留相机白平衡、当前曝光、冷暖和色调，不应用 LUT、显示调色或边框。",
        style = MaterialTheme.typography.bodySmall)
    Text("Log 文件外观偏灰，后续调色时请指定上方的 Log 编码与色域。", style = MaterialTheme.typography.bodySmall)
}
