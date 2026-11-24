package org.prooftray.app.ui

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.prooftray.app.domain.allLayers
import org.prooftray.app.domain.FrameBackgroundKind
import org.prooftray.app.domain.FrameLayer
import org.prooftray.app.domain.FrameLayerKind
import org.prooftray.app.domain.FrameTemplate
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.frame.FrameFont
import org.prooftray.app.frame.FrameMetadataReader
import org.prooftray.app.frame.FramePreset
import org.prooftray.app.frame.FrameRenderSession
import org.prooftray.app.frame.FrameRepository
import org.prooftray.app.frame.FrameTemplates

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FrameEditorScreen(
    asset: PhotoAsset, source: ColorPreviewSource, metadata: Map<String, String>, editor: FrameEditorState,
    state: LibraryUiState, actions: LibraryActions, operationScope: CoroutineScope, snackbar: SnackbarHostState,
    onMetadataChanged: (Map<String, String>) -> Unit,
    onReady: (Boolean) -> Unit, onRetry: () -> Unit,
    header: PhotoEditHeaderContent, status: @Composable () -> Unit, pager: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val repository = remember(context) { FrameRepository(context) }
    val scope = operationScope
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val revision = editor.resourcesRevision
    var properties by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<FramePreset?>(null) }
    val busy = editor.busy
    var imageTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var fontTarget by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(editor.issue) {
        editor.issue?.let { message -> editor.issue = null; snackbar.showSnackbar(message) }
    }

    fun report(error: Throwable) { scope.launch { snackbar.showSnackbar(error.message ?: "操作未完成") } }
    fun operation(block: suspend () -> Unit) {
        if (editor.busy) return
        editor.busy = true
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { snackbar.showSnackbar("可用内存不足") }
            catch (error: Exception) { snackbar.showSnackbar(error.message ?: "操作未完成") }
            finally { editor.busy = false }
        }
    }

    val sourceBitmap = rememberEditBitmap(source.image)
    val width = source.bitmap?.width ?: asset.width ?: 1200
    val height = source.bitmap?.height ?: asset.height ?: 1600
    val values = metadata
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) operation { onMetadataChanged(withContext(Dispatchers.IO) { FrameMetadataReader.read(context, asset) }) }
        else scope.launch {
            if (snackbar.showSnackbar("未获得照片位置读取权限", actionLabel = "去设置") == SnackbarResult.ActionPerformed) actions.onOpenAppSettings()
        }
    }
    fun showGps(enabled: Boolean) {
        editor.change { it.copy(showGps = enabled) }
        if (enabled && values["gps"].isNullOrBlank() && Uri.parse(asset.uri).authority == MediaStore.AUTHORITY &&
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
    val current = editor.template
    val request = FramePreviewRequest(source.image, current, values, revision)
    val preview = rememberFramePreview(request, repository, editor.enabled)
    val previewBitmap = rememberEditBitmap(preview.image)
    val presets by produceState<List<FramePreset>>(emptyList(), revision, state.deletedFrameTemplateIds) {
        value = withContext(Dispatchers.IO) { repository.presets() }
    }
    val fonts by produceState<List<FrameFont>>(emptyList(), revision) { value = withContext(Dispatchers.IO) { repository.fonts() } }
    val design = current.forPhoto(width, height)
    val selected = design.allLayers().find { it.id == editor.selectedId }
    val error = source.error ?: preview.error

    val importTemplate = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            val template = withContext(Dispatchers.IO) { repository.importTemplate(uri) }
            editor.replace(template); editor.resourcesChanged(); tab = 0
            repository.problem(template)?.let { snackbar.showSnackbar(it) }
        }
    }
    val importImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            val target = imageTarget
            val layer = design.allLayers().find { it.id == target }
            val id = withContext(Dispatchers.IO) {
                repository.importImage(uri).also { image ->
                    if (layer?.autoLogo == true && !values["make"].isNullOrBlank()) {
                        repository.bindLogo(values.getValue("make"), image)
                    }
                }
            }
            when {
                target == "background" -> editor.updateDesign(width, height) {
                    it.copy(canvas = it.canvas.copy(background = it.canvas.background.copy(kind = FrameBackgroundKind.IMAGE, imageId = id)))
                }
                layer != null -> editor.updateLayer(width, height, layer.id) { it.copy(imageId = id) }
                else -> editor.add(width, height, FrameEditorState.newLayer(FrameLayerKind.IMAGE).copy(imageId = id))
            }
            editor.resourcesChanged()
        }
    }
    val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            val font = withContext(Dispatchers.IO) { repository.importFont(uri) }
            val target = editor.template.forPhoto(width, height).allLayers().find { it.id == fontTarget }
            if (target?.kind == FrameLayerKind.TEXT) editor.updateLayer(width, height, target.id) { it.copy(text = it.text.copy(font = font.id)) }
            else editor.allFonts(font.id)
            editor.resourcesChanged()
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) operation {
            val portable = repository.portable(editor.template, values["make"].orEmpty())
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")?.use { repository.writePackage(portable, it) } ?: error("无法保存模板")
            }
            snackbar.showSnackbar("模板已保存")
        }
    }
    SideEffect { onReady(!busy && sourceBitmap != null && (!editor.enabled || preview.request == request) && error == null) }
    BackHandler(enabled = tab == 1 && properties && selected != null) { editor.endGesture(); properties = false }
    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            header { closeMenu ->
                DropdownMenuItem(text = { Text("另存为我的模板") }, onClick = {
                    closeMenu(); saveName = current.name; saving = true
                })
                DropdownMenuItem(text = { Text("导入模板") }, onClick = { closeMenu(); importTemplate.launch(arrayOf("*/*")) })
                DropdownMenuItem(text = { Text("保存模板文件") }, onClick = {
                    closeMenu(); saveFile.launch(current.name.take(60) + ".obframepack")
                })
                DropdownMenuItem(text = { Text("分享模板") }, onClick = {
                    closeMenu()
                    operation {
                        val file = withContext(Dispatchers.IO) {
                            repository.shareFile(repository.portable(current, values["make"].orEmpty()))
                        }
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".frames", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = android.content.ClipData.newRawUri("边框模板", uri)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享模板"))
                    }
                })
            }
            status()
            state.errorMessage?.let { message ->
                ErrorNotice(message) { TextButton(onClick = actions.onDismissError) { Text("关闭") } }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val wide = maxWidth >= 700.dp
                val thumbnailsHeight = 128.dp
                val controlsHeight = (maxHeight * 0.38f).coerceIn(176.dp, 300.dp)
                    .coerceAtMost((maxHeight - thumbnailsHeight - 72.dp).coerceAtLeast(96.dp))
                val previewContent: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                        when {
                            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                                TextButton(onClick = { onRetry(); editor.resourcesChanged() }) { Text("重试") }
                            }
                            !editor.enabled && sourceBitmap != null -> Image(sourceBitmap.asImageBitmap(), asset.name,
                                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            preview.rendered != null && previewBitmap != null && sourceBitmap != null ->
                                FrameCanvasPreview(checkNotNull(preview.rendered), editor, width, height,
                                    MaterialTheme.colorScheme.primary, Modifier.fillMaxSize())
                            else -> LoadingIndicator()
                        }
                        pager()
                        if (editor.enabled && selected != null) Surface(
                            Modifier.align(Alignment.TopCenter).padding(top = 8.dp).widthIn(max = 360.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f), tonalElevation = 3.dp
                        ) {
                            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(selected.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge)
                                IconButton(onClick = { tab = 1; properties = true }) { AppIcon(AppGlyph.EDIT, "编辑图层") }
                                IconButton(onClick = {
                                    runCatching { editor.duplicate(width, height, selected.id) }.onFailure(::report)
                                }) { AppIcon(AppGlyph.COPY, "复制图层") }
                                IconButton(onClick = { editor.remove(width, height, selected.id); properties = false }) {
                                    AppIcon(AppGlyph.DELETE, "删除图层")
                                }
                            }
                        }
                    }
                }
                val templates: @Composable (Modifier) -> Unit = { modifier ->
                    FrameTemplatePane(presets, if (editor.enabled) current.id else "", source.image, values, repository, revision,
                        onSelect = { editor.replace(it); properties = false }, onDelete = { deleting = it },
                        onBlank = { editor.replace(FrameTemplates.blank()); tab = 1; properties = false }, modifier = modifier)
                }
                val controls: @Composable (Modifier) -> Unit = { modifier ->
                    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("画布", "图层").forEachIndexed { index, label ->
                                    FilterChip(selected = tab == index, onClick = { editor.endGesture(); tab = index },
                                        label = { Text(label) }, modifier = Modifier.weight(1f))
                                }
                            }
                            if (tab == 0) FrameCanvasProperties(editor, width, height, fonts,
                                onImage = { imageTarget = "background"; importImage.launch(arrayOf("image/*")) },
                                onFont = { fontTarget = null; importFont.launch(arrayOf("*/*")) }, onGps = ::showGps)
                            else if (properties && selected != null) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { editor.endGesture(); properties = false }) {
                                        AppIcon(AppGlyph.BACK); Text("图层列表")
                                    }
                                    Text(selected.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelMedium)
                                }
                                key(selected.id) {
                                    FrameLayerProperties(selected, fonts, design.layers.none { it.id == selected.id },
                                        sections = editor.expandedSections, onSection = editor::toggleSection,
                                        change = { transform -> editor.updateLayer(width, height, selected.id, transform) },
                                        begin = editor::beginGesture, end = editor::endGesture,
                                        onImage = { imageTarget = selected.id; importImage.launch(arrayOf("image/*")) },
                                        onFont = { fontTarget = selected.id; importFont.launch(arrayOf("*/*")) },
                                        onDetach = {
                                            preview.rendered?.plan?.placements?.find { it.layer.id == selected.id }?.let {
                                                editor.detach(width, height, it)
                                            }
                                        },
                                        onField = {
                                            if (it == "gps") showGps(true)
                                            else if (it == "time") editor.change { template -> template.copy(showTime = true) }
                                        }, modifier = Modifier.weight(1f))
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("图层", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    Box {
                                        TextButton(onClick = { adding = true }) {
                                            AppIcon(AppGlyph.ADD)
                                            Text(if (selected?.kind == FrameLayerKind.GROUP) "添加到分组" else "添加")
                                        }
                                        DropdownMenu(adding, { adding = false }) {
                                            FrameLayerKind.entries.forEach { kind ->
                                                DropdownMenuItem(text = { Text(layerKindName(kind)) }, onClick = {
                                                    adding = false
                                                    if (kind == FrameLayerKind.IMAGE) {
                                                        imageTarget = null; importImage.launch(arrayOf("image/*"))
                                                    } else runCatching {
                                                        editor.add(width, height, FrameEditorState.newLayer(kind))
                                                    }.onFailure(::report)
                                                })
                                            }
                                        }
                                    }
                                }
                                FrameLayerList(design.layers, editor.selectedId,
                                    onSelect = { editor.endGesture(); editor.selectedId = it; properties = true },
                                    onToggle = { id -> editor.updateLayer(width, height, id) { it.copy(visible = !it.visible) } },
                                    onReorder = { id, delta -> editor.reorder(width, height, id, delta) })
                            }
                        }
                    }
                }
                if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    previewContent(Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(320.dp).fillMaxHeight()) {
                        templates(Modifier.fillMaxWidth().height(thumbnailsHeight))
                        controls(Modifier.fillMaxWidth().weight(1f))
                    }
                } else Column(Modifier.fillMaxSize()) {
                    previewContent(Modifier.fillMaxWidth().weight(1f))
                    templates(Modifier.fillMaxWidth().height(thumbnailsHeight))
                    controls(Modifier.fillMaxWidth().height(controlsHeight))
                }
            }
        }
        if (busy) Surface(Modifier.align(Alignment.TopCenter).padding(top = 56.dp), shape = MaterialTheme.shapes.large) {
            LoadingIndicator(Modifier.size(40.dp))
        }
    }
    if (saving) AlertDialog(onDismissRequest = { saving = false }, title = { Text("另存为我的模板") },
        text = { OutlinedTextField(saveName, { saveName = it.take(80) }, label = { Text("模板名称") }, singleLine = true) },
        confirmButton = { TextButton(enabled = saveName.isNotBlank() && !busy, onClick = {
            operation {
                val saved = withContext(Dispatchers.IO) { repository.save(editor.template, saveName) }
                editor.replace(saved); editor.resourcesChanged(); saving = false; snackbar.showSnackbar("模板已保存")
            }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { saving = false }) { Text("取消") } })
    deleting?.let { preset -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除“" + preset.name + "”？") },
        text = { Text("已排队的导出不受影响。") },
        confirmButton = { TextButton(onClick = {
            actions.onDeleteFrameTemplate(preset.id); deleting = null
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrameTemplatePane(presets: List<FramePreset>, selectedId: String, photo: EditBitmap?, values: Map<String, String>,
    repository: FrameRepository, revision: Int, onSelect: (FrameTemplate) -> Unit, onDelete: (FramePreset) -> Unit,
    onBlank: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId, presets.map { it.id }) {
        val index = if (selectedId == "user.blank") presets.size else presets.indexOfFirst { it.id == selectedId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), modifier = modifier.fillMaxWidth()) {
        items(presets, key = { it.id }) { preset ->
            var menu by remember(preset.id) { mutableStateOf(false) }
            val thumbnail = rememberEditResult(ColorPreviewSource(), preset.template, photo, revision, values) {
                if (photo == null || preset.template == null || preset.error != null) return@rememberEditResult ColorPreviewSource()
                try {
                    withContext(editRenderDispatcher) {
                        photo.retain().use { lease ->
                            FrameRenderSession(lease.bitmap, repository).use { session ->
                                val rendered = session.render(repository.snapshot(preset.template, values), 220,
                                    cancelled = { coroutineContext.ensureActive() })
                                ColorPreviewSource(own(rendered.bitmap))
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ColorPreviewSource() }
                catch (_: OutOfMemoryError) { ColorPreviewSource() }
            }
            val preview = rememberEditBitmap(thumbnail.image)
            Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(80.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(if (selectedId == preset.id) 3.dp else 0.dp,
                        if (selectedId == preset.id) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.medium)
                    .combinedClickable(onClick = { preset.template?.let(onSelect) }, onLongClickLabel = "模板操作",
                        onLongClick = if (preset.id.startsWith("user.")) ({ menu = true }) else null), contentAlignment = Alignment.Center) {
                    preview?.let { Image(it.asImageBitmap(), preset.name, Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit) }
                        ?: AppIcon(AppGlyph.FRAME, preset.error ?: preset.name)
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("删除模板", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete(preset) })
                    }
                }
                Text(preset.name, Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        item(key = "new-empty-frame") {
            Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(80.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(if (selectedId == "user.blank") 3.dp else 0.dp,
                        if (selectedId == "user.blank") MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.medium)
                    .clickable(onClick = onBlank), contentAlignment = Alignment.Center) {
                    AppIcon(AppGlyph.ADD, "空白模板")
                }
                Text("空白模板", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun FrameLayerList(layers: List<FrameLayer>, selected: String?, onSelect: (String) -> Unit, onToggle: (String) -> Unit,
    onReorder: (String, Int) -> Unit) {
    fun flatten(nodes: List<FrameLayer>, depth: Int): List<Pair<FrameLayer, Int>> =
        nodes.sortedByDescending { it.z }.flatMap { listOf(it to depth) + flatten(it.children, depth + 1) }
    LazyColumn {
        items(flatten(layers, 0), key = { it.first.id }) { (layer, depth) ->
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(if (selected == layer.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable { onSelect(layer.id) }.padding(start = (8 + depth * 12).dp), verticalAlignment = Alignment.CenterVertically) {
                if (layer.locked) AppIcon(AppGlyph.LOCK, "已锁定", Modifier.size(18.dp))
                Text(layer.name, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onReorder(layer.id, 1) }) { Text("↑") }
                TextButton(onClick = { onReorder(layer.id, -1) }) { Text("↓") }
                IconButton(onClick = { onToggle(layer.id) }) {
                    AppIcon(if (layer.visible) AppGlyph.VISIBLE else AppGlyph.HIDDEN, if (layer.visible) "隐藏图层" else "显示图层")
                }
            }
        }
        if (layers.isEmpty()) item { Text("添加文字、图片或色块", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium) }
    }
}

internal fun layerKindName(kind: FrameLayerKind): String = when (kind) {
    FrameLayerKind.TEXT -> "文字"; FrameLayerKind.IMAGE -> "图片"; FrameLayerKind.SHAPE -> "色块"
    FrameLayerKind.LINE -> "分隔线"; FrameLayerKind.GROUP -> "分组"
}

@Composable
internal fun FrameEditorLoadError(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Row {
            TextButton(onClick = onRetry) { Text("重试") }
            TextButton(onClick = onDismiss) { Text("返回调色") }
        }
    }
}
