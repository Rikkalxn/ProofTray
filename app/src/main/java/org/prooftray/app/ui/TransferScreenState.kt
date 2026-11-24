package org.prooftray.app.ui

import org.prooftray.app.camera.CameraDownload
import org.prooftray.app.camera.DownloadStatus
import org.prooftray.app.domain.ExportStatus
import org.prooftray.app.domain.ExportTask
import org.prooftray.app.domain.PhotoAsset

internal data class TransferScreenState(
    val downloads: List<CameraDownload>, val exports: List<ExportTask>, val assets: List<PhotoAsset>,
    val downloadsPaused: Boolean, val exportsPaused: Boolean
)

data class TaskQueueActions(
    val onPause: (Boolean) -> Unit, val onRetry: (String) -> Unit, val onCancel: (String) -> Unit, val onClearHistory: () -> Unit
)

data class TransferScreenActions(val downloads: TaskQueueActions, val exports: TaskQueueActions, val onOpenOutput: (String) -> Unit)

/** Adapt at the screen boundary; rows cannot reach camera pairing, library deletion or editor actions. */
internal fun LibraryUiState.transferState() = TransferScreenState(camera.tasks, tasks, assets, camera.paused, exportPaused)

internal enum class TaskView(val label: String) { ALL("全部"), ACTIVE("进行中"), FAILED("需重试"), COMPLETED("已完成") }

internal fun DownloadStatus.taskView(): TaskView = when (this) {
    DownloadStatus.QUEUED, DownloadStatus.RUNNING -> TaskView.ACTIVE
    DownloadStatus.WAITING -> TaskView.FAILED
    DownloadStatus.COMPLETED -> TaskView.COMPLETED
    DownloadStatus.CANCELLED -> TaskView.ALL
}

internal fun ExportStatus.taskView(): TaskView = when (this) {
    ExportStatus.QUEUED, ExportStatus.RUNNING -> TaskView.ACTIVE
    ExportStatus.FAILED -> TaskView.FAILED
    ExportStatus.COMPLETED -> TaskView.COMPLETED
    ExportStatus.CANCELLED -> TaskView.ALL
}
