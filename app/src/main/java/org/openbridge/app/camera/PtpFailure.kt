package org.openbridge.app.camera

import java.io.IOException

internal class PtpResponseException(val operation: Int, val response: Int, val receivedBytes: Long = 0) :
    IOException(ptpFailureMessage(operation, response))

internal fun ptpFailureMessage(operation: Int, response: Int): String = when (response) {
    0x2009 -> "相机当前无法读取这张照片。请刷新导入列表，确认照片仍在卡上，再重试。"
    0x2013 -> "相机存储卡尚未就绪。请检查存储卡，再刷新导入列表。"
    0x2019 -> "相机正忙。请等机身写入完成或退出菜单后重试。"
    0x2003, 0x201e -> "相机传输会话已变化。请断开并重新连接。"
    0x200f -> "相机拒绝访问。请查看机身提示并确认连接。"
    0x201f -> "相机已取消本次传输，可以重新选择照片。"
    0x2010 -> "这张照片没有机内缩略图，可以导入原片查看。"
    0x2005, 0x200a, 0x201d -> when (operation) {
        0x100a, 0x9803, 0x923e -> "机身未提供这种预览。请刷新后重试，或导入原片查看。"
        0x1009, 0x101b, 0x9211, 0x923d -> "相机不接受本次文件读取。请刷新导入列表后重试。"
        else -> "机身不支持本次操作。请检查机身的传输模式后重试。"
    }
    else -> "相机未完成本次操作。请查看机身提示后重试。"
}

private val oldPtpMessage = Regex("""PTP\s*操作\s*0x([0-9a-f]+)\s*返回\s*0x([0-9a-f]+)""", RegexOption.IGNORE_CASE)

internal fun restoredCameraMessage(stored: String): String = oldPtpMessage.replace(stored) {
    val op = it.groupValues[1].toIntOrNull(16)
    val code = it.groupValues[2].toIntOrNull(16)
    if (op != null && code != null) ptpFailureMessage(op, code) else it.value
}
