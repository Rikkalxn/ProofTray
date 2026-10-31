package org.openbridge.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.FilterSpec
import org.openbridge.app.domain.ImportVisibility
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PhotoCollection

@Composable
internal fun PhotoBrowserScreen(
    state: LibraryUiState, actions: LibraryActions, onCamera: () -> Unit, onFilters: () -> Unit,
    onSavedFilters: () -> Unit, onExpand: (String) -> Unit, onPreview: (String) -> Unit
) {
    val importing = state.browser.collection == PhotoCollection.CAMERA_IMPORT
    Column(Modifier.fillMaxSize()) {
        if (importing) ImportSource(state, actions, onCamera)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.filter != FilterSpec(), onClick = onFilters, label = { Text("组合筛选") })
            if (importing) ImportVisibility.entries.forEach { visibility ->
                FilterChip(selected = state.browser.importVisibility == visibility, onClick = { actions.onSetImportVisibility(visibility) },
                    label = { Text(visibility.label()) })
            }
            FilterChip(selected = state.collapseBursts, onClick = { actions.onSetCollapseBursts(!state.collapseBursts) }, label = { Text("折叠连拍") })
            FilterChip(selected = false, onClick = onSavedFilters, label = { Text("已存筛选") })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${state.catalog.matchedCount} 项", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (state.catalog.pendingMetadataCount > 0) {
                Text("${state.catalog.pendingMetadataCount} 项元数据待确认", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (state.isLoading || (importing && state.camera.busy)) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        }
        if (state.catalog.groups.isEmpty()) BrowserEmptyState(state, actions, onCamera, onFilters)
        else LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.catalog.groups, key = { it.id }) { group ->
                val photo = group.members.first()
                PhotoCard(photo, photo.id in state.selectedIds, { actions.onToggleSelection(photo.id) },
                    onOpen = { onPreview(photo.id) }, selecting = state.selectedIds.isNotEmpty()) {
                    if (group.members.size > 1) TextButton(onClick = { onExpand(group.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${if (group.inferredBurst) "疑似连拍" else "成组照片"} · ${group.members.size} 项")
                    }
                }
            }
            if (importing && state.camera.connected && state.camera.hasMore) item(span = { GridItemSpan(maxLineSpan) }) {
                FilledTonalButton(onClick = actions.onLoadMoreCamera, enabled = !state.camera.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("继续加载，最多 ${state.camera.catalogBatchSize} 项")
                }
            }
        }
    }
}

@Composable
private fun ImportSource(state: LibraryUiState, actions: LibraryActions, onCamera: () -> Unit) {
    var batchMenu by remember { mutableStateOf(false) }
    val cached = state.assets.count { it.inImportList }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(if (state.camera.connected) state.camera.cameraName else "相机未连接", active = state.camera.connected)
            Box {
                TextButton(onClick = { batchMenu = true }) { Text("每批 ${state.camera.catalogBatchSize} 项") }
                DropdownMenu(expanded = batchMenu, onDismissRequest = { batchMenu = false }) {
                    listOf(25, 50, 100, 200).forEach { size ->
                        DropdownMenuItem(text = { Text("最多 $size 项") }, onClick = { actions.onSetBatchSize(size); batchMenu = false })
                    }
                }
            }
            TextButton(onClick = if (state.camera.connected) actions.onRefreshCamera else onCamera, enabled = !state.camera.busy) {
                Text(if (state.camera.connected) "刷新" else "连接")
            }
        }
        if (state.camera.connected) {
            Text("已读取 ${state.camera.indexed} / ${state.camera.total} 项；筛选和排序作用于已加载照片。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("当前导入：${state.settings.manualVariant.label()}。每批上限同时用于加载和导入。", style = MaterialTheme.typography.bodySmall)
        } else if (cached > 0) {
            Text("保留了 $cached 项离线预览，原片只在图库中显示。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { actions.onSetOfflinePreviews(!state.browser.showOfflinePreviews) }) {
                Text(if (state.browser.showOfflinePreviews) "收起离线预览" else "查看离线预览，继续选片")
            }
        }
        state.camera.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun BrowserEmptyState(state: LibraryUiState, actions: LibraryActions, onCamera: () -> Unit, onFilters: () -> Unit) {
    val importing = state.browser.collection == PhotoCollection.CAMERA_IMPORT
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BridgeIcon(if (importing) BridgeGlyph.CAMERA else BridgeGlyph.PHOTOS, modifier = Modifier.size(64.dp))
            val title = when {
                importing && !state.camera.connected && !state.browser.showOfflinePreviews -> "连接相机，开始选片"
                importing -> "当前没有匹配的待导入照片"
                state.filter != FilterSpec() -> "当前筛选没有匹配照片"
                else -> "图库还没有照片"
            }
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (importing) {
                if (state.camera.connected && state.camera.hasMore) PrimaryAction("继续加载下一批", actions.onLoadMoreCamera,
                    enabled = !state.camera.busy)
                else if (!state.camera.connected) PrimaryAction("连接相机", onCamera)
                if (state.browser.importVisibility != ImportVisibility.ALL) {
                    TextButton(onClick = { actions.onSetImportVisibility(ImportVisibility.ALL) }) { Text("查看全部，含已导入") }
                }
            } else {
                Text("图库管理手机里的照片；到下方“导入”浏览和挑选相机照片。", textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium)
                PrimaryAction("去导入选片", { actions.onSetCollection(PhotoCollection.CAMERA_IMPORT) })
                TextButton(onClick = actions.onImport) { Text("添加手机里的照片") }
            }
            if (state.filter != FilterSpec()) TextButton(onClick = onFilters) { Text("调整筛选条件") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoCard(
    asset: PhotoAsset, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit = onToggle,
    selecting: Boolean = false, footer: @Composable () -> Unit = {}
) {
    val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "photo selection")
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = color),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).combinedClickable(onClick = if (selecting) onToggle else onOpen, onLongClick = onToggle)) {
            PhotoImage(asset, Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.TopStart).padding(10.dp), color = Color.Black.copy(alpha = 0.6f), shape = CircleShape) {
                Text(asset.format.label(), Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Color.White,
                    style = MaterialTheme.typography.labelSmall)
            }
            Surface(onClick = onToggle, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp), shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f),
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White) {
                Box(contentAlignment = Alignment.Center) { if (selected) BridgeIcon(BridgeGlyph.CHECK, modifier = Modifier.size(20.dp)) }
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(asset.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            val status = when {
                asset.derivedFromId != null -> "便携 JPEG 副本"
                asset.downloadState == DownloadState.QUEUED -> "导入队列中"
                asset.downloadState == DownloadState.DOWNLOADED -> "已导入 · 本地原片"
                asset.importedAtMillis != null -> "已导入过"
                asset.jpegCopyImportedAtMillis != null -> "曾导入 JPEG 副本 · 原片未导入"
                else -> "待导入"
            }
            Text("${asset.rating} 星 · $status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        footer()
    }
}

private fun ImportVisibility.label(): String = when (this) {
    ImportVisibility.NEW -> "未导入"
    ImportVisibility.ALL -> "全部"
    ImportVisibility.IMPORTED -> "已导入"
}
