package org.prooftray.app.camera

import no.nordicsemi.android.ble.callback.FailCallback

internal fun bluetoothGattCode(status: Int): String =
    if (status < 0) "蓝牙库状态 $status" else "GATT $status（0x${status.toString(16).uppercase().padStart(2, '0')}）"

internal fun bluetoothGattReason(status: Int, connecting: Boolean = false): String = when (status) {
    FailCallback.REASON_DEVICE_DISCONNECTED -> "蓝牙连接已断开"
    FailCallback.REASON_BLUETOOTH_DISABLED -> "手机蓝牙已关闭"
    FailCallback.REASON_DEVICE_NOT_SUPPORTED -> "机身未提供当前操作所需的蓝牙服务"
    FailCallback.REASON_NULL_ATTRIBUTE -> "机身缺少此蓝牙特征或描述符"
    FailCallback.REASON_REQUEST_FAILED -> "Android 未能执行这项蓝牙请求"
    FailCallback.REASON_TIMEOUT, 0x93 -> "等待蓝牙响应超时"
    FailCallback.REASON_CANCELLED -> "蓝牙请求已取消"
    0 -> "蓝牙链路已结束"
    0x05, 0x89 -> "蓝牙身份验证失败，请核对手机和机身的配对状态"
    0x85 -> "Android 蓝牙通信失败，请确认机身仍在等待连接后重试"
    else -> if (connecting) when (status) {
        0x08 -> "蓝牙连接超时"
        0x13 -> "机身已结束蓝牙连接"
        0x16 -> "手机已结束蓝牙连接"
        0x3e -> "未能建立蓝牙链路，请保持机身开机并靠近手机"
        else -> "未能建立蓝牙连接，请查看机身提示"
    } else when (status) {
        0x01, 0x0a, 0x12 -> "机身蓝牙服务已变化，请重新连接"
        0x02 -> "机身不允许读取此项数据"
        0x03 -> "机身不允许写入此项数据"
        0x06 -> "机身不支持此请求"
        0x08 -> "机身未授权此项操作"
        0x0f -> "此操作需要加密的配对连接"
        0x13 -> "机身不接受请求的参数"
        else -> "蓝牙请求未完成，请查看机身提示"
    }
}
