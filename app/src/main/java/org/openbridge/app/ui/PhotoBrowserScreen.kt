package org.openbridge.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.openbridge.app.camera.CameraMake
import org.openbridge.app.camera.CameraState
import org.openbridge.app.camera.ConnectionPhase
import org.openbridge.app.camera.SonyCaptureDay
import org.openbridge.app.camera.catalogDirection
import org.openbridge.app.camera.orderedSonyDates
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.DownloadVariant
import org.openbridge.app.domain.FilterSpec
import org.openbridge.app.domain.ImportVisibility
import org.openbridge.app.domain.LibrarySection
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PhotoCollection
import org.openbridge.app.domain.PickState
import org.openbridge.app.domain.SortOrder
import org.openbridge.app.domain.importedAs
import org.openbridge.app.domain.hasConditions
import org.openbridge.app.domain.hasAdvancedConditions

/** Where the original of a tile lives. [badge] is drawn on the photo; [detail] is the caption in the viewer. */
internal enum class OriginalState(val badge: String?, val detail: String) {
    CAMERA(null, "原片在相机"),
    QUEUED("等待导入", "原片等待导入"),
    IMPORTED("已导入", "原片已导入"),
    JPEG_ONLY("仅 JPEG", "手机只有 JPEG · 原片在相机"),
    LOCAL(null, "手机文件"),
    NO_ORIGINAL("无原片", "原片不在手机")
}

@Composable
internal fun rememberOriginalState(state: LibraryUiState): (PhotoAsset) -> OriginalState {
    val importing = state.browser.collection == PhotoCollection.CAMERA_IMPORT
    val variant = state.settings.effectiveManualVariant
    return remember(state.assets, importing, variant) { originalStateResolver(state.assets, importing, variant) }
}

internal fun originalStateResolver(
    assets: List<PhotoAsset>, importing: Boolean, variant: DownloadVariant = DownloadVariant.ORIGINAL
): (PhotoAsset) -> OriginalState {
    val byId = assets.associateBy { it.id }
    val originals = assets.filter { it.downloadState == DownloadState.DOWNLOADED && it.derivedFromId == null && it.companionOfId == null }
    val localFiles = originals.mapNotNull { it.cameraFileKey }.toHashSet()
    val localRawCaptures = originals.filter { it.format == MediaFormat.RAW }.mapNotNull { it.cameraCaptureKey }.toHashSet()
    return { asset -> originalState(asset, importing, variant, byId, localFiles, localRawCaptures) }
}

