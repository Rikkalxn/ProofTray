package org.prooftray.app.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyMediaProtocolTest {
    @Test fun sessionOpeningUsesTheMediaServerCapabilityAndTheServerVersion() {
        val xml = "<root><X_ServerVersion>3.0</X_ServerVersion><X_PTP_MediaServerSupport>Disable</X_PTP_MediaServerSupport></root>"
        val disabled = sonyDescription(xml.toByteArray())
        assertFalse(disabled.usesSdioOpen(setOf(0x9210)))
        assertTrue(sonyDescription(xml.replace("Disable", "Enable").toByteArray()).usesSdioOpen(emptySet()))
        assertTrue(sonyDescription(xml.replace("3.0", "4.0").toByteArray()).usesSdioOpen(emptySet()))
        assertTrue(sonyDescription(byteArrayOf()).usesSdioOpen(setOf(0x9210)))
    }

    private fun scalar(type: Int, enabled: Int, current: ByteArray): SonyScalar {
        val bytes = ByteBuffer.allocate(6 + current.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xd1d4.toShort()).putShort(type.toShort()).put(0).put(enabled.toByte())
            .put(ByteArray(current.size)).put(current).array()
        return requireNotNull(sonyScalar(bytes, 0xd1d4))
    }

    @Test fun readableSlotsUseTheLowWireByteAndAcceptDisplayOnlyProperties() {
        for (enabled in 0..3) for (value in listOf(0, 1, 3, 0x101, 0x103, 0xffff)) {
            val current = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
            val property = scalar(4, enabled, current)
            assertEquals(value.toLong(), property.value)
            assertEquals(enabled in 1..2 && value in listOf(1, 0x101), property.contentsReadable)
        }
    }

    @Test fun signedPropertyTypesRetainNegativeSentinels() {
        for ((signed, width, unsignedValue) in listOf(Triple(1, 1, 255L), Triple(3, 2, 65535L), Triple(5, 4, 0xffff_ffffL))) {
            val bytes = ByteArray(width) { -1 }
            assertEquals(-1L, scalar(signed, 1, bytes).value)
            assertEquals(unsignedValue, scalar(signed + 1, 1, bytes).value)
        }
        assertFalse(scalar(1, 1, byteArrayOf(-1)).contentsReadable)
    }

    @Test fun emptyCapabilityTablesAreValidButTruncatedControlListsAreNot() {
        val empty = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putShort(300).putInt(0).putInt(0).array()
        assertEquals(SonyExtendedCodes(), sonyExtendedCodes(empty))
        val tables = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort(300)
            .putInt(2).putShort(0xd1d4.toShort()).putShort(0xd1d5.toShort()).putInt(1).putShort(0xd2c1.toShort()).array()
        assertEquals(SonyExtendedCodes(setOf(0xd1d4, 0xd1d5), setOf(0xd2c1)), sonyExtendedCodes(tables))
        assertThrows(IllegalArgumentException::class.java) { sonyExtendedCodes(tables.copyOf(tables.size - 1)) }
    }

    @Test fun phoneSelectionReadsCurrentStatusAfterControlAndVariableLengthDescriptors() {
        val properties = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN).putLong(4)
        properties.putShort(0xd2c1.toShort()).putShort(6).put(0).put(0).putInt(0).put(0)
        properties.putShort(0xd278.toShort()).putShort(0xffff.toShort()).put(0).put(1)
            .put(0).put(2).putShort(65).putShort(0).put(0)
        properties.putShort(0x500a).putShort(4).put(1).put(1).putShort(1).putShort(1)
            .put(2).putShort(1).putShort(1).putShort(2).putShort(0).putShort(1)
        val missing = properties.array().copyOf(properties.position())
        ByteBuffer.wrap(missing).order(ByteOrder.LITTLE_ENDIAN).putLong(3)
        assertNull(sonyTransferModeStatus(missing, setOf(0xd2c1)))
        properties.putShort(0xd295.toShort()).putShort(2).put(0).put(1).put(0).put(1).put(0)
        assertEquals(1L, sonyTransferModeStatus(properties.array().copyOf(properties.position()), setOf(0xd2c1)))
    }

    @Test fun fileSizeReportsRequireAnEnabledSupportedCommandVersion() {
        assertTrue(SonyScalar(1, 101).supportsFileSizeReport)
        assertFalse(SonyScalar(1, 100).supportsFileSizeReport)
        assertFalse(SonyScalar(2, 101).supportsFileSizeReport)
        assertFalse(SonyScalar(1, 0xffff_ffffL).supportsFileSizeReport)
    }

    @Test fun firstChunkSizeReportsAndLaterTimestampsUseSeparateChecks() {
        val first = longArrayOf(200, 0, 1000, 0)
        assertEquals(200L, validateSonyReadMetadata(first, 1000, 100, true))
        assertThrows(IllegalStateException::class.java) { validateSonyReadMetadata(first, 999, 100, true) }
        assertThrows(IllegalStateException::class.java) { validateSonyReadMetadata(first, 1000, 100, false) }
        assertEquals(200L, validateSonyReadMetadata(longArrayOf(200, 0), 1000, 200, false))
        assertThrows(IllegalStateException::class.java) { validateSonyReadMetadata(longArrayOf(201, 0), 1000, 200, false) }
        assertNull(validateSonyReadMetadata(longArrayOf(), 1000, 200, false))
    }
}
