package org.prooftray.app.camera

internal fun bluetoothScanFailure(code: Int): String {
    val reason = when (code) {
        1 -> "已有相同的扫描在运行"
        2 -> "Android 未能登记蓝牙扫描"
        3 -> "Android 蓝牙服务内部错误"
        4 -> "手机不支持本次蓝牙扫描方式"
        5 -> "手机蓝牙扫描资源不足"
        6 -> "扫描请求过于频繁，请稍后重试"
        else -> "手机未能完成蓝牙扫描"
    }
    return "$reason（Android 扫描码 $code）"
}

internal data class NikonAdvertisement(val clientId: Int, val flags: Int)

// The first four identity bytes are sent in the same order in the manufacturer record and the LSS handshake.
internal fun nikonClientId(bytes: ByteArray): Int = java.nio.ByteBuffer.wrap(bytes).int

internal fun nikonAdvertisement(payloads: List<ByteArray>): NikonAdvertisement? = payloads.filter { it.size >= 5 }
    .map { NikonAdvertisement(nikonClientId(it), it[4].toInt() and 255) }.distinct().singleOrNull()

internal fun advertisementHasTransfers(flags: Int?): Boolean = flags != null && flags and 2 != 0

internal fun advertisementNeedsWake(flags: Int?): Boolean = flags != null && flags and 1 != 0 && flags and 8 == 0
