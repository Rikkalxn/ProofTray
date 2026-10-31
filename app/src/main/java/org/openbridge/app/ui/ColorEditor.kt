package org.openbridge.app.ui

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.openbridge.app.color.GlColorView
import org.openbridge.app.color.LutRepository
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.LutPreviewData
import org.openbridge.app.domain.PhotoAsset
import java.util.Locale

private data class ColorPreviewSource(val bitmap: Bitmap? = null, val error: String? = null)
private data class ColorPreviewLut(val data: LutPreviewData? = null, val error: String? = null, val loading: Boolean = false)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ColorEditor(asset: PhotoAsset, state: LibraryUiState, actions: LibraryActions, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val controlsHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    val repository = remember(context) { LutRepository.get(context) }
    var look by remember(asset.id) { mutableStateOf(state.look) }
    var original by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var gpuFailed by remember { mutableStateOf(false) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var glView by remember { mutableStateOf<GlColorView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val supportsGpu = remember { context.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion >= 0x30000 }
    val source by produceState(ColorPreviewSource(), asset.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val input = ImageDecoder.createSource(context.contentResolver, Uri.parse(asset.uri))
                val decoded = ImageDecoder.decodeBitmap(input) { decoder, info, _ ->
                    val edge = maxOf(info.size.width, info.size.height)
                    val scale = minOf(1.0, 1600.0 / edge)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                }
                val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else {
                    decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() } ?: error("无法建立预览")
                }
                if (Build.VERSION.SDK_INT >= 34) bitmap.setGainmap(null)
                ColorPreviewSource(bitmap)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ColorPreviewSource(error = error.message ?: "无法读取照片") }
        }
    }
    val lut by produceState(ColorPreviewLut(), look.lutId) {
        value = ColorPreviewLut(loading = look.lutId != null)
        val id = look.lutId
        value = if (id == null) ColorPreviewLut() else withContext(Dispatchers.IO) {
            try { ColorPreviewLut(repository.previewData(id)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ColorPreviewLut(error = error.message ?: "无法载入 LUT") }
        }
    }
    val displayedLook = if (original || lut.loading || lut.error != null) ColorLookSettings(strength = 0f) else look
    val compatible = gpuFailed || !supportsGpu || lut.data?.dimension == 1
    val cpu by produceState<Bitmap?>(null, source.bitmap, displayedLook, compatible) {
        val bitmap = source.bitmap
        if (bitmap != null && compatible) {
            delay(100)
            renderError = null
            value = withContext(Dispatchers.Default) {
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: error("无法生成预览")
                try {
                    repository.apply(copy, displayedLook)
                    ensureActive()
                    copy
                } catch (cancelled: CancellationException) { copy.recycle(); throw cancelled }
                catch (error: Exception) { copy.recycle(); renderError = error.message; null }
            }
        }
    }
    DisposableEffect(lifecycle, glView) {
        val view = glView
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) view?.onPause()
            if (event == Lifecycle.Event.ON_RESUME) view?.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view?.onPause() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("返回") }
                    Text("调色", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { saving = true }, enabled = lut.error == null && !lut.loading) { Text("保存预设") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1F1F1F)), contentAlignment = Alignment.Center) {
                    val bitmap = source.bitmap
                    when {
                        source.error != null -> Text(source.error.orEmpty(), color = Color.White, modifier = Modifier.padding(24.dp))
                        bitmap == null -> LoadingIndicator(Modifier.size(56.dp))
                        compatible -> cpu?.let { Image(it.asImageBitmap(), asset.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        else -> AndroidView(
                            factory = { GlColorView(it) { gpuFailed = true }.also { view -> glView = view } },
                            modifier = Modifier.fillMaxSize(),
                            update = { it.update(bitmap, lut.data, displayedLook) },
                            onReset = null,
                            onRelease = { it.onPause(); if (glView === it) glView = null }
                        )
                    }
                    if (lut.loading) LoadingIndicator(Modifier.size(48.dp))
                }
                Column(Modifier.heightIn(max = controlsHeight).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(look.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        FilterChip(selected = original, onClick = { original = !original }, label = { Text("对比原图") })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.colorPresets.filter { it.builtIn }.forEach { preset ->
                            FilterChip(selected = look.name == preset.name, onClick = { look = preset.look; renderError = null },
                                label = { Text(preset.name) })
                        }
                        FilterChip(selected = false, onClick = { library = true }, label = { Text("LUT / 更多预设") })
                    }
                    ColorSlider("强度", look.strength, 0f..1f, "${(look.strength * 100).toInt()}%") { look = look.copy(strength = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            ColorSlider("曝光", look.exposure, -3f..3f, String.format(Locale.ROOT, "%+.1f", look.exposure)) {
                                look = look.copy(exposure = it, name = "自定义")
                            }
                            ColorSlider("饱和度", look.saturation, 0f..2f, String.format(Locale.ROOT, "%.2f", look.saturation)) {
                                look = look.copy(saturation = it, name = "自定义")
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            ColorSlider("对比度", look.contrast, -1f..1f, String.format(Locale.ROOT, "%+.2f", look.contrast)) {
                                look = look.copy(contrast = it, name = "自定义")
                            }
                            ColorSlider("冷暖", look.warmth, -1f..1f, String.format(Locale.ROOT, "%+.2f", look.warmth)) {
                                look = look.copy(warmth = it, name = "自定义")
                            }
                        }
                    }
                    (lut.error ?: renderError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text("sRGB 缩小预览 · 参数用于所选照片的 JPEG / PNG 导出", style = MaterialTheme.typography.labelSmall)
                    PrimaryAction("用于导出", onClick = { actions.onSetLook(look); onDismiss() }, modifier = Modifier.fillMaxWidth(),
                        enabled = source.bitmap != null && source.error == null && lut.error == null && !lut.loading && renderError == null)
                }
            }
        }
    }
    if (library) ColorLibrarySheet(state, actions, onPick = { look = it; renderError = null }) { library = false }
    if (saving) AlertDialog(onDismissRequest = { saving = false }, title = { Text("保存调色预设") },
        text = { OutlinedTextField(presetName, { presetName = it }, label = { Text("预设名称") }, singleLine = true) },
        confirmButton = { TextButton(enabled = presetName.isNotBlank(), onClick = {
            actions.onSaveColorPreset(presetName, look); saving = false
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { saving = false }) { Text("取消") } })
}

@Composable
private fun ColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Text(display, style = MaterialTheme.typography.labelMedium)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range)
}