private fun originalState(
    asset: PhotoAsset, importing: Boolean, variant: DownloadVariant, byId: Map<String, PhotoAsset>,
    localFiles: Set<String>, localRawCaptures: Set<String>
): OriginalState {
    if (asset.downloadState == DownloadState.QUEUED) return OriginalState.QUEUED
    if (importing) {
        return if (asset.importedAs(variant)) OriginalState.IMPORTED else OriginalState.CAMERA
    }
    val sourceId = asset.companionOfId ?: asset.derivedFromId ?: return OriginalState.LOCAL
    val source = byId[sourceId]
    val originalOnPhone = source?.downloadState == DownloadState.DOWNLOADED || source?.cameraFileKey?.let { it in localFiles } == true ||
        (asset.companionOfId != null && asset.cameraCaptureKey?.let { it in localRawCaptures } == true)
    val fromCamera = asset.companionOfId != null ||
        (source?.sourceId?.let { it != "local" && !it.startsWith("document:") } ?: (asset.cameraCaptureKey != null))
    return when {
        originalOnPhone || !fromCamera -> OriginalState.LOCAL
        asset.companionOfId != null && asset.format == MediaFormat.JPEG -> OriginalState.JPEG_ONLY
        else -> OriginalState.NO_ORIGINAL
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoBrowserScreen(
    state: LibraryUiState, actions: LibraryActions, selecting: Boolean, originalOf: (PhotoAsset) -> OriginalState,
    onLibrarySection: (LibrarySection) -> Unit, onCamera: (CameraPanel?) -> Unit, onRegister: () -> Unit,
    onFilters: () -> Unit, onExpand: (String) -> Unit, onPreview: (String) -> Unit
) {
    val importing = state.browser.collection == PhotoCollection.CAMERA_IMPORT
    val filtering = state.filter.hasConditions()
    val cached = remember(state.assets) { state.assets.count { it.inImportList } }
    val browsingCamera = state.camera.connected || (state.browser.showOfflinePreviews && cached > 0)
    if (importing && !browsingCamera) {
        Column(Modifier.fillMaxSize()) {
            CameraScreen(state, actions, onBrowse = { actions.onSetCollection(PhotoCollection.CAMERA_IMPORT) },
                onRegister = onRegister, modifier = Modifier.weight(1f))
            if (cached > 0) TextButton(onClick = { actions.onSetOfflinePreviews(true) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("查看 $cached 张离线预览")
            }
        }
        return
    }
    val mixedFormats = remember(state.catalog) { state.catalog.groups.mapTo(hashSetOf()) { it.members.first().format }.size > 1 }
    val grid = key(if (importing) state.camera.catalogReadId else null, if (importing) state.filter.sortOrder else null) { rememberLazyGridState() }
    if (importing) CatalogPrefetch(grid, state, actions.onAutoLoadMoreCamera)
    Column(Modifier.fillMaxSize()) {
        if (importing) CameraStatusCard(state, actions, onCamera)
        if (!importing) {
            SingleChoiceSegmentedButtonRow(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 320.dp).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)) {
                LibrarySection.entries.forEachIndexed { index, section ->
                    SegmentedButton(selected = state.browser.librarySection == section, onClick = { onLibrarySection(section) },
                        shape = SegmentedButtonDefaults.itemShape(index, LibrarySection.entries.size)) { Text(section.label) }
                }
            }
            state.browser.exportSourceId?.let { id ->
                val source = remember(id, state.assets) { state.assets.firstOrNull { it.id == id }?.name ?: "这张照片" }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(source, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onLibrarySection(LibrarySection.EXPORTS) }) { Text("查看全部导出版本") }
                }
            }
        } else if (browsingCamera) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    ImportVisibility.entries.forEachIndexed { index, visibility ->
                        SegmentedButton(selected = state.browser.importVisibility == visibility,
                            onClick = { actions.onSetImportVisibility(visibility) },
                            shape = SegmentedButtonDefaults.itemShape(index, ImportVisibility.entries.size)) { Text(visibility.label()) }
                    }
                }
                if (state.camera.connected && state.camera.make == CameraMake.SONY && state.camera.catalogDates.isNotEmpty()) {
                    SonyDatePicker(state.camera, state.filter.sortOrder, actions.onJumpToDay)
                }
            }
        }
        if (importing) (state.camera.catalogError ?: state.camera.message)?.let { message ->
            ErrorNotice(message, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                if (!state.camera.connected) TextButton(onClick = { onCamera(null) }) { Text("连接相机") }
                else if (state.camera.catalogError == null) TextButton(onClick = actions.onRefreshCamera, enabled = !state.camera.busy) { Text("重试") }
            }
        }
        if (importing && state.camera.connecting) Text(state.camera.stage, Modifier.padding(horizontal = 16.dp))
        val pending = state.catalog.pendingMetadataCount
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val summary = listOfNotNull(state.filter.summary().takeIf { filtering }, "$pending 张信息不全".takeIf { pending > 0 })
            Text(summary.joinToString(" · ").ifEmpty { "全部照片" }, Modifier.weight(1f).clickable(onClick = onFilters).padding(vertical = 12.dp),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CatalogSortMenu(state.filter.sortOrder) { actions.onFilterChange(state.filter.copy(sortOrder = it)) }
            if (filtering) TextButton(onClick = { actions.onFilterChange(FilterSpec(sortOrder = state.filter.sortOrder)) }) { Text("重置") }
        }
        if (state.isLoading || (importing && state.camera.busy)) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (state.catalog.groups.isEmpty()) BrowserEmptyState(state, actions, { onCamera(null) }, onFilters, cached)
        else LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), state = grid, contentPadding = PaddingValues(4.dp),
            modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.catalog.groups, key = { it.id }) { group ->
                val photo = group.members.first()
                PhotoCard(photo, photo.id in state.selectedIds, { actions.onToggleSelection(photo.id) }, onOpen = { onPreview(photo.id) },
                    selecting = selecting, badge = originalOf(photo).badge, showFormat = mixedFormats, groupSize = group.members.size,
                    onExpand = { onExpand(group.id) })
            }
            if (importing && state.camera.connected && state.camera.hasMore) item(key = "catalog-load-more", span = { GridItemSpan(maxLineSpan) }) {
                FilledTonalButton(onClick = actions.onLoadMoreCamera, enabled = !state.camera.busy && !state.camera.connecting,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(state.camera.loadMoreLabel())
                }
            }
        }
    }
}

