package org.openbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.pow
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.LutEncoding
import org.openbridge.app.domain.LutRange
import org.openbridge.app.domain.LutUsage

@Composable
internal fun LutUsageControls(
    look: ColorLookSettings, allowLog: Boolean, warning: String?, onGainChange: (Float) -> Unit, onUsageChange: (LutUsage) -> Unit
) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("LUT 输入", style = MaterialTheme.typography.labelLarge)
            LutKindMenu(look.usage, allowLog, onUsageChange)
        }
        warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (look.usesLog) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LUT 输出", style = MaterialTheme.typography.labelLarge)
                LutOutputMenu(look.usage, onUsageChange)
            }
            var showReference by remember { mutableStateOf(false) }
            val gain = look.logGainEv
            val label = String.format(Locale.ROOT, "%+.1f EV · %.2f×", gain, 2.0.pow(gain.toDouble()))
            EditorSlider("额外增益 K", gain, -3f..3f, label, onReset = if (gain != 0f) ({ onGainChange(0f) }) else null,
                onInfo = { showReference = true }, onChange = onGainChange)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LutRange.entries.forEach { range ->
                    FilterChip(selected = look.usage.inputRange == range, onClick = { onUsageChange(look.usage.copy(inputRange = range)) },
                        label = { Text(if (range == LutRange.FULL) "全范围" else "视频范围") })
                }
            }
            if (showReference) AlertDialog(onDismissRequest = { showReference = false }, title = { Text("RAW 曝光参考") },
                text = { Text("K=1 表示不额外增益，原图和 0% 不受 K 影响。通用 RAW 参考尚未针对当前机型标定。" +
                    "请先按 LUT 作者的说明确认输入和输出编码，再调整 K。") },
                confirmButton = { TextButton(onClick = { showReference = false }) { Text("关闭") } })
        }
    }
}

@Composable
internal fun LutKindMenu(usage: LutUsage, allowLog: Boolean, onChange: (LutUsage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.widthIn(max = 160.dp)) {
            Text(if (usage.unavailableReason != null) "不可用" else usage.input?.label.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            LutEncoding.entries.forEach { encoding ->
                DropdownMenuItem(text = { Text(encoding.label) }, enabled = allowLog || !encoding.isLog, onClick = {
                    expanded = false
                    if (encoding != usage.input || usage.unavailableReason != null) onChange(usage.withInput(encoding))
                })
            }
        }
    }
}

@Composable
internal fun LutOutputMenu(usage: LutUsage, onChange: (LutUsage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun label(encoding: LutEncoding?) = when (encoding) {
        LutEncoding.DIRECT -> "sRGB · 直接显示"
        LutEncoding.REC709_G24 -> "Rec.709 γ2.4"
        else -> "不可用"
    }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label(usage.output)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            LutUsage.logOutputs.forEach { encoding ->
                DropdownMenuItem(text = { Text(label(encoding)) }, onClick = {
                    expanded = false
                    onChange(usage.copy(outputName = encoding.name, outputRange = LutRange.FULL))
                })
            }
        }
    }
}
