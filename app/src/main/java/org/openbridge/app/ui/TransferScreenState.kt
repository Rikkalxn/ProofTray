package org.openbridge.app.ui

import org.openbridge.app.camera.CameraDownload
import org.openbridge.app.domain.ExportTask
import org.openbridge.app.domain.PhotoAsset

internal data class TransferScreenState(
    val downloads: List<CameraDownload>, val exports: List<ExportTask>, val assets: List<PhotoAsset>,
    val downloadsPaused: Boolean, val exportsPaused: Boolean
)

internal data class TaskQueueActions(
    val onPause: (Boolean) -> Unit, val onRetry: (String) -> Unit, val onCancel: (String) -> Unit, val onClearHistory: () -> Unit
)

internal data class TransferScreenActions(val downloads: TaskQueueActions, val exports: TaskQueueActions, val onOpenOutput: (String) -> Unit)

/** Adapt at the screen boundary; rows cannot reach camera pairing, library deletion or editor actions. */
internal fun LibraryUiState.transferState() = TransferScreenState(camera.tasks, tasks, assets, camera.paused, exportPaused)

internal fun LibraryActions.transferActions() = TransferScreenActions(
    TaskQueueActions(onPauseDownloads, onRetryDownload, onCancelDownload, onClearDownloadHistory),
    TaskQueueActions(onPauseExports, onRetryTask, onCancelTask, onClearExportHistory), onOpenOutput
)
