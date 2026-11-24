package org.prooftray.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

internal enum class CatalogCleanup { IMPORT_LIST, IMPORT_SELECTED, LIBRARY_ALL, LIBRARY_SELECTED }

@Composable
internal fun CatalogCleanupDialog(action: CatalogCleanup, listName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title = when (action) {
        CatalogCleanup.IMPORT_LIST -> "清空本次选片列表？"
        CatalogCleanup.IMPORT_SELECTED -> "将所选候选照片移出列表？"
        CatalogCleanup.LIBRARY_ALL -> "清空${listName}列表？"
        CatalogCleanup.LIBRARY_SELECTED -> "将所选照片移出${listName}列表？"
    }
    val detail = if (action == CatalogCleanup.IMPORT_SELECTED) {
        "将所选项目移出当前导入列表，保留手机文件、相机原片和导入历史。再次刷新相机可重新加入。"
    } else if (action == CatalogCleanup.IMPORT_LIST) {
        "将当前相机的候选照片和未导入预览移出列表；离线时清空所有候选列表。已保存原片、图库条目和导入历史保留。" +
            "有待传输任务时，请先完成或取消任务。再次刷新相机可重新选片。"
    } else {
        "仅移出本应用的图库列表，手机相册、RAW 文件和导入历史保留。需要时可以重新添加本地照片。"
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("移出列表") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
