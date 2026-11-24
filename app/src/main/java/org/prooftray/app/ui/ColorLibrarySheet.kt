package org.prooftray.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.LutAsset
import org.prooftray.app.domain.validateLutName

internal sealed interface ColorLibraryEntry {
    data class Lut(val id: String) : ColorLibraryEntry
    data class Preset(val id: String) : ColorLibraryEntry
}

@Composable
fun ColorLibrarySheet(
    state: LibraryUiState, actions: LibraryActions, onPick: ((ColorLookSettings) -> Unit)?, allowLog: Boolean = true, onDismiss: () -> Unit
) {
    var detail by remember { mutableStateOf<ColorLibraryEntry?>(null) }
    val listState = rememberSaveableStateHolder()
    val listScroll = rememberScrollState()
    val back = { if (detail != null) detail = null else onDismiss() }
    key(detail) {
    AppSheet(title = if (detail == null) "LUT" else "详情", onDismiss = back, actions = {
        TextButton(onClick = back) { Text(if (detail == null) "关闭" else "返回") }
        Spacer(Modifier.weight(1f))
        if (detail == null) PrimaryAction(if (state.colorBusy) "正在导入" else "导入 LUT", actions.onImportColor,
            enabled = !state.colorBusy, glyph = AppGlyph.ADD)
    }, scrollState = if (detail == null) listScroll else null) {
        val entry = detail
        if (entry == null) listState.SaveableStateProvider("list") {
            ColorLibraryContent(state, actions, onPick?.let { pick -> { look -> pick(look); onDismiss() } },
                allowLog, onDetails = { detail = it })
        }
        else ColorLibraryDetails(entry, state, actions, allowLog = if (onPick == null) true else allowLog, onGone = { detail = null })
    }
    }
}

