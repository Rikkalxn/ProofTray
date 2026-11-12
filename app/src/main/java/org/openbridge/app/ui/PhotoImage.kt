package org.openbridge.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.openbridge.app.data.PhotoPreviewLoader
import org.openbridge.app.data.LoadedPhotoPreview
import org.openbridge.app.data.PreviewOrigin
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset

internal val LocalCameraPreviewRequest = staticCompositionLocalOf<(String, Boolean) -> (() -> Unit)> { { _, _ -> {} } }
internal val LocalCameraSessionGeneration = staticCompositionLocalOf { 0 }
internal val LocalPhotoGeometryUpdate = staticCompositionLocalOf<(PhotoAsset) -> Unit> { {} }
private val previewDispatcher = Dispatchers.IO.limitedParallelism(2)
private data class Preview(val image: LoadedPhotoPreview? = null, val loading: Boolean = true)

@Composable
fun PhotoImage(
    asset: PhotoAsset, modifier: Modifier = Modifier, maxEdge: Int = 768, contentScale: ContentScale = ContentScale.Crop,
    requestCamera: Boolean = true, showSource: Boolean = false
) {
    val context = LocalContext.current.applicationContext
    val edge = maxEdge.coerceIn(128, 3072)
    val request = LocalCameraPreviewRequest.current
    val generation = LocalCameraSessionGeneration.current
    val onGeometry by rememberUpdatedState(LocalPhotoGeometryUpdate.current)
    val preview by produceState(Preview(), asset.id, asset.uri, asset.previewUri, asset.previewRevision, asset.sizeBytes, edge, asset.format,
        asset.sourceOrientation, asset.width, asset.height, asset.downloadState) {
        value = Preview()
        value = withContext(previewDispatcher) {
            val image = try {
                PhotoPreviewLoader.load(context, asset, edge, onGeometry = { onGeometry(it) }, onPreview = { value = Preview(it) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { PhotoPreviewLoader.clearMemoryCache(); value.image }
            catch (_: Exception) { null }
            ensureActive()
            Preview(image, loading = false)
        }
    }
    val needsCamera = asset.downloadState != DownloadState.DOWNLOADED ||
        (asset.format == MediaFormat.RAW && !preview.loading && preview.image?.dimensions?.sufficient(edge > 768) != true)
    DisposableEffect(asset.id, needsCamera, edge > 768, generation, requestCamera) {
        val release = if (requestCamera && needsCamera) request(asset.id, edge > 768) else ({})
        onDispose { release() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val image = preview.image
        when {
            image != null -> {
                val bitmap = image.bitmap
                Image(bitmap.asImageBitmap(), asset.name, Modifier.fillMaxSize(), contentScale = contentScale)
                if (showSource && (asset.format == MediaFormat.RAW || asset.downloadState != DownloadState.DOWNLOADED)) {
                    val label = if (image.dimensions.longestEdge < 320) "小缩略图" else when (image.origin) {
                        PreviewOrigin.CAMERA -> "相机预览"
                        PreviewOrigin.RAW_EMBEDDED -> "RAW 内嵌预览"
                        PreviewOrigin.ORIGINAL -> "原片预览"
                    }
                    Text("$label · ${bitmap.width}×${bitmap.height}", modifier = Modifier.align(Alignment.BottomStart)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).padding(4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            preview.loading -> CircularProgressIndicator()
            else -> Text(if (asset.format == MediaFormat.RAW && asset.downloadState == DownloadState.DOWNLOADED) "暂未读到 RAW 预览"
                else if (asset.downloadState == DownloadState.DOWNLOADED) "暂无法读取预览" else "预览待载入\n连接相机后获取",
                modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}
