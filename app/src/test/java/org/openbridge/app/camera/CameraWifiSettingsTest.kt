package org.openbridge.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraWifiSettingsTest {
    private fun packet(security: Int = 1): ByteArray = ByteArray(102).apply {
        this[0] = 3
        "Test camera".toByteArray().copyInto(this, 1)
        "test-passphrase".toByteArray().copyInto(this, 33)
        this[97] = security.toByte()
    }

    @Test fun fixedWidthFieldsAndOptionalBluetoothTailAreSeparated() {
        val result = decodeCameraWifiSettings(packet()) { it.copyOf() }
        assertEquals("Test camera", result.ssid)
        assertEquals("test-passphrase", result.password)
        assertEquals(CameraWifiSecurity.WPA2, result.security)
        assertFalse(result.toString().contains(result.password))
        assertFalse(CameraConnection(ssid = result.ssid, password = result.password).toString().contains(result.password))
    }

    @Test fun openNetworksDoNotInterpretUnusedPasswordBytes() {
        val packet = packet(0).apply { fill(0xff.toByte(), 33, 97) }
        val result = decodeCameraWifiSettings(packet) { bytes ->
            assertEquals(32, bytes.size)
            bytes
        }
        assertEquals("", result.password)
        assertEquals(CameraWifiSecurity.OPEN, result.security)
    }

    @Test fun malformedOrUnsupportedSettingsNeverBecomeNetworkRequests() {
        assertThrows(IllegalArgumentException::class.java) { decodeCameraWifiSettings(ByteArray(10)) { it } }
        assertThrows(IllegalArgumentException::class.java) { decodeCameraWifiSettings(packet().copyOf(97)) { it } }
        assertThrows(IllegalStateException::class.java) { decodeCameraWifiSettings(packet(4)) { it } }
        assertThrows(IllegalArgumentException::class.java) {
            decodeCameraWifiSettings(packet().apply { this[1] = 0xff.toByte() }) { it }
        }
        assertEquals("Test camera", decodeCameraWifiSettings(packet().apply { this[14] = 1 }) { it }.ssid)
    }
}
