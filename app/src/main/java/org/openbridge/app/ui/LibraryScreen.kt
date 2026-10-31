package org.openbridge.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openbridge.app.camera.DownloadStatus
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.ExportStatus
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoCollection
import org.openbridge.app.domain.PhotoAsset

private enum class MainTab(val title: String, val glyph: BridgeGlyph, val collection: PhotoCollection? = null) {
    LIBRARY("图库", BridgeGlyph.PHOTOS, PhotoCollection.LIBRARY),
    IMPORT("导入", BridgeGlyph.DOWNLOAD, PhotoCollection.CAMERA_IMPORT),
    CAMERA("相机", BridgeGlyph.CAMERA), TASKS("任务", BridgeGlyph.TASKS), SETTINGS("设置", BridgeGlyph.SETTINGS)
}

@Composable
fun LibraryScreen(state: LibraryUiState, actions: LibraryActions, showAboutOnStart: Boolean = false) {
    CompositionLocalProvider(LocalCameraPreviewRequest provides actions.onRequestPreview,
        LocalCameraSessionGeneration provides state.camera.generation) {
        OpenBridgeTheme { LibraryContent(state, actions, showAboutOnStart) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryContent(state: LibraryUiState, actions: LibraryActions, showAboutOnStart: Boolean) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var showingAbout by rememberSaveable { mutableStateOf(showAboutOnStart) }
    var showingFilters by remember { mutableStateOf(false) }
    var showingSaveFilter by remember { mutableStateOf(false) }
    var showingSavedFilters by remember { mutableStateOf(false) }
    var showingExport by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<List<PhotoAsset>?>(null) }
    var cleanup by remember { mutableStateOf<CatalogCleanup?>(null) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    val importing = state.browser.collection == PhotoCollection.CAMERA_IMPORT
    var showingColorLibrary by remember { mutableStateOf(false) }
    var colorAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var showingTags by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf<List<String>>(emptyList()) }
    var expandedGroupId by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val page = MainTab.entries.getOrElse(tab) { MainTab.LIBRARY }
    fun navigate(destination: MainTab) {
        destination.collection?.let(actions.onSetCollection)
        tab = destination.ordinal
    }
    LaunchedEffect(state.browser.collection) {
        if (MainTab.entries.getOrNull(tab)?.collection != null) {
            tab = if (state.browser.collection == PhotoCollection.LIBRARY) MainTab.LIBRARY.ordinal else MainTab.IMPORT.ordinal
        }
    }
    val holder = rememberSaveableStateHolder()
    val selected = remember(state.assets, state.selectedIds) { state.assets.filter { it.id in state.selectedIds } }
    val colorAsset = selected.firstOrNull {
        it.downloadState == DownloadState.DOWNLOADED && it.format in setOf(MediaFormat.JPEG, MediaFormat.PNG, MediaFormat.HEIF)
    }
    val visibleIds = remember(state.catalog) { state.catalog.groups.flatMap { it.members }.map { it.id }.toSet() }
    val hiddenCount = selected.count { it.id !in visibleIds }
    val canDelete = selected.isNotEmpty() && selected.all { it.downloadState == DownloadState.DOWNLOADED && it.uri.startsWith("content:") }
    val compareAssets = remember(comparing, state.assets) { comparing.mapNotNull { id -> state.assets.find { it.id == id } } }
    val pendingTasks = state.tasks.count { it.status == ExportStatus.QUEUED || it.status == ExportStatus.RUNNING } +
        state.camera.tasks.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
    if (showingAbout) {
        AboutScreen(onBack = { showingAbout = false; navigate(MainTab.SETTINGS) })
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MediumTopAppBar(
                title = { Text(page.title, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (page.collection != null) {
                        IconButton(onClick = { showingFilters = true }) { BridgeIcon(BridgeGlyph.FILTER, "组合筛选") }
                        Box {
                            IconButton(onClick = { menu = true }) { BridgeIcon(BridgeGlyph.MORE, "照片选项") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("添加手机里的照片") }, onClick = { menu = false; actions.onImport() })
                                DropdownMenuItem(text = { Text("LUT 与预设") }, onClick = { menu = false; showingColorLibrary = true })
                                DropdownMenuItem(text = { Text("已保存的筛选") }, onClick = { menu = false; showingSavedFilters = true })
                                DropdownMenuItem(text = { Text("保存当前筛选") }, onClick = { menu = false; showingSaveFilter = true })
                                DropdownMenuItem(text = { Text("选择全部结果") }, onClick = { menu = false; actions.onSelectVisible() })
                                DropdownMenuItem(text = { Text(if (importing) "清空本次选片列表" else "清空图库列表") }, onClick = {
                                    menu = false
                                    cleanup = if (importing) CatalogCleanup.IMPORT_LIST else CatalogCleanup.LIBRARY_ALL
                                })
                            }
                        }
                    }
                    if (page == MainTab.CAMERA && state.camera.connected) {
                        IconButton(onClick = actions.onRefreshCamera, enabled = !state.camera.busy) { BridgeIcon(BridgeGlyph.REFRESH, "刷新相机目录") }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                AnimatedVisibility(page.collection != null && selected.isNotEmpty()) {
                    SelectionBar(selected.size, hiddenCount, actions,
                        onCompare = { if (selected.size == 1) previewId = selected.single().id else comparing = selected.map { it.id } },
                        onExport = { showingExport = true }, onTags = { showingTags = true },
                        canGrade = colorAsset != null, onGrade = { colorAssetId = colorAsset?.id }, importing = importing,
                        batchLimit = state.camera.catalogBatchSize,
                        onRemove = { cleanup = if (importing) CatalogCleanup.IMPORT_SELECTED else CatalogCleanup.LIBRARY_SELECTED },
                        canDelete = canDelete, onDelete = { deleting = selected.toList() })
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                    MainTab.entries.forEach { destination ->
                        NavigationBarItem(selected = page == destination, onClick = { navigate(destination) },
                            label = { Text(destination.title) }, icon = {
                            BadgedBox(badge = { if (destination == MainTab.TASKS && pendingTasks > 0) Badge { Text(pendingTasks.toString()) } }) {
                                BridgeIcon(destination.glyph)
                            }
                        })
                    }
                }
            }
        },
        floatingActionButton = {
            if (page == MainTab.LIBRARY && state.catalog.groups.isNotEmpty() && selected.isEmpty()) {
                ExtendedFloatingActionButton(onClick = actions.onImport, icon = { BridgeIcon(BridgeGlyph.ADD) }, text = { Text("添加照片") })
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.errorMessage?.let { message ->
                Surface(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = actions.onDismissError) { BridgeIcon(BridgeGlyph.CLOSE, "关闭提示") }
                    }
                }
            }
            holder.SaveableStateProvider(tab) {
                when (page) {
                    MainTab.LIBRARY, MainTab.IMPORT -> PhotoBrowserScreen(state, actions, onCamera = { navigate(MainTab.CAMERA) },
                        onFilters = { showingFilters = true },
                        onSavedFilters = { showingSavedFilters = true }, onExpand = { expandedGroupId = it }, onPreview = { previewId = it })
                    MainTab.CAMERA -> CameraScreen(state, actions) { navigate(MainTab.IMPORT) }
                    MainTab.TASKS -> TransferScreen(state, actions)
                    MainTab.SETTINGS -> SettingsScreen(state, actions, onWifiConnection = { navigate(MainTab.CAMERA) },
                        onAbout = { showingAbout = true })
                }
            }
        }
    }
    if (showingFilters) FilterDialog(state.filter, actions.onFilterChange) { showingFilters = false }
    if (showingSaveFilter) TextEntryDialog("保存筛选", "给这组条件起个名字", onConfirm = actions.onSaveFilter) { showingSaveFilter = false }
    if (showingSavedFilters) SavedFiltersDialog(state, actions) { showingSavedFilters = false }
    if (showingTags) TextEntryDialog("编辑标签", "逗号分隔，将替换所选照片的标签", allowEmpty = true,
        onConfirm = { actions.onSetTags(it.split(',', '，').map(String::trim).filter(String::isNotEmpty).toSet()) }) { showingTags = false }
    if (showingExport) ExportDialog(selected, state.look, actions.onExport) { showingExport = false }
    if (showingColorLibrary) ColorLibrarySheet(state, actions, actions.onSetLook) { showingColorLibrary = false }
    state.assets.find { it.id == colorAssetId }?.let { asset ->
        ColorEditor(asset, state, actions) { colorAssetId = null }
    }
    if (compareAssets.size in 2..4) CompareDialog(compareAssets) { comparing = emptyList() }
    state.assets.find { it.id == previewId }?.let { photo ->
        CompareDialog(listOf(photo), state.selectedIds, actions.onToggleSelection) { previewId = null }
    }
    cleanup?.let { action ->
        CatalogCleanupDialog(action, onConfirm = {
            if (action == CatalogCleanup.IMPORT_LIST) actions.onClearImportList()
            else if (action == CatalogCleanup.IMPORT_SELECTED) actions.onRemoveFromImport()
            else actions.onRemoveFromLibrary(action == CatalogCleanup.LIBRARY_ALL)
            cleanup = null
        }) { cleanup = null }
    }
    deleting?.let { assets ->
        DeletePhotosDialog(assets, onConfirm = { actions.onDeletePhotos(assets); deleting = null }) { deleting = null }
    }
    state.catalog.groups.find { it.id == expandedGroupId }?.let { group ->
        GroupDialog(group, state.selectedIds, actions.onToggleSelection) { expandedGroupId = null }
    }
}

