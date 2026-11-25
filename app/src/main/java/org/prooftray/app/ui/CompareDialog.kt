package org.prooftray.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import org.prooftray.app.domain.PickState
import org.prooftray.app.domain.PhotoAsset

private data class PreviewTransform(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    /** Zooms around a focus given in viewport fractions (-0.5..0.5), then pans, keeping the image inside the viewport. */
    fun zoomed(zoom: Float, focusX: Float, focusY: Float, panX: Float = 0f, panY: Float = 0f): PreviewTransform {
        val next = (scale * zoom).coerceIn(1f, 8f)
        val ratio = next / scale
        val limit = (next - 1f) / 2f
        return PreviewTransform(next, (focusX + (x - focusX) * ratio + panX).coerceIn(-limit, limit),
            (focusY + (y - focusY) * ratio + panY).coerceIn(-limit, limit))
    }
}

/** Pinch always zooms; one-finger drags only pan when zoomed, so an enclosing pager keeps its swipe at 1×. */
private fun Modifier.photoZoom(key: Any, current: () -> PreviewTransform, onTransform: (PreviewTransform) -> Unit): Modifier = this
    .pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val old = current()
                val multi = event.changes.count { it.pressed } > 1
                if ((multi || old.scale > 1f) && size.width > 0 && size.height > 0) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    val focus = if (centroid == Offset.Unspecified) Offset(size.width / 2f, size.height / 2f) else centroid
                    val pan = event.calculatePan()
                    onTransform(old.zoomed(event.calculateZoom(), focus.x / size.width - 0.5f, focus.y / size.height - 0.5f,
                        pan.x / size.width, pan.y / size.height))
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(key) {
        detectTapGestures(onDoubleTap = { offset ->
            val old = current()
            onTransform(if (old.scale > 1f) PreviewTransform()
                else old.zoomed(2.5f, offset.x / size.width - 0.5f, offset.y / size.height - 0.5f))
        })
    }

/** Single-photo viewer: swipe between [assets], pick photos and jump to compare or color without returning to the grid. */
@Composable
fun PhotoViewer(
    assets: List<PhotoAsset>, startId: String, selectedIds: Set<String>, onToggleSelection: (String) -> Unit,
    detailOf: (PhotoAsset) -> String, importing: Boolean, canImport: (PhotoAsset) -> Boolean,
    onRate: (PhotoAsset, Int) -> Unit, onPick: (PhotoAsset, PickState) -> Unit,
    onExport: (PhotoAsset) -> Unit, onImport: (PhotoAsset) -> Unit, onCamera: () -> Unit, onCompare: (() -> Unit)?,
    canEdit: (PhotoAsset) -> Boolean, onEdit: (PhotoAsset) -> Unit,
    onFrame: (PhotoAsset) -> Unit,
    versionCount: (PhotoAsset) -> Int, onShowVersions: (PhotoAsset) -> Unit, onPromote: ((PhotoAsset) -> Unit)?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The camera catalog can change while the dialog is open. Update page count and content in the same composition.
        val pager = rememberPagerState(initialPage = assets.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { assets.size }
        var zoomed by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                val current = assets.getOrNull(pager.currentPage)
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { AppIcon(AppGlyph.CLOSE, "关闭") }
                    Column(Modifier.weight(1f)) {
                        Text(current?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (assets.size > 1) Text("${pager.currentPage + 1} / ${assets.size}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (current != null) {
                        val picked = current.id in selectedIds
                        IconButton(onClick = { onToggleSelection(current.id) },
                            modifier = Modifier.semantics { contentDescription = if (picked) "取消选择" else "选择" }) {
                            SelectionMark(picked)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed,
                        key = { page -> assets.getOrNull(page)?.id ?: page }) { page ->
                        val asset = assets.getOrNull(page) ?: return@HorizontalPager
                        ZoomablePhoto(asset) { if (page == pager.currentPage) zoomed = it }
                    }
                }
                if (current != null) {
                    ViewerActions(current, detailOf(current), selectedIds.size, importing, canImport(current), canEdit(current), onRate, onPick,
                        onExport, onImport, onCamera, onEdit, onFrame, onCompare, versionCount(current), onShowVersions, onPromote)
                }
            }
        }
    }
}

