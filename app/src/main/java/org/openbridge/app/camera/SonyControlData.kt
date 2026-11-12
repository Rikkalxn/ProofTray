package org.openbridge.app.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Retained only for the selected camera session. The fingerprint comes from the bonded camera or its own display. */
class SonyAccess internal constructor(internal val user: String, internal val password: String, fingerprint: String) {
    private val expected = parseFingerprint(fingerprint)
    internal val cameraKey: String get() = "sony:ssh:" + Base64.getEncoder().withoutPadding().encodeToString(expected)

    init { require(user.isNotBlank() && password.isNotEmpty()) { "相机未提供完整的访问认证信息" } }

    internal fun accepts(key: ByteArray): Boolean = MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(key))
    override fun toString() = "SonyAccess"

    private fun parseFingerprint(value: String): ByteArray {
        val body = value.trim().replaceFirst(Regex("^SHA256:", RegexOption.IGNORE_CASE), "")
        val hex = body.replace(":", "")
        val bytes = if (hex.matches(Regex("[0-9a-fA-F]{64}"))) hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            else runCatching { Base64.getDecoder().decode(body) }.getOrNull()
        return requireNotNull(bytes?.takeIf { it.size == 32 }) { "相机的 SSH 指纹不是有效的 SHA-256 指纹" }
    }
}

internal object SonyControlData {
    const val COMPANY_ID = 0x012d
    val controlService: UUID = UUID.fromString("8000cc00-cc00-ffff-ffff-ffffffffffff")
    val pairingService: UUID = UUID.fromString("8000ee00-ee00-ffff-ffff-ffffffffffff")

    fun wifiText(value: ByteArray): String {
        require(value.size in 3..67) { "相机热点数据长度异常" }
        return value.copyOfRange(3, value.size).toString(Charsets.UTF_8).trimEnd('\u0000')
    }

    fun bssid(value: ByteArray): String? {
        val text = value.toString(Charsets.US_ASCII).trimEnd('\u0000')
        if (!text.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) || text == "00:00:00:00:00:00") return null
        return text.takeIf { it.substring(0, 2).toInt(16) and 1 == 0 }
    }

    data class WifiStatus(val state: Int, val error: Int) {
        val ready get() = state == 2
        val launching get() = state == 1
        val terminating get() = state == 3
        val terminated get() = state == 0
    }

    fun wifiStatus(data: ByteArray): WifiStatus? {
        var offset = 0
        while (offset + 3 <= data.size) {
            val size = data[offset].toInt() and 255
            if (size < 2 || size >= data.size - offset) return null
            if (size >= 4 && data[offset + 1] == 0.toByte() && data[offset + 2] == 1.toByte()) {
                return WifiStatus(data[offset + 3].toInt() and 255, data[offset + 4].toInt() and 255)
            }
            offset += size + 1
        }
        return null
    }

    /** The two-byte type is followed by access state, then three length-prefixed ASCII fields. */
    fun access(value: ByteArray): SonyAccess? {
        require(value.size >= 4 && value[1] == 0.toByte() && value[2] == 0.toByte()) { "相机访问认证数据无效" }
        val end = (value[0].toInt() and 255) + 1
        require(end in 4..value.size) { "相机访问认证数据不完整" }
        if (value[3] == 1.toByte()) return null
        check(value[3] == 2.toByte()) { "相机尚未报告访问认证状态，请在机身确认" }
        var offset = 4
        fun next(): String {
            require(offset < end) { "相机访问认证字段缺失" }
            val size = value[offset++].toInt() and 255
            require(size > 0 && size <= end - offset) { "相机访问认证字段长度异常" }
            val start = offset
            offset += size
            return value.copyOfRange(start, offset).toString(Charsets.US_ASCII)
        }
        val access = SonyAccess(next(), next(), next())
        require(offset == end) { "相机访问认证数据有未知字段" }
        return access
    }

    /** Null is an intermediate notification. A final result is distinct from both GATT success and the system bond. */
    fun pairResult(value: ByteArray): Boolean? {
        if (value.size < 8 || value[0] != 7.toByte() || value[1] != 0.toByte() || value[2] != 0.toByte()) return null
        val code = ByteBuffer.wrap(value, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        return when (value[3].toInt()) {
            2 -> code == 1
            1 -> if (code in setOf(3, 7, 9)) false else null
            else -> null
        }
    }
}
