package org.openbridge.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PhotoEditTab
import org.openbridge.app.domain.photoEditSession

internal typealias PhotoEditMenuContent = @Composable (closeMenu: () -> Unit) -> Unit
internal typealias PhotoEditHeaderContent = @Composable (PhotoEditMenuContent) -> Unit

/** The retained session owns drafts/history; this dialog owns preview buffers and views. Drafts are never written to disk. */
@Composable
internal fun PhotoEditScreen(
    assets: List<PhotoAsset>, session: PhotoEditorState, state: LibraryUiState, actions: LibraryActions, onDismiss: () -> Unit
) {
    val allowLog = assets.all { it.format == MediaFormat.RAW }
    var draftLook by session.look
    var draftFrame by session.frame
    var draftLookKey by session.lookKey
    var awaitingImport by session.awaitingImport
    var tab by session.tab
    var index by session.index
    var reload by session.reload
    var exporting by session.exporting
    var exportDraft by session.exportDraft
    var confirmingClose by session.confirmingClose
    var ready by remember { mutableStateOf(false) }
    val frameScope = rememberCoroutineScope()
    val frameSnackbar = remember { SnackbarHostState() }
    val currentIndex = index.coerceIn(0, assets.lastIndex)
    LaunchedEffect(assets.size) { session.clampIndex(assets.size) }
    val asset = assets[currentIndex]
    var metadataOverride by remember(asset.uri, reload) { mutableStateOf<Map<String, String>?>(null) }
    val frameState by session.frameState
    val editor = frameState.getOrNull()
    var seenDeletions by session.seenDeletions
    LaunchedEffect(state.deletedFrameTemplateIds) {
        val deleted = state.deletedFrameTemplateIds - seenDeletions
        if (draftFrame?.id in deleted) { editor?.clear(); draftFrame = null }
        seenDeletions = state.deletedFrameTemplateIds
    }
    LaunchedEffect(state.luts, state.removedLuts, allowLog) {
        if ((!allowLog && draftLook.usesLog) ||
            (draftLook.lutId != null && (state.luts + state.removedLuts).none { it.id == draftLook.lutId })) {
            session.changeLook(draftLook.withoutLut()); draftLookKey = null
        }
    }
    LaunchedEffect(state.colorImportRevision) {
        if (awaitingImport != null && awaitingImport != state.colorImportRevision) {
            val imported = state.luts.firstOrNull { it.look().lutKey == state.importedLookKey }?.look()
                ?: state.colorPresets.firstOrNull { "preset:${it.id}" == state.importedLookKey }?.look
            if (imported != null && imported.usage.unavailableReason == null && (allowLog || !imported.usesLog)) {
                session.changeLook(if (state.importedLookKey?.startsWith("lut:") == true) draftLook.withLut(imported) else imported)
                draftLookKey = state.importedLookKey
            }
            awaitingImport = null
        }
    }
    fun commit() {
        session.endColorGesture()
        editor?.endGesture()
        val committed = photoEditSession(draftLook, draftFrame)
        actions.onSetLook(committed.look ?: ColorLookSettings())
        actions.onSetFrame(committed.frame)
    }
    fun close() { if (session.requestClose()) onDismiss() }
    fun switchTo(value: PhotoEditTab) { session.endColorGesture(); editor?.endGesture(); tab = value; ready = false }
    val sources = rememberPhotoEditSource(asset, reload)
    val graded = key(asset.uri, reload) { rememberGradedPhoto(sources, draftLook, tab == PhotoEditTab.FRAME || sources.raw) }
    val holder = rememberSaveableStateHolder()
    val header: PhotoEditHeaderContent = { extra ->
        PhotoEditHeader(asset.name, tab, editor, ready && editor?.busy != true, draftFrame != null,
            onClose = ::close, onTab = ::switchTo,
            onExport = { session.endColorGesture(); editor?.endGesture(); exporting = true },
            onResetLook = { session.endColorGesture(); session.changeLook(ColorLookSettings()); draftLookKey = null },
            onClearFrame = { editor?.clear(); draftFrame = null }, extraMenu = extra)
    }
    val pager: @Composable BoxScope.() -> Unit = {
        PhotoEditPager(currentIndex, assets.size, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) { next ->
            editor?.endGesture(); editor?.selectedId = null; index = next; ready = false
        }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(enabled = !exporting && !confirmingClose, onBack = ::close)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.fillMaxSize()) {
                    key(asset.uri, reload) {
                        holder.SaveableStateProvider(tab) {
                            if (tab == PhotoEditTab.COLOR) ColorEditor(asset, state, actions, draftLook, {
                                session.changeLook(it); if (it.isIdentity) draftLookKey = null
                            }, draftLookKey, { draftLookKey = it }, onImport = {
                                awaitingImport = state.colorImportRevision; actions.onImportColor()
                            },
                                photos = sources, graded = graded, allowLog = allowLog,
                                canUndo = session.canUndoColor, canRedo = session.canRedoColor,
                                onUndo = session::undoColor, onRedo = session::redoColor,
                                onGesture = { if (it) session.beginColorGesture() else session.endColorGesture() },
                                onReady = { ready = it }, onRetry = { reload++; ready = false },
                                header = header, status = {
                                    EditStatus("边框 · " + (draftFrame?.name ?: "无")) { switchTo(PhotoEditTab.FRAME) }
                                }, pager = pager)
                            else if (editor != null) FrameEditorScreen(asset, graded, metadataOverride ?: sources.values, editor, state, actions,
                                operationScope = frameScope, snackbar = frameSnackbar,
                                onMetadataChanged = { metadataOverride = it },
                                onReady = { ready = it && graded.renderedLook == draftLook }, onRetry = { reload++; ready = false },
                                header = header, status = {
                                    EditStatus("调色 · " + if (draftLook.isIdentity) "原图" else draftLook.name) { switchTo(PhotoEditTab.COLOR) }
                                }, pager = pager)
                            else {
                                header { _ -> }
                                FrameEditorLoadError(frameState.exceptionOrNull()?.message ?: "无法读取边框模板",
                                    onRetry = session::retryFrame, onDismiss = { switchTo(PhotoEditTab.COLOR) })
                            }
                        }
                    }
                }
                SnackbarHost(frameSnackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
        if (exporting) ExportDialog(assets, draftLook, draftFrame, state.settings.exportPreferences,
            onChange = { target, choices -> exportDraft = choices; exporting = false; switchTo(target) }, onExport = {
                commit(); actions.onExportPhotos(assets.mapTo(linkedSetOf()) { photo -> photo.id }, it); onDismiss()
            }, onDismiss = { exporting = false; exportDraft = null }, initial = exportDraft)
        if (confirmingClose) AlertDialog(onDismissRequest = { confirmingClose = false }, title = { Text("保留修改？") },
            text = { Text("可以保留本次编辑，或放弃修改并关闭。") }, confirmButton = {
                TextButton(onClick = { confirmingClose = false; commit(); onDismiss() }) { Text("保留修改") }
            }, dismissButton = {
                Row {
                    TextButton(onClick = { confirmingClose = false; onDismiss() }) { Text("放弃修改") }
                    TextButton(onClick = { confirmingClose = false }) { Text("取消") }
                }
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoEditHeader(
    name: String, tab: PhotoEditTab, editor: FrameEditorState?, ready: Boolean, hasFrame: Boolean,
    onClose: () -> Unit, onTab: (PhotoEditTab) -> Unit, onExport: () -> Unit, onResetLook: () -> Unit, onClearFrame: () -> Unit,
    extraMenu: PhotoEditMenuContent
) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { BridgeIcon(BridgeGlyph.CLOSE, "关闭编辑") }
            Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Button(onClick = onExport, enabled = ready) { Text("导出") }
            Box {
                IconButton(onClick = { menu = true }) { BridgeIcon(BridgeGlyph.MORE, "编辑选项") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("还原调色") }, onClick = { menu = false; onResetLook() })
                    DropdownMenuItem(text = { Text("移除边框") }, enabled = hasFrame && editor?.busy != true,
                        onClick = { menu = false; onClearFrame() })
                    if (tab == PhotoEditTab.FRAME && editor != null) { HorizontalDivider(); extraMenu { menu = false } }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                PhotoEditTab.entries.forEachIndexed { index, item ->
                    SegmentedButton(selected = tab == item, onClick = { onTab(item) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(item.label) }
                }
            }
            if (tab == PhotoEditTab.FRAME && editor != null) {
                IconButton(onClick = editor::undo, enabled = editor.canUndo && !editor.busy) { BridgeIcon(BridgeGlyph.UNDO, "撤销") }
                IconButton(onClick = editor::redo, enabled = editor.canRedo && !editor.busy) { BridgeIcon(BridgeGlyph.REDO, "重做") }
            }
        }
    }
}

@Composable
private fun EditStatus(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        BridgeIcon(BridgeGlyph.FORWARD)
    }
}

@Composable
private fun PhotoEditPager(index: Int, count: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    if (count < 2) return
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .94f),
        tonalElevation = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(enabled = index > 0, onClick = { onChange(index - 1) }) { BridgeIcon(BridgeGlyph.BACK, "上一张") }
            Text("${index + 1} / $count", style = MaterialTheme.typography.labelMedium)
            IconButton(enabled = index < count - 1, onClick = { onChange(index + 1) }) { BridgeIcon(BridgeGlyph.FORWARD, "下一张") }
        }
    }
}
