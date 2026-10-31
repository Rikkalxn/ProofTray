package org.openbridge.app.camera

import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException

internal class BleTransportException(val status: Int, phase: String = "通道断开") : IOException("BLE $phase，状态码 $status")

internal fun retryableBleFailure(error: Throwable): Boolean =
    (error is BleTransportException && error.status in setOf(8, 19, 133, 257)) || error is TimeoutCancellationException

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
