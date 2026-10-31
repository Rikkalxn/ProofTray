package org.openbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import org.openbridge.app.domain.PhotoGroup

@Composable
internal fun GroupDialog(group: PhotoGroup, selectedIds: Set<String>, onToggle: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (group.inferredBurst) "疑似连拍 · 逐张挑选" else "分组 · 逐张挑选") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (group.inferredBurst) Text("按拍摄时间和来源推断，不代表机身提供的连拍标识。", style = MaterialTheme.typography.bodySmall)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp), modifier = Modifier.height(400.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(group.members, key = { it.id }) { asset ->
                        PhotoCard(asset, asset.id in selectedIds, { onToggle(asset.id) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
internal fun SavedFiltersDialog(state: LibraryUiState, actions: LibraryActions, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("已保存的筛选") },
        text = {
            if (state.savedFilters.isEmpty()) Text("在组合筛选中设置条件，再点击“保存当前”。")
            else LazyColumn(modifier = Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.savedFilters, key = { it.id }) { saved ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(modifier = Modifier.weight(1f), onClick = { actions.onFilterChange(saved.spec); onDismiss() }) {
                            Text(saved.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { actions.onDeleteSavedFilter(saved.id) }) { Text("删除") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
internal fun TextEntryDialog(
    title: String, label: String, allowEmpty: Boolean = false, onConfirm: (String) -> Unit, onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = allowEmpty || text.isNotBlank(), onClick = { onConfirm(text.trim()); onDismiss() }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