@Composable
private fun CatalogSortMenu(order: SortOrder, onChange: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(order.label()) }
        DropdownMenu(open, { open = false }) {
            SortOrder.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label()) },
                onClick = { open = false; if (option != order) onChange(option) }) }
        }
    }
}

private fun CameraState.loadMoreLabel(): String = when {
    catalogError != null -> "重试"
    readingCatalog -> "正在读取"
    catalogStopped -> "继续加载"
    else -> catalogBatchSize?.let { "加载下一批 $it 张" } ?: "加载剩余照片"
}

@Composable
private fun CatalogPrefetch(grid: LazyGridState, state: LibraryUiState, load: (Long) -> Unit) {
    val latest by rememberUpdatedState(state)
    val onLoad by rememberUpdatedState(load)
    LaunchedEffect(grid) {
        var requested: Pair<Long, Any>? = null
        snapshotFlow {
            val camera = latest.camera
            if (!camera.canAutoLoadCatalog) return@snapshotFlow null
            val groups = latest.catalog.groups
            val layout = grid.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull { item ->
                val group = groups.getOrNull(item.index)
                group != null && group.id == item.key && group.members.first().id in camera.catalogIds
            }
            if (last != null && groups.lastIndex - last.index <= layout.maxSpan * 6) {
                camera.catalogReadId to last.key
            } else null
        }.collectLatest { edge ->
            if (edge == null || edge == requested) return@collectLatest
            delay(150)
            requested = edge
            onLoad(edge.first)
        }
    }
}

@Composable
private fun SonyDatePicker(camera: CameraState, sort: SortOrder, onDay: (SonyCaptureDay) -> Unit) {
    var open by remember(camera.generation) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("日期") }
    if (!open) return
    val dates = remember(camera.catalogDates, sort) { orderedSonyDates(camera.catalogDates, sort.catalogDirection()) }
    AlertDialog(onDismissRequest = { open = false }, title = { Text("跳到拍摄日期") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(dates, key = { "${it.slot}:${it.localMillis}" }) { day ->
                val date = LocalDate.ofEpochDay(day.epochDay)
                TextButton(onClick = { open = false; onDay(day) }, modifier = Modifier.fillMaxWidth()) {
                    Text("$date · 卡槽 ${day.slot}" + if (day == camera.catalogStartDay) " ✓" else "")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } })
}

