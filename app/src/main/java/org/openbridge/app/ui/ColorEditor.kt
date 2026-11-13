package org.openbridge.app.ui

import android.app.ActivityManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.openbridge.app.color.GlColorView
import org.openbridge.app.data.LinearRawPreview
import org.openbridge.app.domain.LutRange
import kotlin.math.pow
import org.openbridge.app.color.LutRepository
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.DEFAULT_LUT_STRENGTH
import org.openbridge.app.domain.LutPreviewData
import org.openbridge.app.domain.PhotoAsset
import java.util.Locale
import kotlin.math.roundToInt

private data class ColorPreviewLut(val data: LutPreviewData? = null, val error: String? = null, val loading: Boolean = false)

private data class LookOption(val key: String, val name: String, val look: ColorLookSettings)

private enum class Adjustment(val label: String, val range: ClosedFloatingPointRange<Float>, val neutral: Float, val pattern: String) {
    EXPOSURE("曝光", -3f..3f, 0f, "%+.1f"), CONTRAST("对比度", -1f..1f, 0f, "%+.2f"),
    SATURATION("饱和度", 0f..2f, 1f, "%.2f"), WARMTH("冷暖", -1f..1f, 0f, "%+.2f");

    fun of(look: ColorLookSettings): Float = when (this) {
        EXPOSURE -> look.exposure
        CONTRAST -> look.contrast
        SATURATION -> look.saturation
        WARMTH -> look.warmth
    }

    fun set(look: ColorLookSettings, value: Float): ColorLookSettings = when (this) {
        EXPOSURE -> look.copy(exposure = value)
        CONTRAST -> look.copy(contrast = value)
        SATURATION -> look.copy(saturation = value)
        WARMTH -> look.copy(warmth = value)
    }.copy(name = "自定义")

    fun format(value: Float): String = String.format(Locale.ROOT, pattern, value)
}

private const val ORIGINAL_KEY = "original"
private const val THUMBNAIL_EDGE = 192f

