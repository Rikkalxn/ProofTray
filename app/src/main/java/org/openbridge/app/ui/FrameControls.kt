package org.openbridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun <T> FrameChoice(label: String, value: T, choices: List<T>, name: (T) -> String,
    extraItem: Pair<String, () -> Unit>? = null, change: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { expanded = true }) { Text(name(value)); BridgeIcon(BridgeGlyph.FORWARD) }
            DropdownMenu(expanded, { expanded = false }) {
                choices.forEach { choice -> DropdownMenuItem(text = { Text(name(choice)) }, onClick = { expanded = false; change(choice) }) }
                extraItem?.let { (title, action) ->
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; action() })
                }
            }
        }
    }
}

@Composable
internal fun FrameSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = change)
    }
}

@Composable
internal fun FrameSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit,
    begin: () -> Unit = {}, end: () -> Unit = {}, percent: Boolean = true) {
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val factor = if (percent) 100f else 1f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.3f), style = MaterialTheme.typography.bodyMedium)
        Slider(value = value.coerceIn(range), onValueChange = { begin(); change(it) }, onValueChangeFinished = end,
            valueRange = range, modifier = Modifier.weight(0.5f))
        TextButton(onClick = { end(); input = String.format(Locale.ROOT, "%.2f", value * factor); editing = true }) {
            Text(String.format(Locale.ROOT, "%.1f", value * factor) + if (percent) "%" else "")
        }
    }
    if (editing) {
        val number = input.toFloatOrNull()?.div(factor)
        val valid = number != null && number.isFinite() && number in range
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(label) },
            text = { OutlinedTextField(input, { input = it }, singleLine = true, isError = !valid,
                supportingText = { if (!valid) Text("请输入 " + range.start * factor + "–" + range.endInclusive * factor + " 之间的数值") }) },
            confirmButton = { TextButton(enabled = valid, onClick = { change(checkNotNull(number)); editing = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
    }
}

@Composable
internal fun FrameColor(label: String, value: String, change: (String) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val palette = listOf("#FFFFFF", "#222222", "#777777", "#F5F0E6", "#98544B", "#E6C373", "#557D8E")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { input = value.removePrefix("#"); custom = true }) { Text(value) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            palette.forEach { color ->
                Box(Modifier.size(48.dp).semantics { contentDescription = label + " " + color }.clickable { change(color) }
                    .padding(4.dp).border(if (value.equals(color, true)) 3.dp else 1.dp,
                        if (value.equals(color, true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .padding(4.dp).background(Color(android.graphics.Color.parseColor(color)), CircleShape))
            }
        }
    }
    if (custom) {
        val valid = Regex("[a-fA-F0-9]{6}").matches(input)
        AlertDialog(onDismissRequest = { custom = false }, title = { Text(label) },
            text = { OutlinedTextField(input, { input = it.take(6) }, prefix = { Text("#") }, singleLine = true, isError = !valid) },
            confirmButton = { TextButton(enabled = valid, onClick = { change("#" + input.uppercase(Locale.ROOT)); custom = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("取消") } })
    }
}
