package org.openbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openbridge.app.camera.CameraDownload
import org.openbridge.app.camera.DownloadStatus
import org.openbridge.app.camera.DownloadChannel
import org.openbridge.app.domain.DownloadVariant
import org.openbridge.app.domain.ExportStatus
import org.openbridge.app.domain.ExportTask
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(state: LibraryUiState, actions: LibraryActions) {
    var source by rememberSaveable { mutableStateOf(0) }
    val downloads = source == 0
    val paused = if (downloads) state.camera.paused else state.exportPaused
    val count = if (downloads) state.camera.tasks.size else state.tasks.size
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            listOf("相机下载", "本地导出").forEachIndexed { index, label ->
                SegmentedButton(selected = source == index, onClick = { source = index }, shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                    Text(label)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$count 个任务", style = MaterialTheme.typography.titleMedium)
                Text(if (paused) "后续任务已暂停" else "按顺序处理，完成后保存到手机", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (count > 0) FilledTonalButton(onClick = {
                if (downloads) actions.onPauseDownloads(!paused) else actions.onPauseExports(!paused)
            }) { Text(if (paused) "继续" else "暂停") }
        }
        if (count == 0) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                            BridgeIcon(BridgeGlyph.TASKS, modifier = Modifier.size(48.dp))
                        }
                    }
                    Text("暂时没有任务", style = MaterialTheme.typography.headlineSmall)
                    Text(if (downloads) "在照片页选择相机照片，再点下载原片。" else "在照片页选择本地照片，再点导出。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (downloads) items(state.camera.tasks, key = { it.id }) { task -> DownloadCard(task, actions) }
            else items(state.tasks, key = { it.id }) { task -> ExportCard(task, actions) }
        }
    }
}

@Composable
private fun TaskCard(
    name: String, status: String, detail: String, active: Boolean, error: Boolean, progress: Float?,
    subtitle: String, actions: @Composable () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { BridgeIcon(BridgeGlyph.PHOTOS) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StatusBadge(status, active = active, error = error)
            progress?.let { LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
    }
}

@Composable
private fun DownloadCard(task: CameraDownload, actions: LibraryActions) {
    val running = task.status == DownloadStatus.RUNNING
    val label = when (task.status) {
        DownloadStatus.QUEUED -> "排队中"
        DownloadStatus.RUNNING -> "下载中"
        DownloadStatus.WAITING -> "等待重试"
        DownloadStatus.COMPLETED -> "已保存"
        DownloadStatus.CANCELLED -> "已取消"
    }
    val progress = if (running && task.expectedBytes > 0) (task.received.toDouble() / task.expectedBytes).toFloat().coerceAtMost(0.99f) else null
    val channel = if (task.channel == DownloadChannel.BLUETOOTH) "蓝牙" else "Wi-Fi"
    val size = if (task.expectedBytes > 0) "${bytes(task.received)} / ${bytes(task.expectedBytes)}" else "等待相机返回大小"
    TaskCard(task.photo.asset.name, label, task.message, running, task.status == DownloadStatus.WAITING, progress,
        "$channel · ${task.variant.label()} · $size") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (task.status == DownloadStatus.WAITING) FilledTonalButton(onClick = { actions.onRetryDownload(task.id) }) {
                Text(if (task.variant == DownloadVariant.ORIGINAL) "重试 / 续传" else "重试，重新获取小图")
            }
            if (task.status != DownloadStatus.COMPLETED && task.status != DownloadStatus.CANCELLED) {
                TextButton(onClick = { actions.onCancelDownload(task.id) }) { Text("取消任务") }
            }
            if (task.status == DownloadStatus.CANCELLED && task.outputUri != null) {
                TextButton(onClick = { actions.onCancelDownload(task.id) }) { Text("清理未完成副本") }
            }
            if (task.status == DownloadStatus.CANCELLED && task.outputUri == null) {
                TextButton(onClick = { actions.onRetryDownload(task.id) }) { Text("重新加入") }
            }
            if (task.status == DownloadStatus.COMPLETED) task.outputUri?.let { uri ->
                TextButton(onClick = { actions.onOpenOutput(uri) }) { Text("打开照片") }
            }
        }
    }
}

@Composable
private fun ExportCard(task: ExportTask, actions: LibraryActions) {
    val running = task.status == ExportStatus.RUNNING
    val label = when (task.status) {
        ExportStatus.QUEUED -> "排队中"
        ExportStatus.RUNNING -> "处理中"
        ExportStatus.COMPLETED -> "已导出"
        ExportStatus.FAILED -> "需要重试"
        ExportStatus.CANCELLED -> "已取消"
    }
    TaskCard(task.asset.name, label, task.message.orEmpty(), running, task.status == ExportStatus.FAILED,
        if (running) task.progress else null, task.options.format.label()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (task.status) {
                ExportStatus.QUEUED, ExportStatus.RUNNING -> TextButton(onClick = { actions.onCancelTask(task.id) }) { Text("取消任务") }
                ExportStatus.FAILED, ExportStatus.CANCELLED -> FilledTonalButton(onClick = { actions.onRetryTask(task.id) }) { Text("重试") }
                ExportStatus.COMPLETED -> task.outputUri?.let { uri ->
                    TextButton(onClick = { actions.onOpenOutput(uri) }) { Text("打开照片") }
                }
            }
        }
    }
}

private fun bytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GB", value / (1024.0 * 1024 * 1024))
    value >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024))
    else -> "${value / 1024} KB"
}