@Composable
internal fun ColumnScope.ColorLibraryContent(
    state: LibraryUiState, actions: LibraryActions, onPick: ((ColorLookSettings) -> Unit)?, allowLog: Boolean = true,
    showLuts: Boolean = true, showPresets: Boolean = true, onDetails: (ColorLibraryEntry) -> Unit
) {
    var search by rememberSaveable { mutableStateOf("") }
    var showRemoved by rememberSaveable(showLuts) { mutableStateOf(false) }
    var sourcesExpanded by rememberSaveable { mutableStateOf(false) }
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    val luts = (if (showRemoved) state.removedLuts else state.luts).filter { it.name.contains(search, true) }
    val presets = state.colorPresets.filter { it.name.contains(search, true) }
    state.errorMessage?.let { message -> ErrorNotice(message) { TextButton(onClick = actions.onDismissError) { Text("关闭") } } }
    if (state.luts.size + state.removedLuts.size + state.colorPresets.size > 8) {
        OutlinedTextField(search, { search = it }, label = { Text("搜索") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
    if (showLuts) {
        if (luts.isEmpty()) Text(if (showRemoved) "暂无已隐藏的 LUT" else ".cube 或 HaldCLUT PNG，ZIP 需先解压",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        luts.forEach { lut ->
            val unavailable = lut.defaultUsage.unavailableReason
            val requiresRaw = onPick != null && !allowLog && lut.defaultUsage.input?.isLog == true
            val canPick = onPick == null || (!showRemoved && unavailable == null && !requiresRaw)
            ListItem(headlineContent = { Text(lut.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                supportingContent = { Text(unavailable ?: lut.domainWarning() ?: if (requiresRaw) "Log 输入需要 RAW 原片" else lutSpecification(lut),
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    IconButton(onClick = { onDetails(ColorLibraryEntry.Lut(lut.id)) }) { AppIcon(AppGlyph.MORE, "LUT 详情") }
                }, colors = colors, modifier = Modifier.clickable(enabled = canPick) {
                    if (onPick == null) onDetails(ColorLibraryEntry.Lut(lut.id)) else onPick(lut.look())
                })
        }
    }
    if (showPresets && !showRemoved) {
        if (showLuts && presets.isNotEmpty()) Text("我的预设", style = MaterialTheme.typography.titleSmall)
        if (!showLuts && presets.isEmpty()) Text("暂无调色预设")
        presets.forEach { preset ->
            val unavailable = preset.look.usage.unavailableReason
            val requiresRaw = onPick != null && !allowLog && preset.look.usesLog
            ListItem(headlineContent = { Text(preset.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                supportingContent = { Text(unavailable ?: if (requiresRaw) "Log 输入需要 RAW 原片" else preset.look.usage.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    IconButton(onClick = { onDetails(ColorLibraryEntry.Preset(preset.id)) }) { AppIcon(AppGlyph.MORE, "预设详情") }
                }, colors = colors, modifier = Modifier.clickable(enabled = onPick == null || (unavailable == null && !requiresRaw)) {
                    if (onPick == null) onDetails(ColorLibraryEntry.Preset(preset.id)) else onPick(preset.look)
                })
        }
    }
    if (showLuts && !showRemoved) {
        TextButton(onClick = { sourcesExpanded = !sourcesExpanded }) {
            Text("获取 LUT"); AppIcon(if (sourcesExpanded) AppGlyph.BACK else AppGlyph.FORWARD)
        }
        if (sourcesExpanded) colorSources.forEach { (name, url) ->
            ListItem(headlineContent = { Text(name) }, colors = colors, trailingContent = { AppIcon(AppGlyph.FORWARD) },
                modifier = Modifier.clickable { actions.onOpenColorSource(url) })
        }
    }
    if (showLuts && (showRemoved || state.removedLuts.isNotEmpty())) TextButton(onClick = { showRemoved = !showRemoved }) {
        Text(if (showRemoved) "返回 LUT 列表" else "已隐藏（${state.removedLuts.size}）")
    }
}

private fun lutSpecification(lut: LutAsset): String {
    val size = if (lut.dimension == 3) "${lut.size}³" else "1D / ${lut.size}"
    return "${lut.defaultUsage.input?.label ?: lut.defaultUsage.inputName} · $size · ${lut.sourceFormat}"
}

@Composable
internal fun ColorLibraryDetails(
    entry: ColorLibraryEntry, state: LibraryUiState, actions: LibraryActions, allowLog: Boolean, onGone: () -> Unit
) {
    val lut = (entry as? ColorLibraryEntry.Lut)?.let { item -> (state.luts + state.removedLuts).find { it.id == item.id } }
    val preset = (entry as? ColorLibraryEntry.Preset)?.let { item -> state.colorPresets.find { it.id == item.id } }
    if (lut == null && preset == null) { LaunchedEffect(entry) { onGone() }; return }
    var renaming by remember(entry) { mutableStateOf(false) }
    var hiding by remember(entry) { mutableStateOf(false) }
    var deleting by remember(entry) { mutableStateOf(false) }
    val busy = state.colorBusy || state.isLoading
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SelectionContainer { Text(lut?.name ?: checkNotNull(preset).name, style = MaterialTheme.typography.titleMedium) }
        if (lut != null) {
            Text(lutSpecification(lut), style = MaterialTheme.typography.bodyMedium)
            (lut.defaultUsage.unavailableReason ?: lut.domainWarning())?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row {
                Text("输入曲线", modifier = Modifier.weight(1f))
                LutKindMenu(lut.defaultUsage, allowLog) { actions.onSetLutDefault(lut.id, it) }
            }
            if (lut.defaultUsage.input?.isLog == true) Row {
                Text("LUT 输出", modifier = Modifier.weight(1f))
                LutOutputMenu(lut.defaultUsage) { actions.onSetLutDefault(lut.id, it) }
            }
            val references = state.colorPresets.filter { it.look.lutId == lut.id }
            Text(if (references.isEmpty()) "没有预设引用" else "引用预设：${references.joinToString("、") { it.name }}",
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = { renaming = true }) { Text("重命名") }
            TextButton(enabled = !busy, onClick = {
                if (lut.removed) actions.onSetLutRemoved(lut.id, false) else hiding = true
            }) { Text(if (lut.removed) "恢复" else "隐藏") }
            TextButton(enabled = !busy, onClick = { deleting = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            if (renaming) LutRenameDialog(lut, busy, onSave = { actions.onRenameLut(lut.id, it); renaming = false }) { renaming = false }
            if (hiding) AlertDialog(onDismissRequest = { hiding = false }, title = { Text("隐藏“${lut.name}”？") },
                text = { Text("不删文件，可在『已隐藏』里恢复。已有预设仍可使用。") },
                confirmButton = { TextButton(enabled = !busy, onClick = {
                    actions.onSetLutRemoved(lut.id, true); hiding = false
                }) { Text("隐藏") } }, dismissButton = { TextButton(onClick = { hiding = false }) { Text("取消") } })
            if (deleting) LutDeleteDialog(lut, references, state.tasks.any { it.requiresLut(lut.id) }, busy, onConfirm = {
                actions.onDeleteLut(lut.id, it); deleting = false
            }, onDismiss = { deleting = false })
        } else if (preset != null) {
            Text(preset.look.usage.label)
            TextButton(enabled = preset.look.usage.unavailableReason == null, onClick = { actions.onExportColorPreset(preset) }) { Text("分享") }
            TextButton(enabled = !busy, onClick = { deleting = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            if (deleting) ColorPresetDeleteDialog(preset, busy, onConfirm = {
                actions.onDeleteColorPreset(preset.id); deleting = false
            }, onDismiss = { deleting = false })
        }
    }
}

@Composable
private fun LutRenameDialog(lut: LutAsset, busy: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(lut.id) { mutableStateOf(lut.name) }
    val checked = remember(name) { runCatching { validateLutName(name) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("重命名 LUT") }, text = {
        OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, isError = checked.isFailure,
            supportingText = { checked.exceptionOrNull()?.message?.let { Text(it) } })
    }, confirmButton = { TextButton(enabled = !busy && checked.isSuccess, onClick = { onSave(checked.getOrThrow()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private val colorSources = listOf(
    "G’MIC 色彩预设" to "https://gmic.eu/color_presets/",
    "PictureFX 胶片 LUT" to "https://picturefx.itch.io/",
    "Fresh LUTs 最新投稿" to "https://freshluts.com/latest",
    "RawTherapee 胶片模拟" to "https://rawpedia.pixls.us/film_simulation/",
    "HaldCLUT 资源集合" to "https://github.com/cedeber/hald-clut",
    "Nikon Imaging Recipes（机身配方参考）" to "https://imagingcloud.nikon.com/recipe/release/"
)
