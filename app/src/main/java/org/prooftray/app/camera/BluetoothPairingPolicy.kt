package org.prooftray.app.camera

import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import no.nordicsemi.android.ble.observer.ConnectionObserver

internal class BleTransportException(message: String, val retryable: Boolean = true) : IOException(message) {
    constructor(status: Int, phase: String = "通道断开") : this("BLE $phase，GATT 状态码 $status", status in setOf(0, 8, 19, 133, 257))
}

internal fun retryableBleFailure(error: Throwable): Boolean =
    (error is BleTransportException && error.retryable) || error is TimeoutCancellationException

/** ConnectionObserver reasons and Android GATT status numbers are distinct namespaces. */
internal fun nikonDisconnectFailure(reason: Int): BleTransportException = when (reason) {
    ConnectionObserver.REASON_TIMEOUT -> BleTransportException("尼康蓝牙控制连接已超时")
    ConnectionObserver.REASON_TERMINATE_PEER_USER -> BleTransportException("机身已断开蓝牙控制连接")
    ConnectionObserver.REASON_CANCELLED, ConnectionObserver.REASON_TERMINATE_LOCAL_HOST -> BleTransportException("手机已结束蓝牙控制连接", false)
    ConnectionObserver.REASON_NOT_SUPPORTED, ConnectionObserver.REASON_UNSUPPORTED_CONFIGURATION ->
        BleTransportException("机身未能建立兼容的蓝牙连接（Nordic 原因 $reason）", false)
    else -> BleTransportException("尼康蓝牙控制链路中断（Nordic 原因 $reason）")
}

internal fun initializeCameraPairing(explicitRequest: Boolean, systemBondExists: Boolean): Boolean = explicitRequest || !systemBondExists

internal fun cameraBluetoothName(name: String?): String = name.orEmpty().substringBefore('\u0000').trim()

/** Z6 II releases BLE before accepting the classic data connection; keep this quirk limited to the identified model. */
internal fun needsBluetoothHandoff(names: Set<String>): Boolean = names.any {
    Regex("^Z[ _]6[ _]2(?:_|$)", RegexOption.IGNORE_CASE).containsMatchIn(cameraBluetoothName(it))
}

internal fun isNikonCameraName(name: String): Boolean = name.contains("Nikon", ignoreCase = true) ||
    Regex("Z[ _-]?[0-9fF].*", RegexOption.IGNORE_CASE).matches(name.trim())

internal fun matchesCameraName(name: String?, expected: Set<String>): Boolean {
    val target = cameraBluetoothName(name)
    if (target.isBlank() || target in setOf("Nikon 相机", "已配对设备", "已保存相机")) return false
    return expected.any { target.equals(cameraBluetoothName(it), ignoreCase = true) }
}