/** Rating and pick state take one tap each while culling; the primary action stays visible and the rest sits behind one menu. */
@Composable
private fun ViewerActions(
    photo: PhotoAsset, detail: String, selectedCount: Int, importing: Boolean, canImport: Boolean, canEdit: Boolean,
    onRate: (PhotoAsset, Int) -> Unit, onPick: (PhotoAsset, PickState) -> Unit,
    onExport: (PhotoAsset) -> Unit, onImport: (PhotoAsset) -> Unit, onCamera: () -> Unit,
    onEdit: (PhotoAsset) -> Unit, onFrame: (PhotoAsset) -> Unit, onCompare: (() -> Unit)?,
    versions: Int, onShowVersions: (PhotoAsset) -> Unit, onPromote: ((PhotoAsset) -> Unit)?
) {
    var more by remember(photo.id) { mutableStateOf(false) }
    val canCompare = onCompare != null && selectedCount in 2..4
    val canPromote = onPromote != null && photo.derivedFromId != null
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(detail, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        RatingStars(photo.rating) { onRate(photo, it) }
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PickState.PICKED to "入选", PickState.REJECTED to "排除").forEach { (pick, label) ->
            FilterChip(selected = photo.pickState == pick, label = { Text(label) },
                onClick = { onPick(photo, if (photo.pickState == pick) PickState.UNMARKED else pick) })
        }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = {
            if (!importing) onExport(photo) else if (canImport) onImport(photo) else onCamera()
        }) { Text(if (!importing) "导出" else if (canImport) "导入这张" else "连接相机") }
        if (canEdit || canCompare || versions > 0 || canPromote) Box {
            IconButton(onClick = { more = true }) { AppIcon(AppGlyph.MORE, "照片操作") }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                if (canEdit) {
                    DropdownMenuItem(text = { Text("调色") }, onClick = { more = false; onEdit(photo) })
                    DropdownMenuItem(text = { Text("边框") }, onClick = { more = false; onFrame(photo) })
                }
                if (canCompare) DropdownMenuItem(text = { Text("比较 $selectedCount 张") }, onClick = { more = false; onCompare?.invoke() })
                if ((canEdit || canCompare) && (versions > 0 || canPromote)) HorizontalDivider()
                if (versions > 0) DropdownMenuItem(text = { Text("$versions 个导出版本") },
                    onClick = { more = false; onShowVersions(photo) })
                if (canPromote) DropdownMenuItem(text = { Text("放入照片") }, onClick = { more = false; onPromote?.invoke(photo) })
            }
        }
    }
}

/** Tapping the current rating clears it, so 0–5 stars stay one tap away without a menu. */
@Composable
private fun RatingStars(rating: Int, onRate: (Int) -> Unit) {
    Row {
        (1..5).forEach { value ->
            val filled = value <= rating
            IconButton(onClick = { onRate(if (value == rating) 0 else value) }) {
                AppIcon(if (filled) AppGlyph.STAR_FILLED else AppGlyph.STAR,
                    if (value == rating) "已评 $value 星，清除评分" else "评为 $value 星",
                    tint = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(asset: PhotoAsset, onZoomChange: (Boolean) -> Unit) {
    var transform by remember(asset.id) { mutableStateOf(PreviewTransform()) }
    val latest by rememberUpdatedState(transform)
    val zoomed = transform.scale > 1f
    LaunchedEffect(zoomed) { onZoomChange(zoomed) }
    Box(Modifier.fillMaxSize().clipToBounds().photoZoom(asset.id, { latest }) { transform = it }, contentAlignment = Alignment.Center) {
        PhotoImage(asset, Modifier.fillMaxSize().graphicsLayer {
            scaleX = transform.scale
            scaleY = transform.scale
            translationX = transform.x * size.width
            translationY = transform.y * size.height
        }, maxEdge = 3072, contentScale = ContentScale.Fit, showSource = true)
    }
}

@Composable
fun CompareDialog(assets: List<PhotoAsset>, selectedIds: Set<String>, onToggleSelection: (String) -> Unit, onDismiss: () -> Unit) {
    var linked by remember { mutableStateOf(true) }
    var shared by remember { mutableStateOf(PreviewTransform()) }
    var individual by remember { mutableStateOf(emptyMap<String, PreviewTransform>()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { AppIcon(AppGlyph.CLOSE, "关闭比较") }
                    Text("比较 ${assets.size} 张", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("同步缩放", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = linked, onCheckedChange = { value ->
                        if (!value) individual = assets.associate { it.id to shared }
                        else shared = assets.firstOrNull()?.let { individual[it.id] } ?: PreviewTransform()
                        linked = value
                    })
                    TextButton(onClick = { shared = PreviewTransform(); individual = emptyMap() }) { Text("复位") }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val columns = if (assets.size > 2 || maxWidth > 640.dp) 2 else 1
                    val rows = assets.chunked(columns)
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEach { row ->
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { asset ->
                                    val transform = if (linked) shared else individual[asset.id] ?: PreviewTransform()
                                    ComparePanel(asset, asset.id in selectedIds, { onToggleSelection(asset.id) }, transform,
                                        Modifier.weight(1f)) { next ->
                                        if (linked) shared = next else individual = individual + (asset.id to next)
                                    }
                                }
                                if (row.size < columns) Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparePanel(
    asset: PhotoAsset, selected: Boolean, onToggle: () -> Unit, transform: PreviewTransform, modifier: Modifier,
    onTransform: (PreviewTransform) -> Unit
) {
    val latestTransform by rememberUpdatedState(transform)
    val latestUpdate by rememberUpdatedState(onTransform)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(asset.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(String.format(Locale.ROOT, "%.1f×", transform.scale), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggle, modifier = Modifier.semantics { contentDescription = if (selected) "取消选择" else "选择" }) {
                SelectionMark(selected)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val panelWidth = minOf(maxWidth, maxHeight * (4f / 3f))
            Box(Modifier.width(panelWidth).aspectRatio(4f / 3f).clipToBounds().background(Color.Black)
                .photoZoom(asset.id, { latestTransform }) { latestUpdate(it) }) {
                PhotoImage(
                    asset, modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.x * size.width
                        translationY = transform.y * size.height
                    }, maxEdge = 2048, contentScale = ContentScale.Fit, showSource = true
                )
            }
        }
    }
}
