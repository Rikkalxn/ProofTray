package org.prooftray.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.prooftray.app.domain.ColorPreset
import org.prooftray.app.domain.LutAsset

@Composable
internal fun LutManagementMenu(lut: LutAsset, enabled: Boolean, onSetRemoved: (String, Boolean) -> Unit, onDelete: () -> Unit) {
    var expanded by remember(lut.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) { AppIcon(AppGlyph.MORE, "管理 LUT") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(if (lut.removed) "恢复" else "隐藏") },
                leadingIcon = { AppIcon(if (lut.removed) AppGlyph.VISIBLE else AppGlyph.HIDDEN) }, onClick = {
                    expanded = false
                    onSetRemoved(lut.id, !lut.removed)
                })
            DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { AppIcon(AppGlyph.DELETE) }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
internal fun LutDeleteDialog(
    lut: LutAsset, references: List<ColorPreset>, inUse: Boolean, busy: Boolean, onConfirm: (Set<String>) -> Unit, onDismiss: () -> Unit
) {
    val related = if (references.isEmpty()) "" else {
        val names = references.take(3).joinToString("、") { it.name }
        "\n同时删除 ${references.size} 个关联预设：$names${if (references.size > 3) "…" else ""}。"
    }
    val message = if (inUse) "导出任务正在使用这个 LUT，请完成或取消任务后再删除。隐藏不会影响正在执行的任务。"
        else "删除应用内的 LUT 副本，保留外部源文件。$related\n删除后，历史任务重试可能需要重新导入这个 LUT。"
    AlertDialog(onDismissRequest = onDismiss, title = { Text("删除“${lut.name}”？") }, text = { Text(message) },
        confirmButton = {
            TextButton(enabled = !inUse && !busy, onClick = { onConfirm(references.mapTo(hashSetOf()) { it.id }) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ColorPresetDeleteDialog(preset: ColorPreset, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("删除“${preset.name}”？") },
        text = { Text("删除这份调色预设，保留它使用的 LUT。") },
        confirmButton = { TextButton(enabled = !busy, onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