@Composable
private fun BrowserEmptyState(state: LibraryUiState, actions: LibraryActions, onCamera: () -> Unit, onFilters: () -> Unit, cached: Int) {
    val importing = state.browser.collection == PhotoCollection.CAMERA_IMPORT
    val filtering = state.filter.hasConditions()
    val disconnecting = importing && state.camera.connectionPhase == ConnectionPhase.DISCONNECTING
    val exports = !importing && state.browser.librarySection == LibrarySection.EXPORTS
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BridgeIcon(if (importing) BridgeGlyph.CAMERA else BridgeGlyph.PHOTOS, modifier = Modifier.size(64.dp))
            val title = when {
                disconnecting -> "正在断开相机"
                importing && state.camera.connecting -> "正在连接相机"
                importing && state.camera.manuallyDisconnected && !state.browser.showOfflinePreviews -> "相机已断开"
                importing && !state.camera.connected && !state.browser.showOfflinePreviews -> cameraConnectionSummary(state).title
                importing && state.camera.catalogError != null -> "目录读取中断"
                importing && state.camera.readingCatalog -> "正在读取相机目录"
                importing && state.camera.catalogStopped -> "已停止读取"
                filtering -> "没有匹配的照片"
                importing && state.browser.importVisibility == ImportVisibility.NEW -> "没有未导入的照片"
                importing -> "没有照片"
                exports -> "暂无导出版本"
                else -> "图库还没有照片"
            }
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            when {
                disconnecting -> Unit
                importing && state.camera.connected && state.camera.hasMore ->
                    PrimaryAction(state.camera.loadMoreLabel(), actions.onLoadMoreCamera, enabled = !state.camera.busy && !state.camera.connecting)
                importing && state.camera.connecting -> TextButton(onClick = actions.onDisconnectCamera) { Text("取消连接") }
                importing && !state.camera.connected -> {
                    val saved = state.settings.selectedAddress.isNotBlank()
                    if (saved && !state.settings.sonySelected) {
                        Text("蓝牙便携图会保存到图库和系统相册；浏览相机原片时再连接 Wi-Fi", textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PrimaryAction(if (saved) "切换到 Wi-Fi" else "连接相机", if (saved) actions.onWakeWifi else onCamera, glyph = BridgeGlyph.CAMERA)
                    if (cached > 0 && !state.browser.showOfflinePreviews) {
                        TextButton(onClick = { actions.onSetOfflinePreviews(true) }) { Text("查看 $cached 张离线预览") }
                    }
                }
                !importing && !exports && !filtering -> {
                    PrimaryAction("从相机导入", { actions.onSetCollection(PhotoCollection.CAMERA_IMPORT) }, glyph = BridgeGlyph.DOWNLOAD)
                    TextButton(onClick = actions.onImport) { Text("添加手机照片") }
                }
            }
            if (filtering) TextButton(onClick = onFilters) { Text("调整筛选") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoCard(
    asset: PhotoAsset, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit = onToggle, selecting: Boolean = false,
    badge: String? = null, showFormat: Boolean = false, groupSize: Int = 1, onExpand: () -> Unit = {}, showSelection: Boolean = false
) {
    val inset by animateDpAsState(if (selected) 8.dp else 0.dp, label = "photo selection")
    val shape = MaterialTheme.shapes.extraSmall
    Box(Modifier.fillMaxWidth().aspectRatio(1f)
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape)
        .semantics { if (selecting) this.selected = selected }
        .combinedClickable(onClick = if (selecting) onToggle else onOpen, onLongClickLabel = "选择照片", onLongClick = onToggle)) {
        PhotoImage(asset, Modifier.fillMaxSize().padding(inset).clip(shape).alpha(if (asset.pickState == PickState.REJECTED) 0.45f else 1f))
        badge?.let { TileLabel(it, Modifier.align(Alignment.TopStart).padding(inset + 6.dp)) }
        if (selecting || selected || showSelection) Box(Modifier.align(Alignment.TopEnd).size(48.dp)
            .clickable(onClickLabel = if (selected) "取消选择" else "选择照片", onClick = onToggle), contentAlignment = Alignment.TopEnd) {
            SelectionMark(selected, Modifier.padding(6.dp))
        }
        Row(Modifier.align(Alignment.BottomStart).padding(inset + 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showFormat) TileLabel(asset.format.label())
            if (asset.rating > 0) TileLabel("★ ${asset.rating}")
        }
        if (groupSize > 1) Box(Modifier.align(Alignment.BottomEnd).size(56.dp).clickable(onClickLabel = "展开分组", onClick = onExpand),
            contentAlignment = Alignment.BottomEnd) {
            TileLabel("$groupSize 张", Modifier.padding(inset + 6.dp))
        }
    }
}

@Composable
internal fun TileLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
}

@Composable
internal fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.size(28.dp), shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.3f),
        contentColor = MaterialTheme.colorScheme.onPrimary, border = if (selected) null else BorderStroke(2.dp, Color.White)) {
        Box(contentAlignment = Alignment.Center) { if (selected) BridgeIcon(BridgeGlyph.CHECK, modifier = Modifier.size(18.dp)) }
    }
}

private fun FilterSpec.summary(): String {
    val parts = buildList {
        if (formats.isNotEmpty()) add(formats.joinToString("/") { it.label() })
        if (minimumRating > 0 || maximumRating < 5) add(if (maximumRating == 5) "$minimumRating 星以上" else "$minimumRating–$maximumRating 星")
        if (pickStates.isNotEmpty()) add(pickStates.joinToString("/") { it.label() })
        if (downloadStates.isNotEmpty()) add(downloadStates.joinToString("/") { it.label() })
        if (text.isNotBlank()) add("“${text.trim()}”")
    }
    val rest = hasAdvancedConditions()
    return when {
        parts.isEmpty() && !rest -> "全部照片"
        rest -> (parts + "更多条件").joinToString(" · ")
        else -> parts.joinToString(" · ")
    }
}

private fun ImportVisibility.label(): String = when (this) {
    ImportVisibility.NEW -> "未导入"
    ImportVisibility.ALL -> "全部"
    ImportVisibility.IMPORTED -> "已导入"
}
