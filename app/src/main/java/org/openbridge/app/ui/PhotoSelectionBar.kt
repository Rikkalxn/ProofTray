package org.openbridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import org.openbridge.app.domain.PickState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SelectionBar(
    count: Int, hiddenCount: Int, actions: LibraryActions, onCompare: () -> Unit, onExport: () -> Unit, onTags: () -> Unit,
    canGrade: Boolean, onGrade: () -> Unit, importing: Boolean, batchLimit: Int, onRemove: () -> Unit,
    canDelete: Boolean, onDelete: () -> Unit
) {
    var markingMenu by remember { mutableStateOf(false) }
    var ratingMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (hiddenCount == 0) "已选 $count 张" else "已选 $count 张，含筛选外 $hiddenCount 张",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = actions.onClearSelection) { Text("清除选择") }
                TextButton(onClick = onRemove) { Text("移除") }
                TextButton(onClick = onDelete, enabled = canDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            }
            if (importing && count > batchLimit) Text("本批导入 $batchLimit 项，其余继续保留选择。", style = MaterialTheme.typography.bodySmall)
            HorizontalFloatingToolbar(expanded = true, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                if (importing) FilledTonalButton(onClick = actions.onDownloadSelection) { Text("导入 ${minOf(count, batchLimit)} 项") }
                Box {
                    TextButton(onClick = { ratingMenu = true }) { Text("评分") }
                    DropdownMenu(expanded = ratingMenu, onDismissRequest = { ratingMenu = false }) {
                        (0..5).forEach { rating ->
                            DropdownMenuItem(text = { Text(if (rating == 0) "清除评分" else "$rating 星") },
                                onClick = { actions.onSetRating(rating); ratingMenu = false })
                        }
                    }
                }
                Box {
                    IconButton(onClick = { markingMenu = true }) { BridgeIcon(BridgeGlyph.MORE, "标记和更多操作") }
                    DropdownMenu(expanded = markingMenu, onDismissRequest = { markingMenu = false }) {
                        DropdownMenuItem(text = { Text("标为入选") }, onClick = { actions.onSetPickState(PickState.PICKED); markingMenu = false })
                        DropdownMenuItem(text = { Text("标为排除") }, onClick = { actions.onSetPickState(PickState.REJECTED); markingMenu = false })
                        DropdownMenuItem(text = { Text("清除标记") }, onClick = { actions.onSetPickState(PickState.UNMARKED); markingMenu = false })
                        DropdownMenuItem(text = { Text("编辑标签") }, onClick = { onTags(); markingMenu = false })
                        DropdownMenuItem(text = { Text("系统编辑副本") }, enabled = count == 1,
                            onClick = { actions.onSystemEdit(); markingMenu = false })
                    }
                }
                TextButton(onClick = onCompare, enabled = count in 1..4) { Text(if (count == 1) "查看" else "比较") }
                if (!importing) TextButton(onClick = onGrade, enabled = canGrade) { Text("调色") }
                if (!importing) FilledTonalButton(onClick = onExport) { Text("导出") }
            }
        }
    }
}
