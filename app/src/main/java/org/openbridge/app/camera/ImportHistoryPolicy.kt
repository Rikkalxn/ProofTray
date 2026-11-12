package org.openbridge.app.camera

internal fun importHistoryBlockReason(tasks: List<CameraDownload>, automatic: AutoDownloadState): String? = when {
    tasks.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING } -> "请先完成或取消排队中、进行中的导入任务"
    automatic.transferring || automatic.checking -> "正在检查或接收相机照片，请稍后再试"
    else -> null
}
