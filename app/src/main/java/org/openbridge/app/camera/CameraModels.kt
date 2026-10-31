package org.openbridge.app.camera

import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.DownloadVariant

enum class CameraMode { AP, STA }
data class CameraConnection(
    val mode: CameraMode = CameraMode.AP, val host: String = "", val port: Int = 15740, val ssid: String = "", val password: String = ""
)
data class CameraState(
    val connected: Boolean = false, val busy: Boolean = false, val cameraName: String = "",
    val stage: String = "相机未连接", val indexed: Int = 0, val total: Int = 0,
    val message: String? = null, val tasks: List<CameraDownload> = emptyList(), val paused: Boolean = false,
    val pairingCode: String? = null, val sourceId: String = "", val generation: Int = 0,
    val catalogBatchSize: Int = 50, val hasMore: Boolean = false, val catalogIds: Set<String> = emptySet(), val transmissionPaused: Boolean = false,
    val connecting: Boolean = false
) {
    val canConnect: Boolean get() = !connecting
}
data class RemotePhoto(val asset: PhotoAsset, val handle: Long, val storage: Long, val cameraIdentity: String, val captured: String)
enum class DownloadStatus { QUEUED, RUNNING, WAITING, COMPLETED, CANCELLED }
enum class DownloadChannel { WIFI, BLUETOOTH }
data class CameraDownload(
    val id: String, val photo: RemotePhoto, val status: DownloadStatus = DownloadStatus.QUEUED,
    val received: Long = 0, val outputUri: String? = null, val message: String = "等待下载",
    val variant: DownloadVariant = DownloadVariant.ORIGINAL, val automatic: Boolean = false, val payloadBytes: Long = 0,
    val channel: DownloadChannel = DownloadChannel.WIFI
) {
    val expectedBytes: Long get() = if (variant == DownloadVariant.ORIGINAL) photo.asset.sizeBytes else payloadBytes
}

data class AutoDownloadState(
    val watching: Boolean = false, val message: String = "自动下载未开启", val lastCheckMillis: Long = 0,
    val checking: Boolean = false, val lastCheckResult: String = ""
)
