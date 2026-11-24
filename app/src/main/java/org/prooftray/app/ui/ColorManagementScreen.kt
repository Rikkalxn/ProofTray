package org.prooftray.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.prooftray.app.frame.FramePreset
import org.prooftray.app.frame.FrameRepository
import org.prooftray.app.frame.withFrameInitialization

/** Management never applies a LUT or template to the current editing session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorManagementScreen(state: LibraryUiState, actions: LibraryActions, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<ColorLibraryEntry?>(null) }
    var frameDetail by remember { mutableStateOf<FramePreset?>(null) }
    val context = LocalContext.current
    val repository = remember(context) { FrameRepository(context) }
    var retry by remember { mutableIntStateOf(0) }
    val frames by produceState<Result<List<FramePreset>>?>(null, tab, retry, state.deletedFrameTemplateIds) {
        if (tab == 2) value = withContext(Dispatchers.IO) { runCatching { withFrameInitialization { repository.presets() } } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { AppIcon(AppGlyph.CLOSE, "关闭管理") }
                    Text("调色与边框", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (tab != 2) TextButton(onClick = actions.onImportColor, enabled = !state.colorBusy) { Text("导入") }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    listOf("LUT", "我的预设", "边框模板").forEachIndexed { index, title ->
                        SegmentedButton(selected = tab == index, onClick = { tab = index },
                            shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(title) }
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab != 2) ColorLibraryContent(state, actions, onPick = null, showLuts = tab == 0, showPresets = tab == 1,
                        onDetails = { detail = it })
                    else {
                        state.errorMessage?.let { ErrorNotice(it) { TextButton(onClick = actions.onDismissError) { Text("关闭") } } }
                        val result = frames
                        when {
                            result == null -> Text("正在读取模板")
                            result.isFailure -> {
                                Text(result.exceptionOrNull()?.message ?: "无法读取模板", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { retry++ }) { Text("重试") }
                            }
                            else -> result.getOrThrow().forEach { preset ->
                                ListItem(headlineContent = { Text(preset.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                                    supportingContent = { Text(preset.error ?: if (preset.id.startsWith("user.")) "我的模板" else "内置模板",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = { AppIcon(AppGlyph.FORWARD) },
                                    modifier = Modifier.clickable { frameDetail = preset })
                            }
                        }
                    }
                }
            }
        }
        detail?.let { entry ->
            AppSheet("详情", onDismiss = { detail = null }, actions = {
                TextButton(onClick = { detail = null }) { Text("关闭") }
            }) { ColorLibraryDetails(entry, state, actions, allowLog = true, onGone = { detail = null }) }
        }
        frameDetail?.let { preset ->
            var deleting by remember(preset.id) { mutableStateOf(false) }
            AppSheet("边框模板", onDismiss = { frameDetail = null }, actions = {
                TextButton(onClick = { frameDetail = null }) { Text("关闭") }
            }) {
                SelectionContainer { Text(preset.name, style = MaterialTheme.typography.titleMedium) }
                preset.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (preset.id.startsWith("user.")) TextButton(onClick = { deleting = true }, enabled = !state.isLoading) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                } else Text("内置模板", style = MaterialTheme.typography.bodyMedium)
            }
            if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除“${preset.name}”？") },
                text = { Text("当前使用的该边框将清空。已排队的导出不受影响。") },
                confirmButton = { TextButton(onClick = {
                    actions.onDeleteFrameTemplate(preset.id); deleting = false; frameDetail = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
        }
    }
}