/**
 * Grading panel inside PhotoEditScreen. The parent owns the draft, decode cache and export flow.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColorEditor(
    asset: PhotoAsset, state: LibraryUiState, actions: LibraryActions, look: ColorLookSettings, onLookChange: (ColorLookSettings) -> Unit,
    selectedLookKey: String?, onLookKeyChange: (String) -> Unit, onImport: () -> Unit,
    photos: PhotoEditSource, graded: ColorPreviewSource, allowLog: Boolean, onReady: (Boolean) -> Unit, onRetry: () -> Unit,
    header: PhotoEditHeaderContent, status: @Composable () -> Unit, pager: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val controlsHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    val repository = remember(context) { LutRepository.get(context) }
    // "原图" first, then imported LUTs (the main content), then the user's own presets.
    val options = remember(state.colorPresets, state.luts) {
        listOf(LookOption(ORIGINAL_KEY, "原图", ColorLookSettings())) +
            state.luts.filter { it.defaultUsage.unavailableReason == null }.map { lut ->
                lut.look().let { LookOption(checkNotNull(it.lutKey), lut.name, it) }
            } + state.colorPresets.filter { it.look.usage.unavailableReason == null }.map { LookOption("preset:${it.id}", it.name, it.look) }
    }
    val lookKey = selectedLookKey ?: options.firstOrNull { it.look == look }?.key ?: look.lutKey ?: ORIGINAL_KEY
    var tab by rememberSaveable { mutableStateOf(0) }
    var adjustment by rememberSaveable { mutableStateOf(Adjustment.EXPOSURE) }
    var original by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var rawReferenceInfo by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var gpuFailed by remember { mutableStateOf(false) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var glView by remember { mutableStateOf<GlColorView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val supportsGpu = remember { context.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion >= 0x30000 }
    val linear = photos.linear
    val normal = photos.normal
    val raw = photos.raw
    val glLeases = remember { mutableMapOf<GlColorView, EditBitmap.Lease>() }
    val logLook = if (original || holding) look.copy(strength = 0f) else look
    val source = if (raw) {
        if (linear.error != null) ColorPreviewSource(error = linear.error)
        else if (original || holding) {
            if (look.usesLog) linearLookPreview(linear.frame, logLook, repository) else normal.copy(renderedLook = logLook)
        } else graded
    } else normal
    val thumbBase = normal.image
    val thumbnail = rememberEditResult(ColorPreviewSource(), thumbBase) {
        val image = thumbBase ?: return@rememberEditResult ColorPreviewSource()
        withContext(editRenderDispatcher) {
            image.retain().use { lease ->
                val bitmap = lease.bitmap
                val scale = THUMBNAIL_EDGE / maxOf(bitmap.width, bitmap.height)
                ColorPreviewSource(if (scale >= 1f) hold(image) else own(Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)))
            }
        }
    }
    val lut by produceState(ColorPreviewLut(), look.lutId, look.usesLog, raw) {
        value = ColorPreviewLut(loading = look.lutId != null)
        val id = look.lutId
        value = if (id == null || look.usesLog || raw) ColorPreviewLut() else withContext(Dispatchers.IO) {
            try { ColorPreviewLut(repository.previewData(id)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ColorPreviewLut(error = error.message ?: "无法载入 LUT") }
        }
    }
    val displayedLook = if (original || holding || lut.loading || lut.error != null) ColorLookSettings(strength = 0f) else look
    val compatible = !raw && !look.usesLog && (gpuFailed || !supportsGpu || lut.data?.dimension == 1)
    val cpu = rememberEditResult(ColorPreviewSource(), source.image, displayedLook, compatible) {
        val image = source.image ?: return@rememberEditResult ColorPreviewSource()
        if (!compatible) return@rememberEditResult ColorPreviewSource()
        delay(100)
        try {
            if (displayedLook.isIdentity) ColorPreviewSource(hold(image), renderedLook = displayedLook)
            else withContext(editRenderDispatcher) {
                image.retain().use { lease ->
                    val copy = own(checkNotNull(lease.bitmap.copy(Bitmap.Config.ARGB_8888, true)) { "无法生成预览" })
                    repository.apply(copy.bitmap, displayedLook)
                    ColorPreviewSource(copy, renderedLook = displayedLook)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { close(); ColorPreviewSource(error = "可用内存不足") }
        catch (error: Exception) { close(); ColorPreviewSource(error = error.message ?: "无法生成预览") }
    }
    val bitmap = rememberEditBitmap(source.image)
    val cpuBitmap = rememberEditBitmap(cpu.image)
    DisposableEffect(lifecycle, glView) {
        val view = glView
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) view?.onPause()
            if (event == Lifecycle.Event.ON_RESUME) view?.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view?.onPause() }
    }
    val ready = if (raw) (!look.usesLog || allowLog) && source.renderedLook == logLook && source.bitmap != null && source.error == null
        else source.bitmap != null && source.error == null && lut.error == null && !lut.loading && renderError == null && cpu.error == null &&
            (!compatible || (cpu.image != null && cpu.renderedLook == displayedLook))
    val blendable = look.copy(name = "", strength = 1f) != ColorLookSettings(name = "")
    val savable = blendable && state.colorPresets.none { it.look.copy(name = look.name) == look }
    SideEffect { onReady(ready) }
    Column(Modifier.fillMaxSize()) {
        header { _ -> }
        status()
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1F1F1F))
            .pointerInput(Unit) { detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false }) },
            contentAlignment = Alignment.Center) {
            when {
                source.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(source.error.orEmpty(), color = Color.White, modifier = Modifier.padding(24.dp))
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                bitmap == null -> LoadingIndicator(Modifier.size(56.dp))
                raw -> Image(bitmap.asImageBitmap(), asset.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                compatible -> cpuBitmap?.let { Image(it.asImageBitmap(), asset.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                else -> AndroidView(
                    factory = { GlColorView(it) { gpuFailed = true }.also { view ->
                        glView = view; glLeases[view] = checkNotNull(source.image).retain()
                    } },
                    modifier = Modifier.fillMaxSize(),
                    update = { it.update(bitmap, lut.data, displayedLook) },
                    onReset = null,
                    onRelease = { it.releasePreview(); glLeases.remove(it)?.close(); if (glView === it) glView = null }
                )
            }
            if (lut.loading) LoadingIndicator(Modifier.size(48.dp))
            FilterChip(selected = original, onClick = { original = !original }, label = { Text("原图") },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            pager()
        }
        Column(Modifier.heightIn(max = controlsHeight).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (raw && linear.frame != null) {
                Text(if (look.usesLog) "RAW · 线性输入" else "RAW 显影 · 标准",
                    Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall)
            }
            state.errorMessage?.let { message ->
                ErrorNotice(message, Modifier.padding(horizontal = 20.dp)) {
                    IconButton(onClick = actions.onDismissError) { BridgeIcon(BridgeGlyph.CLOSE, "关闭提示") }
                }
            }
            (lut.error ?: renderError ?: cpu.error)?.let { message ->
                ErrorNotice(message, Modifier.padding(horizontal = 20.dp)) { TextButton(onClick = onRetry) { Text("重试") } }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                listOf("风格", "调整").forEachIndexed { index, label ->
                    SegmentedButton(selected = tab == index, onClick = { tab = index },
                        shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                }
            }
            if (!allowLog && options.any { it.look.usesLog }) Text("所选照片含非 RAW，Log LUT 不可用",
                Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
            if (tab == 0) {
                @Composable
                fun OptionTile(option: LookOption) = LookTile(option.name, lookKey == option.key, onClick = {
                    onLookChange(option.look); onLookKeyChange(option.key); renderError = null
                        }, enabled = allowLog || !option.look.usesLog) { LookThumbnail(thumbnail.image, linear.thumb, option.look, repository) }
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = ORIGINAL_KEY) { OptionTile(options.first()) }
                    item(key = "import") {
                        LookTile(if (state.colorBusy) "正在导入" else "导入 LUT", false,
                            onClick = { onImport() }, enabled = !state.colorBusy) {
                            if (state.colorBusy) LoadingIndicator(Modifier.size(32.dp)) else BridgeIcon(BridgeGlyph.ADD)
                        }
                    }
                    items(options.drop(1), key = { it.key }) { OptionTile(it) }
                    item(key = "manage") { LookTile("管理", false, onClick = { library = true }) { BridgeIcon(BridgeGlyph.MORE) } }
                }
                if (state.luts.isEmpty()) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(".cube 或 HaldCLUT PNG", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { library = true }) { Text("获取 LUT") }
                }
                val lutId = look.lutId
                // The curve the table expects; the user compares on the preview and the choice becomes this LUT's default.
                if (lutId != null) Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("LUT 输入", style = MaterialTheme.typography.labelLarge)
                        LutKindMenu(look.usage, allowLog = allowLog) { usage ->
                            val fromLutTile = lookKey == look.lutKey
                            val next = look.withUsage(usage)
                            onLookChange(next)
                            renderError = null
                            if (fromLutTile) {
                                onLookKeyChange(next.lutKey ?: ORIGINAL_KEY)
                                actions.onSetLutDefault(lutId, usage)
                            }
                        }
                    }
                    state.luts.firstOrNull { it.id == lutId }?.domainWarning(look.usage)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (look.usesLog) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("LUT 输出", style = MaterialTheme.typography.labelLarge)
                            LutOutputMenu(look.usage) { usage ->
                                val fromTable = lookKey == look.lutKey
                                val next = look.withUsage(usage)
                                onLookChange(next)
                                renderError = null
                                if (fromTable) {
                                    onLookKeyChange(next.lutKey ?: ORIGINAL_KEY)
                                    actions.onSetLutDefault(lutId, usage)
                                }
                            }
                        }
                        val label = String.format(Locale.ROOT, "%+.1f EV · %.2f×", look.logGainEv, 2.0.pow(look.logGainEv.toDouble()))
                        EditorSlider("额外增益 K", look.logGainEv, -3f..3f, label,
                            onReset = if (look.logGainEv != 0f) ({ onLookChange(look.copy(logGainEv = 0f)) }) else null,
                            onInfo = { rawReferenceInfo = true }) {
                            onLookChange(look.copy(logGainEv = it))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LutRange.entries.forEach { range ->
                                FilterChip(selected = look.usage.inputRange == range, onClick = {
                                    val fromTable = lookKey == look.lutKey
                                    val next = look.copy(usage = look.usage.copy(inputRange = range))
                                    onLookChange(next)
                                    if (fromTable) {
                                        onLookKeyChange(next.lutKey ?: ORIGINAL_KEY)
                                        actions.onSetLutDefault(lutId, next.usage)
                                    }
                                }, label = { Text(if (range == LutRange.FULL) "全范围" else "视频范围") })
                            }
                        }
                    }
                }
                val defaultStrength = if (lutId != null) DEFAULT_LUT_STRENGTH else 1f
                if (blendable) EditorSlider("强度", look.strength, 0f..1f, "${(look.strength * 100).roundToInt()}%",
                    onReset = if (look.strength != defaultStrength) ({ onLookChange(look.copy(strength = defaultStrength)) }) else null) {
                    onLookChange(look.copy(strength = it))
                }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Adjustment.entries.forEach { option ->
                        val value = option.of(look)
                        FilterChip(selected = adjustment == option, onClick = { adjustment = option },
                            label = { Text(if (value == option.neutral) option.label else "${option.label} ${option.format(value)}") })
                    }
                }
                val value = adjustment.of(look)
                EditorSlider(adjustment.label, value, adjustment.range, adjustment.format(value),
                    onReset = if (value != adjustment.neutral) ({ onLookChange(adjustment.set(look, adjustment.neutral)) }) else null) {
                    onLookChange(adjustment.set(look, it))
                }
            }
            if (savable) TextButton(onClick = { saving = true }, enabled = ready, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("存为预设")
            }
        }
    }
    if (library) ColorLibrarySheet(state, actions, allowLog = allowLog, onPick = { picked ->
        onLookChange(picked)
        onLookKeyChange(options.firstOrNull { it.look == picked }?.key ?: picked.lutKey ?: ORIGINAL_KEY)
        renderError = null
    }) { library = false }
    if (rawReferenceInfo) AlertDialog(onDismissRequest = { rawReferenceInfo = false }, title = { Text("RAW 曝光参考") },
        text = { Text("K=1 表示不额外增益，原图和 0% 不受 K 影响。通用 RAW 参考尚未针对当前机型标定。" +
            "请先按 LUT 作者的说明确认输入和输出编码，再调整 K。") },
        confirmButton = { TextButton(onClick = { rawReferenceInfo = false }) { Text("关闭") } })
    if (saving) AlertDialog(onDismissRequest = { saving = false }, title = { Text("存为预设") },
        text = { OutlinedTextField(presetName, { presetName = it }, label = { Text("预设名称") }, singleLine = true) },
        confirmButton = { TextButton(enabled = presetName.isNotBlank(), onClick = {
            actions.onSaveColorPreset(presetName, look); saving = false
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { saving = false }) { Text("取消") } })
}

@Composable
private fun LookTile(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.small
    var fullName by remember(label) { mutableStateOf(false) }
    Column(Modifier.width(72.dp).combinedClickable(onClick = { if (enabled) onClick() }, onLongClick = { fullName = true })
        .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(72.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
            contentAlignment = Alignment.Center) { content() }
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The look rendered on a small copy of the photo being edited, so styles are chosen by result instead of by name. */
@Composable
private fun LookThumbnail(base: EditBitmap?, linear: LinearRawPreview?, look: ColorLookSettings, repository: LutRepository) {
    if (linear != null) {
        val source = linearLookPreview(linear, look, repository)
        rememberEditBitmap(source.image)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        return
    }
    if (look.usesLog) { BridgeIcon(BridgeGlyph.PHOTOS); return }
    val rendered = rememberEditResult(ColorPreviewSource(), base, look) {
        val image = base ?: return@rememberEditResult ColorPreviewSource()
        try {
            if (look.isIdentity) ColorPreviewSource(hold(image))
            else withContext(editRenderDispatcher) {
                image.retain().use { lease ->
                    val copy = own(checkNotNull(lease.bitmap.copy(Bitmap.Config.ARGB_8888, true)))
                    repository.apply(copy.bitmap, look)
                    ColorPreviewSource(copy)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { close(); ColorPreviewSource() }
    }
    rememberEditBitmap(rendered.image)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

@Composable
private fun EditorSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String,
    onReset: (() -> Unit)?, onInfo: (() -> Unit)? = null, onChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            if (onInfo != null) IconButton(onClick = onInfo, modifier = Modifier.size(28.dp)) { BridgeIcon(BridgeGlyph.INFO, "曝光参考说明") }
            Text(display, style = MaterialTheme.typography.labelLarge)
            if (onReset != null) TextButton(onClick = onReset) { Text("复位") }
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
