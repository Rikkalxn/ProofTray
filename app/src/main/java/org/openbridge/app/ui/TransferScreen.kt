package org.openbridge.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openbridge.app.camera.CameraDownload
import org.openbridge.app.camera.DownloadChannel
import org.openbridge.app.camera.DownloadStatus
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.DownloadVariant
import org.openbridge.app.domain.ExportStatus
import org.openbridge.app.domain.ExportTask
import org.openbridge.app.domain.ExportDestination
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import java.util.Locale

private enum class TaskView(val label: String) { ALL("全部"), ACTIVE("进行中"), FAILED("需重试"), COMPLETED("已完成") }

private fun DownloadStatus.taskView(): TaskView = when (this) {
    DownloadStatus.QUEUED, DownloadStatus.RUNNING -> TaskView.ACTIVE
    DownloadStatus.WAITING -> TaskView.FAILED
    DownloadStatus.COMPLETED -> TaskView.COMPLETED
    DownloadStatus.CANCELLED -> TaskView.ALL
}

private fun ExportStatus.taskView(): TaskView = when (this) {
    ExportStatus.QUEUED, ExportStatus.RUNNING -> TaskView.ACTIVE
    ExportStatus.FAILED -> TaskView.FAILED
    ExportStatus.COMPLETED -> TaskView.COMPLETED
    ExportStatus.CANCELLED -> TaskView.ALL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransferScreen(state: TransferScreenState, actions: TransferScreenActions, showExports: Boolean, onShowExports: (Boolean) -> Unit) {
    val source = if (showExports) 1 else 0
    var view by rememberSaveable(source) { mutableStateOf(TaskView.ALL) }
    var filterMenu by remember(source) { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val downloads = source == 0
    val controls = if (downloads) actions.downloads else actions.exports
    val paused = if (downloads) state.downloadsPaused else state.exportsPaused
    val count = if (downloads) state.downloads.size else state.exports.size
    val clearable = if (downloads) state.downloads.count { it.canClearHistory } else state.exports.count { it.canClearHistory }
    val active = if (downloads) state.downloads.any { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) }
        else state.exports.any { it.status in setOf(ExportStatus.QUEUED, ExportStatus.RUNNING) }
    val assetsById = remember(state.assets) { state.assets.associateBy { it.id } }
    val assetsByUri = remember(state.assets) { state.assets.associateBy { it.uri } }
    val downloadRows = remember(state.downloads, view) {
        state.downloads.asReversed().let { if (view == TaskView.ALL) it else it.filter { task -> task.status.taskView() == view } }
    }
    val exportRows = remember(state.exports, view) {
        state.exports.asReversed().let { if (view == TaskView.ALL) it else it.filter { task -> task.status.taskView() == view } }
    }
    val visibleCount = if (downloads) downloadRows.size else exportRows.size
    val activeCounts = listOf(state.downloads.count { it.status.taskView() == TaskView.ACTIVE },
        state.exports.count { it.status.taskView() == TaskView.ACTIVE })
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            listOf("相机导入", "照片导出").forEachIndexed { index, label ->
                SegmentedButton(selected = source == index, onClick = { onShowExports(index == 1) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                    Text(if (activeCounts[index] > 0) "$label · ${activeCounts[index]}" else label, maxLines = 1)
                }
            }
        }
        if (count > 0 || paused) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { filterMenu = true }) {
                    BridgeIcon(BridgeGlyph.FILTER)
                    Text("${view.label} · $visibleCount")
                }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    TaskView.entries.forEach { option ->
                        val matching = when {
                            option == TaskView.ALL -> count
                            downloads -> state.downloads.count { it.status.taskView() == option }
                            else -> state.exports.count { it.status.taskView() == option }
                        }
                        DropdownMenuItem(text = { Text("${option.label} · $matching") }, onClick = { view = option; filterMenu = false })
                    }
                }
            }
            if (active || paused) TextButton(onClick = {
                controls.onPause(!paused)
            }) { Text((if (paused) "继续" else "暂停") + if (downloads) "导入" else "导出") }
            if (clearable > 0) TextButton(onClick = { clearing = true }) { Text("清理记录") }
        }
        if (view == TaskView.FAILED && visibleCount > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                val ids = if (downloads) downloadRows.map { it.id } else exportRows.map { it.id }
                ids.forEach(controls.onRetry)
            }) { Text("全部重试") }
        }
        if (visibleCount == 0) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BridgeIcon(if (downloads) BridgeGlyph.DOWNLOAD else BridgeGlyph.SHARE, modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (count == 0) "暂无任务记录" else "此筛选下没有任务", style = MaterialTheme.typography.titleMedium)
                if (count == 0) Text(if (downloads) "在“导入”页选择相机照片后导入" else "在“图库”选择照片后导出",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else TextButton(onClick = { view = TaskView.ALL }) { Text("显示全部任务") }
            }
        } else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (downloads) items(downloadRows, key = { it.id }) { task ->
                val sourceAsset = assetsById[task.photo.asset.id] ?: task.photo.asset
                val thumbnail = if (task.status == DownloadStatus.COMPLETED && task.outputUri != null) {
                    assetsByUri[task.outputUri] ?: sourceAsset.copy(uri = task.outputUri, downloadState = DownloadState.DOWNLOADED,
                        format = if (task.variant == DownloadVariant.CAMERA_JPEG) MediaFormat.JPEG else sourceAsset.format)
                } else sourceAsset
                DownloadRow(task, thumbnail, actions.downloads, actions.onOpenOutput)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            } else items(exportRows, key = { it.id }) { task ->
                ExportRow(task, assetsById[task.asset.id] ?: task.asset, actions.exports, actions.onOpenOutput)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("清理 $clearable 条任务记录？") },
        text = { Text("清理已结束记录，保留照片、导入历史和未完成任务。失败记录清理后无法在此重试。") },
        confirmButton = { TextButton(onClick = {
            controls.onClearHistory()
            clearing = false
        }) { Text("清理记录") } }, dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } })
}

