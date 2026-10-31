package org.openbridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import org.openbridge.app.domain.PhotoAsset

private data class PreviewTransform(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f)

@Composable
fun CompareDialog(
    assets: List<PhotoAsset>, selectedIds: Set<String> = emptySet(), onToggleSelection: ((String) -> Unit)? = null, onDismiss: () -> Unit
) {
    val single = assets.size == 1
    var linked by remember { mutableStateOf(true) }
    var shared by remember { mutableStateOf(PreviewTransform()) }
    var individual by remember { mutableStateOf(emptyMap<String, PreviewTransform>()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (single) "查看照片" else "比较 ${assets.size} 张", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (single && onToggleSelection != null) TextButton(onClick = { onToggleSelection(assets.single().id) }) {
                        Text(if (assets.single().id in selectedIds) "已选择" else "选择")
                    }
                    TextButton(onClick = { shared = PreviewTransform(); individual = emptyMap() }) { Text("重置") }
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
                if (!single) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = linked, onCheckedChange = { value ->
                        if (!value) individual = assets.associate { it.id to shared }
                        else shared = assets.firstOrNull()?.let { individual[it.id] } ?: PreviewTransform()
                        linked = value
                    })
                    Text("同步缩放与移动")
                }
                Text(
                    "双指缩放与拖动。相机预览清晰度取决于机身；预览不能替代原片的 100% 细节检查。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val columns = if (!single && (assets.size > 2 || maxWidth > 640.dp)) 2 else 1
                    val rows = assets.chunked(columns)
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEach { row ->
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { asset ->
                                    val transform = if (linked) shared else individual[asset.id] ?: PreviewTransform()
                                    ComparePanel(asset, transform, Modifier.weight(1f), if (single) 3072 else 2048) { next ->
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
    asset: PhotoAsset, transform: PreviewTransform, modifier: Modifier, maxEdge: Int, onTransform: (PreviewTransform) -> Unit
) {
    val latestTransform by rememberUpdatedState(transform)
    val latestUpdate by rememberUpdatedState(onTransform)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(asset.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val panelWidth = minOf(maxWidth, maxHeight * (4f / 3f))
            Box(
                Modifier.width(panelWidth).aspectRatio(4f / 3f).clipToBounds().background(Color.Black)
                    .pointerInput(asset.id) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (size.width <= 0 || size.height <= 0) return@detectTransformGestures
                            val old = latestTransform
                            val scale = (old.scale * zoom).coerceIn(1f, 8f)
                            val ratio = scale / old.scale
                            val focusX = centroid.x / size.width - 0.5f
                            val focusY = centroid.y / size.height - 0.5f
                            val limit = (scale - 1f) / 2f
                            val x = (focusX + (old.x - focusX) * ratio + pan.x / size.width).coerceIn(-limit, limit)
                            val y = (focusY + (old.y - focusY) * ratio + pan.y / size.height).coerceIn(-limit, limit)
                            latestUpdate(PreviewTransform(scale, x, y))
                        }
                    }
            ) {
                PhotoImage(
                    asset, modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.x * size.width
                        translationY = transform.y * size.height
                    }, maxEdge = maxEdge, contentScale = ContentScale.Fit
                )
            }
        }
        Text(
            "预览 ${String.format(Locale.ROOT, "%.1f", transform.scale)}× · ${asset.format.name}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
