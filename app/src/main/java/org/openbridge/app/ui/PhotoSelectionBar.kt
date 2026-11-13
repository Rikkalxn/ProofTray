package org.openbridge.app.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.DownloadVariant
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PickState

/**
 * Bottom tools while selecting. [visible] are the selected photos inside the current filter; camera import only submits those,
 * capped at [batchLimit], so the primary label states the exact count.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SelectionBar(
    count: Int, visible: List<PhotoAsset>, actions: LibraryActions, importing: Boolean, connected: Boolean, batchLimit: Int?,
    variant: DownloadVariant, canGrade: Boolean, canUngroup: Boolean, onPromote: (() -> Unit)?,
    onCompare: () -> Unit, onExport: () -> Unit, onTags: () -> Unit, onGrade: () -> Unit, onFrame: () -> Unit, onCamera: () -> Unit
) {
    var markingMenu by remember { mutableStateOf(false) }
    var ratingMenu by remember { mutableStateOf(false) }
    val portable = variant == DownloadVariant.CAMERA_JPEG
    val importCount = batchLimit?.let { minOf(visible.size, it) } ?: visible.size
    val needsCamera = portable || visible.any { it.downloadState != DownloadState.DOWNLOADED }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (importing) {
                val notes = listOfNotNull("本批导入 $importCount 张，其余保持选中".takeIf { visible.size > importCount },
                    "筛选外 ${count - visible.size} 张不导入".takeIf { count > visible.size })
                if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalFloatingToolbar(expanded = true, modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    Box {
                        IconButton(onClick = { ratingMenu = true }, enabled = count > 0) { BridgeIcon(BridgeGlyph.STAR, "评分") }
                        DropdownMenu(expanded = ratingMenu, onDismissRequest = { ratingMenu = false }) {
                            (5 downTo 0).forEach { rating ->
                                DropdownMenuItem(text = { Text(if (rating == 0) "清除评分" else "★".repeat(rating)) },
                                    onClick = { actions.onSetRating(rating); ratingMenu = false })
                            }
                        }
                    }
                    TextButton(onClick = onCompare, enabled = count in 1..4) { Text(if (count == 1) "查看" else "比较") }
                    if (!importing) TextButton(onClick = onGrade, enabled = canGrade) { Text("调色") }
                    if (!importing) TextButton(onClick = onFrame, enabled = canGrade) { Text("边框") }
                    Box {
                        IconButton(onClick = { markingMenu = true }, enabled = count > 0) { BridgeIcon(BridgeGlyph.MORE, "标记和更多操作") }
                        DropdownMenu(expanded = markingMenu, onDismissRequest = { markingMenu = false }) {
                            DropdownMenuItem(text = { Text("标为入选") }, onClick = { actions.onSetPickState(PickState.PICKED); markingMenu = false })
                            DropdownMenuItem(text = { Text("标为排除") }, onClick = { actions.onSetPickState(PickState.REJECTED); markingMenu = false })
                            DropdownMenuItem(text = { Text("清除标记") }, onClick = { actions.onSetPickState(PickState.UNMARKED); markingMenu = false })
                            DropdownMenuItem(text = { Text("编辑标签") }, onClick = { onTags(); markingMenu = false })
                            DropdownMenuItem(text = { Text("将所选照片分组") }, enabled = count >= 2,
                                onClick = { actions.onGroupSelection(); markingMenu = false })
                            DropdownMenuItem(text = { Text("解散所在分组") }, enabled = canUngroup,
                                onClick = { actions.onUngroupSelection(); markingMenu = false })
                            if (!importing) DropdownMenuItem(text = { Text("系统编辑副本") }, enabled = count == 1,
                                onClick = { actions.onSystemEdit(); markingMenu = false })
                            if (onPromote != null) DropdownMenuItem(text = { Text("导出") }, enabled = count > 0,
                                onClick = { markingMenu = false; onExport() })
                        }
                    }
                }
                when {
                    onPromote != null -> PrimaryAction("放入照片", onPromote, enabled = count > 0)
                    !importing -> PrimaryAction("导出", onExport, enabled = count > 0)
                    needsCamera && !connected && importCount > 0 -> PrimaryAction("连接相机", onCamera)
                    else -> {
                        val kind = when {
                            portable -> "便携 JPEG"
                            visible.isNotEmpty() && visible.all { it.format == MediaFormat.RAW } -> " RAW"
                            else -> "原片"
                        }
                        PrimaryAction(if (importCount > 0) "导入 $importCount 张$kind" else "导入", actions.onDownloadSelection,
                            enabled = importCount > 0)
                    }
                }
            }
        }
    }
}