@Composable
private fun TaskRow(
    asset: PhotoAsset, name: String, status: String, detail: String, failed: Boolean, progress: Float?, onOpen: (() -> Unit)?,
    action: @Composable () -> Unit
) {
    var showingError by remember(name, detail) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(enabled = onOpen != null || failed) {
        if (failed) showingError = true else onOpen?.invoke()
    }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PhotoImage(asset, Modifier.size(48.dp).clip(MaterialTheme.shapes.small), maxEdge = 160, requestCamera = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(status, style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (failed) 3 else 2, overflow = TextOverflow.Ellipsis)
            if (failed) TextButton(onClick = { showingError = true }, contentPadding = PaddingValues(0.dp)) { Text("查看原因") }
            if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        action()
    }
    if (showingError) AlertDialog(onDismissRequest = { showingError = false }, title = { Text("任务未完成") },
        text = { SelectionContainer { Text(detail) } }, confirmButton = { TextButton(onClick = { showingError = false }) { Text("关闭") } })
}

@Composable
private fun DownloadRow(task: CameraDownload, thumbnail: PhotoAsset, actions: TaskQueueActions, onOpenOutput: (String) -> Unit) {
    val running = task.status == DownloadStatus.RUNNING
    val failed = task.status == DownloadStatus.WAITING
    val status = when (task.status) {
        DownloadStatus.QUEUED -> "排队"
        DownloadStatus.RUNNING -> "导入中"
        DownloadStatus.WAITING -> "中断"
        DownloadStatus.COMPLETED -> "成功"
        DownloadStatus.CANCELLED -> "已取消"
    }
    val channel = if (task.channel == DownloadChannel.BLUETOOTH) "蓝牙" else "Wi-Fi"
    val detail = if (failed || (task.status == DownloadStatus.CANCELLED && task.outputUri != null)) task.message else {
        val size = if (task.expectedBytes > 0) " · ${bytes(task.received)} / ${bytes(task.expectedBytes)}" else ""
        "$channel · ${task.variant.label()}$size"
    }
    val progress = if (running && task.expectedBytes > 0) (task.received.toDouble() / task.expectedBytes).toFloat().coerceAtMost(0.99f) else null
    val open = task.outputUri?.takeIf { task.status == DownloadStatus.COMPLETED }?.let { uri -> { onOpenOutput(uri) } }
    TaskRow(thumbnail, task.photo.asset.name, status, detail.replace("下载", "导入").replace("原图", "原片"), failed, progress, open) {
        when (task.status) {
            DownloadStatus.WAITING -> Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { actions.onRetry(task.id) }) { Text("重试") }
                IconButton(onClick = { actions.onCancel(task.id) }) { BridgeIcon(BridgeGlyph.CLOSE, "取消任务并清理未完成副本") }
            }
            DownloadStatus.QUEUED, DownloadStatus.RUNNING -> TextButton(onClick = { actions.onCancel(task.id) }) { Text("取消") }
            DownloadStatus.CANCELLED -> if (task.outputUri != null) {
                TextButton(onClick = { actions.onCancel(task.id) }) { Text("清理副本") }
            } else TextButton(onClick = { actions.onRetry(task.id) }) { Text("重试") }
            DownloadStatus.COMPLETED -> Unit
        }
    }
}

@Composable
private fun ExportRow(task: ExportTask, thumbnail: PhotoAsset, actions: TaskQueueActions, onOpenOutput: (String) -> Unit) {
    val failed = task.status == ExportStatus.FAILED
    val status = when (task.status) {
        ExportStatus.QUEUED -> "排队"
        ExportStatus.RUNNING -> "导出中"
        ExportStatus.COMPLETED -> "成功"
        ExportStatus.FAILED -> "失败"
        ExportStatus.CANCELLED -> "已取消"
    }
    val open = task.outputUri?.takeIf { task.status == ExportStatus.COMPLETED }?.let { uri -> { onOpenOutput(uri) } }
    val details = listOfNotNull(task.options.format.label(), task.options.look?.takeUnless { it.isIdentity }?.name,
        task.options.frame?.template?.name, if (task.options.destination == ExportDestination.GALLERY) "相册" else "文件夹", task.message)
    TaskRow(thumbnail, task.asset.name, status, details.joinToString(" · "), failed,
        if (task.status == ExportStatus.RUNNING) task.progress else null, open) {
        when (task.status) {
            ExportStatus.QUEUED, ExportStatus.RUNNING -> TextButton(onClick = { actions.onCancel(task.id) }) { Text("取消") }
            ExportStatus.FAILED, ExportStatus.CANCELLED -> Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { actions.onRetry(task.id) }) { Text("重试") }
                if (task.outputUri != null) IconButton(onClick = { actions.onCancel(task.id) }) {
                    BridgeIcon(BridgeGlyph.CLOSE, "清理未完成的导出副本")
                }
            }
            ExportStatus.COMPLETED -> Unit
        }
    }
}

private fun bytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GB", value / (1024.0 * 1024 * 1024))
    value >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024))
    else -> "${value / 1024} KB"
}
