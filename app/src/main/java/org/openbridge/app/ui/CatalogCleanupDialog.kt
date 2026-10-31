package org.openbridge.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

internal enum class CatalogCleanup { IMPORT_LIST, IMPORT_SELECTED, LIBRARY_ALL, LIBRARY_SELECTED }

@Composable
internal fun CatalogCleanupDialog(action: CatalogCleanup, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title = when (action) {
        CatalogCleanup.IMPORT_LIST -> "清空本次选片列表？"
        CatalogCleanup.IMPORT_SELECTED -> "移除所选候选照片？"
        CatalogCleanup.LIBRARY_ALL -> "清空图库列表？"
        CatalogCleanup.LIBRARY_SELECTED -> "从图库移除所选照片？"
    }
    val detail = if (action == CatalogCleanup.IMPORT_SELECTED) {
        "从当前导入列表移除所选项目，保留手机文件、相机原件和导入历史。再次刷新相机可重新加入。"
    } else if (action == CatalogCleanup.IMPORT_LIST) {
        "移除当前相机的候选照片和未导入预览；离线时清空所有候选列表。已保存原片、图库条目和导入历史保留。" +
            "有待传输任务时，请先完成或取消任务。再次刷新相机可重新选片。"
    } else {
        "仅从 OpenBridge 图库列表移除，手机相册、RAW 文件和导入历史保留。需要时可以重新添加本地照片。"
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("移除列表条目") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
