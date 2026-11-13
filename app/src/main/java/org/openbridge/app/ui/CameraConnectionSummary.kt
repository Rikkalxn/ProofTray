package org.openbridge.app.ui

import org.openbridge.app.camera.ConnectionPhase
import org.openbridge.app.camera.CameraMake
import org.openbridge.app.domain.AutoDownloadKind

internal data class CameraConnectionSummary(val title: String, val detail: String, val busy: Boolean, val bluetoothReady: Boolean)

internal fun cameraConnectionSummary(state: LibraryUiState): CameraConnectionSummary {
    val camera = state.camera
    val ble = state.ble
    val ready = !state.settings.sonySelected && ble.authenticated && ble.systemPaired && !ble.pairingInProgress
    return when {
        camera.connectionPhase == ConnectionPhase.DISCONNECTING -> CameraConnectionSummary("正在断开相机", "", true, false)
        camera.manuallyDisconnected && !camera.connected && !camera.connecting ->
            CameraConnectionSummary("相机已断开", "", false, false)
        camera.connecting -> CameraConnectionSummary("正在连接相机", if (ble.pairingInProgress) ble.message else camera.stage, true, ready)
        camera.connected -> CameraConnectionSummary("Wi-Fi 原片通道已就绪", camera.stage, false, ready)
        camera.connectionPhase == ConnectionPhase.FAILED -> CameraConnectionSummary(
            when { ready -> "蓝牙已连接 · Wi-Fi 未连接"; camera.stage == "连接已断开" -> "相机连接已断开"; else -> "相机连接未完成" },
            camera.message.orEmpty(), false, ready)
        !ble.adapterEnabled -> CameraConnectionSummary("手机蓝牙已关闭", "开启手机蓝牙后可连接相机；已有 Wi-Fi 连接仍可使用", false, false)
        state.settings.sonySelected -> CameraConnectionSummary(if (camera.sonyControlConnected) "索尼蓝牙已连接" else "已配对 · 相机未连接",
            "需要浏览和导入原片时，再连接相机 Wi-Fi", false, false)
        ble.pairingInProgress -> CameraConnectionSummary("正在连接蓝牙", ble.message, true, false)
        state.automatic.checking -> CameraConnectionSummary("正在检查待传照片", state.automatic.message, true, ready)
        state.automatic.transferring -> CameraConnectionSummary("蓝牙传图中", state.automatic.message, false, ready)
        ready -> CameraConnectionSummary("蓝牙已就绪", if (state.settings.autoDownload) state.automatic.message
            else "可接收便携 JPEG，或连接 Wi-Fi 浏览原片", false, true)
        state.automatic.watching && state.settings.autoKind == AutoDownloadKind.CAMERA_JPEG ->
            CameraConnectionSummary("蓝牙待机监听", state.automatic.message, false, false)
        state.settings.controlAddress.isNotBlank() -> CameraConnectionSummary("已保存相机", ble.message, false, false)
        else -> CameraConnectionSummary("还未添加相机", "先配对一次，再选择蓝牙便携图或 Wi-Fi 原片", false, false)
    }
}

internal enum class CameraAction { BROWSE, WAKE_WIFI, RETRY, BLUETOOTH_SETTINGS, ADD, MANUAL, CANCEL, NONE }

internal data class CameraPrimaryAction(val action: CameraAction, val label: String, val glyph: BridgeGlyph = BridgeGlyph.CAMERA)

/** The import card and connection page share the same action priority; no protocol or retry policy lives here. */
internal fun cameraPrimaryAction(
    state: LibraryUiState, summary: CameraConnectionSummary = cameraConnectionSummary(state)
): CameraPrimaryAction {
    val camera = state.camera
    val saved = state.settings.selectedAddress.isNotBlank()
    val nikon = saved && !state.settings.sonySelected && camera.make != CameraMake.SONY
    val failed = camera.connectionPhase == ConnectionPhase.FAILED
    return when {
        camera.connectionPhase == ConnectionPhase.DISCONNECTING -> CameraPrimaryAction(CameraAction.NONE, "正在断开")
        summary.busy -> CameraPrimaryAction(CameraAction.CANCEL, "取消连接", BridgeGlyph.CLOSE)
        camera.connected -> CameraPrimaryAction(CameraAction.BROWSE, "浏览照片", BridgeGlyph.PHOTOS)
        !saved && camera.manuallyDisconnected -> CameraPrimaryAction(CameraAction.MANUAL, "重新连接 Wi-Fi", BridgeGlyph.WIFI)
        !state.ble.adapterEnabled -> CameraPrimaryAction(CameraAction.BLUETOOTH_SETTINGS, "打开手机蓝牙", BridgeGlyph.BLUETOOTH)
        nikon -> CameraPrimaryAction(CameraAction.WAKE_WIFI, "切换到 Wi-Fi", BridgeGlyph.WIFI)
        failed -> CameraPrimaryAction(CameraAction.RETRY, "重试连接", BridgeGlyph.REFRESH)
        saved && camera.manuallyDisconnected -> CameraPrimaryAction(CameraAction.WAKE_WIFI, "重新连接相机", BridgeGlyph.WIFI)
        saved -> CameraPrimaryAction(CameraAction.WAKE_WIFI, "连接相机 Wi-Fi", BridgeGlyph.WIFI)
        else -> CameraPrimaryAction(CameraAction.ADD, "添加相机", BridgeGlyph.ADD)
    }
}

internal fun performCameraAction(
    action: CameraAction, actions: LibraryActions, onBrowse: () -> Unit, onPanel: (CameraPanel) -> Unit
) {
    when (action) {
        CameraAction.BROWSE -> onBrowse()
        CameraAction.WAKE_WIFI -> actions.onWakeWifi()
        CameraAction.RETRY -> actions.onRetryConnection()
        CameraAction.BLUETOOTH_SETTINGS -> actions.onOpenBluetoothSettings()
        CameraAction.ADD -> onPanel(CameraPanel.SEARCH)
        CameraAction.MANUAL -> onPanel(CameraPanel.MANUAL)
        CameraAction.CANCEL -> actions.onDisconnectCamera()
        CameraAction.NONE -> Unit
    }
}
