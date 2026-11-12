package org.openbridge.app.camera

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class CameraWifiSecurity { OPEN, WPA2, WPA3, WPA2_WPA3 }

/** Credentials belong to one authenticated connection. Do not log or persist this object. */
internal class CameraWifiSettings(val ssid: String, val password: String, val security: CameraWifiSecurity) {
    override fun toString(): String = "CameraWifiSettings(security=$security)"
}

internal fun decodeCameraWifiSettings(packet: ByteArray, decrypt: (ByteArray) -> ByteArray): CameraWifiSettings {
    require(packet.isNotEmpty() && packet[0].toInt() and 1 != 0) { "机身没有提供热点配置，请使用手动连接" }
    require(packet.size >= 98) { "机身热点配置不完整，请使用手动连接" }
    val security = CameraWifiSecurity.entries.getOrNull(packet[97].toInt() and 255)
        ?: error("暂不支持机身提供的热点加密方式，请使用系统 Wi-Fi 设置连接")
    val ssidBytes = decrypt(packet.copyOfRange(1, 33))
    try {
        require(ssidBytes.size == 32) { "热点名称解密长度异常" }
        val ssid = connectionText(ssidBytes, "名称")
        require(ssid.isNotEmpty() && ssid.toByteArray(Charsets.UTF_8).size <= 32 && ssid.none(Char::isISOControl)) {
            "热点名称解密校验失败，请重新连接蓝牙或使用手动连接"
        }
        val password = if (security == CameraWifiSecurity.OPEN) "" else {
            val bytes = decrypt(packet.copyOfRange(33, 97))
            try {
                require(bytes.size == 64) { "热点密码解密长度异常" }
                connectionText(bytes, "密码").also { value ->
                    require(value.length in 8..63 && value.all { it.code in 32..126 }) {
                        "热点密码格式不受支持，请使用系统 Wi-Fi 设置连接"
                    }
                }
            } finally { bytes.fill(0) }
        }
        return CameraWifiSettings(ssid, password, security)
    } finally { ssidBytes.fill(0) }
}

private fun connectionText(bytes: ByteArray, field: String): String {
    val end = bytes.indexOf(0).takeIf { it >= 0 } ?: bytes.size
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, end)).toString()
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("无法解读相机热点$field，请重新连接蓝牙后再切换 Wi-Fi", error)
    }
    return text.removeSurrounding("\"")
}
