package org.prooftray.app.camera

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prooftray.app.domain.AppSettings
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.photoMimeType

class SonyImportTest {
    @Test fun pinnedAccessUsesTheBondedCameraKeyAndRejectsIncompleteRecords() {
        val key = byteArrayOf(1, 2, 3)
        val fingerprint = Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        val body = listOf("test-user", "test-pass", "SHA256:$fingerprint").fold(byteArrayOf(0, 0, 2)) { data, value ->
            data + value.length.toByte() + value.toByteArray(Charsets.US_ASCII)
        }
        val record = byteArrayOf(body.size.toByte()) + body
        val access = requireNotNull(SonyControlData.access(record))
        assertTrue(access.accepts(key))
        assertFalse(access.accepts(byteArrayOf(9)))
        assertFalse(access.toString().contains("test-pass"))
        assertThrows(IllegalArgumentException::class.java) { SonyControlData.access(record.copyOf(record.size - 1)) }
        assertThrows(IllegalStateException::class.java) { SonyControlData.access(byteArrayOf(3, 0, 0, 0)) }
        assertNull(SonyControlData.access(byteArrayOf(3, 0, 0, 1)))
    }

    @Test fun bluetoothAcknowledgementAndHotspotReadinessAreSeparate() {
        assertNull(SonyControlData.pairResult(byteArrayOf(7, 0, 0, 1, 0, 0, 0, 1)))
        assertEquals(false, SonyControlData.pairResult(byteArrayOf(7, 0, 0, 2, 0, 0, 0, 6)))
        assertEquals(true, SonyControlData.pairResult(byteArrayOf(7, 0, 0, 2, 0, 0, 0, 1)))
        assertFalse(requireNotNull(SonyControlData.wifiStatus(byteArrayOf(4, 0, 1, 1, 1))).ready)
        assertTrue(requireNotNull(SonyControlData.wifiStatus(byteArrayOf(3, 0, 8, 1, 4, 0, 1, 2, 0))).ready)
        assertNull(SonyControlData.wifiStatus(byteArrayOf(4, 0, 1, 2)))
        assertTrue(requireNotNull(SonyControlData.wifiStatus(byteArrayOf(4, 0, 1, 0, 5))).terminated)
        assertFalse(requireNotNull(SonyControlData.wifiStatus(byteArrayOf(4, 0, 1, 3, 0))).terminated)
    }

    @Test fun hotspotWireStatesAreIndependentOfErrorCodes() {
        for (state in 0..3) {
            for (error in listOf(0, 1, 5, 9)) {
                val status = requireNotNull(SonyControlData.wifiStatus(byteArrayOf(4, 0, 1, state.toByte(), error.toByte())))
                assertEquals(state, status.state)
                assertEquals(error, status.error)
                assertEquals(state == 2, status.ready)
                assertEquals(state == 1, status.launching)
                assertEquals(state == 3, status.terminating)
                assertEquals(state == 0, status.terminated)
            }
        }
    }

    @Test fun lanIdentityComesFromTheDigitalImagingAnnouncement() {
        val id = UUID.fromString("00000000-0000-1000-9000-000000000001")
        val st = "urn:schemas-sony-com:service:DigitalImaging:1"
        val response = "HTTP/1.1 200 OK\r\nsT: $st\r\nUSN: uuid:$id::$st\r\nLOCATION: http://example.invalid\r\n\r\n"
        assertEquals(id, sonyAnnouncement(response)?.id)
        assertNull(sonyAnnouncement(response.replace(st, "upnp:rootdevice")))
    }

    @Test fun cameraRawAndJpegShareCaptureButNeverFileIdentity() {
        fun photo(name: String, camera: String) = requireNotNull(cameraPhoto(JSONObject().apply {
            put("name", name); put("storage", 1); put("parent", 2); put("size", 5000)
            put("captured", "20261110T120000"); put("width", 6000); put("height", 4000)
        }, 42, camera, camera)).asset
        val arw = photo("DSC00001.ARW", "ILCE-7M5:123")
        val jpeg = photo("DSC00001.JPG", "ILCE-7M5:123")
        assertEquals(MediaFormat.RAW, arw.format)
        assertEquals("image/x-sony-arw", photoMimeType(arw.name))
        assertEquals(arw.cameraCaptureKey, jpeg.cameraCaptureKey)
        assertNotEquals(arw.cameraFileKey, jpeg.cameraFileKey)
        assertNotEquals(arw.id, photo(arw.name, "ILCE-7CM2:456").id)
    }

    @Test fun sonyDoesNotInheritNikonBluetoothDownloadsOrCollidingOperations() {
        assertEquals(CameraMake.SONY, ptpCameraMake("Sony Corporation", 6))
        assertEquals(CameraMake.NIKON, ptpCameraMake("Nikon Corporation", 10))
        val commands = previewCommands(true, setOf(GET_FHD_PICTURE, GET_LSS_IMAGE), false, emptySet())
        assertEquals(listOf(GET_THUMB), commands.map { it.operation })
        assertFalse(AppSettings(autoDownload = true, sonySelected = true).automaticEnabled)
        assertTrue(AppSettings(autoDownload = true).automaticEnabled)
    }
}
