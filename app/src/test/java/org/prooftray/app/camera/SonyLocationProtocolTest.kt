package org.prooftray.app.camera

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class SonyLocationProtocolTest {
    private val fix = CameraLocationFix(-1.00000009, 2.00000009, 123.0,
        Instant.parse("2026-01-02T03:04:05Z").toEpochMilli(), 1_000_000_000L)

    @Test fun commonRecordUsesSignedBigEndianCoordinatesAndUtcFixTime() {
        val prefix = "00 59 08 02 fc 00 00 00 10 10 10 ff 67 69 80 01 31 2d 00 07 ea 01 02 03 04 05"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(prefix + ByteArray(65), SonyLocationProtocol.encode(fix, false))
        assertArrayEquals(SonyLocationProtocol.encode(fix, false), SonyLocationProtocol.encode(fix.copy(altitude = Double.NaN), false))
    }

    @Test fun extendedRecordKeepsStandardOffsetSeparateFromDaylightSavings() {
        val summer = fix.copy(timeMillis = Instant.parse("2026-07-02T03:04:05Z").toEpochMilli())
        val packet = SonyLocationProtocol.encode(summer, true, TimeZone.getTimeZone("America/New_York"))
        assertEquals(95, packet.size)
        assertEquals(0x5d.toByte(), packet[1])
        assertEquals(3.toByte(), packet[5])
        assertArrayEquals(byteArrayOf(0xfe.toByte(), 0xd4.toByte(), 0, 60), packet.copyOfRange(91, 95))
        val nepal = SonyLocationProtocol.encode(fix, true, TimeZone.getTimeZone("Asia/Kathmandu"))
        assertArrayEquals(byteArrayOf(1, 0x59, 0, 0), nepal.copyOfRange(91, 95))
    }

    @Test fun capabilitiesAndFreshnessDoNotAcceptIncompleteOrExpiredData() {
        assertFalse(SonyLocationProtocol.supportsTimeZone(byteArrayOf(2)))
        assertFalse(SonyLocationProtocol.supportsTimeZone(byteArrayOf(0, 0, 0, 0, 1)))
        assertTrue(SonyLocationProtocol.supportsTimeZone(byteArrayOf(0, 0, 0, 0, 2)))
        assertTrue(fix.fresh(10_999_999_999L, SonyLocationProtocol.MAX_AGE_NANOS))
        assertFalse(fix.fresh(11_000_000_000L, SonyLocationProtocol.MAX_AGE_NANOS))
        assertFalse(fix.fresh(999_999_999L, SonyLocationProtocol.MAX_AGE_NANOS))
        assertThrows(IllegalArgumentException::class.java) { SonyLocationProtocol.encode(fix.copy(latitude = Double.NaN), false) }
        assertThrows(IllegalArgumentException::class.java) { SonyLocationProtocol.encode(fix.copy(longitude = 181.0), false) }
        assertThrows(IllegalArgumentException::class.java) { SonyLocationProtocol.encode(fix.copy(timeMillis = 0), false) }
    }
}
