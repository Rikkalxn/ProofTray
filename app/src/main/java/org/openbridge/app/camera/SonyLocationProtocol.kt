package org.openbridge.app.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.UUID

/** Sony DD00 location records. Reserved bytes are zero; altitude is not part of this record. */
internal object SonyLocationProtocol {
    val service: UUID = UUID.fromString("8000dd00-dd00-ffff-ffff-ffffffffffff")
    const val STATUS = 0xdd01
    const val LOCATION = 0xdd11
    const val CAPABILITIES = 0xdd21
    const val LOCK = 0xdd30
    const val ENABLE = 0xdd31
    const val TIME_SETTING = 0xdd32
    const val AREA_SETTING = 0xdd33
    const val MAX_AGE_NANOS = 10_000_000_000L

    fun supportsTimeZone(value: ByteArray): Boolean = value.size >= 5 && value[4].toInt() and 2 != 0
    fun disabled(value: ByteArray): Boolean = value.contentEquals(byteArrayOf(3, 1, 2, 0))

    fun encode(fix: CameraLocationFix, includeTimeZone: Boolean, zone: TimeZone = TimeZone.getDefault()): ByteArray {
        require(fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
            fix.longitude.isFinite() && fix.longitude in -180.0..180.0 && fix.timeMillis > 0) { "定位数据无效" }
        val utc = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { timeInMillis = fix.timeMillis }
        require(utc.get(Calendar.ERA) == 1 && utc.get(Calendar.YEAR) in 1..65535) { "定位时间无效" }
        val bytes = ByteArray(if (includeTimeZone) 95 else 91)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        bytes[1] = (bytes.size - 2).toByte()
        bytes[2] = 8; bytes[3] = 2; bytes[4] = 0xfc.toByte()
        bytes[5] = if (includeTimeZone) 3 else 0
        bytes[8] = 0x10; bytes[9] = 0x10; bytes[10] = 0x10
        buffer.putInt(11, (fix.latitude * 10_000_000).toInt())
        buffer.putInt(15, (fix.longitude * 10_000_000).toInt())
        buffer.putShort(19, utc.get(Calendar.YEAR).toShort())
        bytes[21] = (utc.get(Calendar.MONTH) + 1).toByte()
        bytes[22] = utc.get(Calendar.DAY_OF_MONTH).toByte()
        bytes[23] = utc.get(Calendar.HOUR_OF_DAY).toByte()
        bytes[24] = utc.get(Calendar.MINUTE).toByte()
        bytes[25] = utc.get(Calendar.SECOND).toByte()
        if (includeTimeZone) {
            val standardMinutes = zone.rawOffset / 60_000
            val daylightMinutes = if (zone.inDaylightTime(Date(fix.timeMillis))) zone.getDSTSavings() / 60_000 else 0
            require(standardMinutes in -32768..32767 && daylightMinutes in -32768..32767) { "手机时区无效" }
            buffer.putShort(91, standardMinutes.toShort())
            buffer.putShort(93, daylightMinutes.toShort())
        }
        return bytes
    }
}
