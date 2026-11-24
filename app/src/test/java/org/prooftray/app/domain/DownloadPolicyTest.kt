package org.prooftray.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {
    @Test fun firstObservationNeverDownloadsAnOldCard() {
        val tracker = NewObjectTracker()
        assertTrue(tracker.observe(setOf(7, 900, 2).map(Int::toLong).toSet()).isEmpty())
        assertTrue(tracker.baselineReset)
        // A new handle may be smaller; numeric ordering is not capture chronology.
        assertEquals(listOf(1L), tracker.observe(setOf(7L, 900L, 2L, 1L)))
        assertFalse(tracker.baselineReset)
    }

    @Test fun pendingEntriesSurvivePollingUntilDurablyQueued() {
        val tracker = NewObjectTracker()
        tracker.observe(setOf(1L))
        assertEquals(listOf(2L), tracker.observe(setOf(1L, 2L)))
        assertEquals(listOf(2L), tracker.observe(setOf(1L, 2L)))
        tracker.acknowledge(2L)
        assertTrue(tracker.observe(setOf(1L, 2L)).isEmpty())
    }

    @Test fun cardChangesAndNewSessionsRebuildTheBaseline() {
        val tracker = NewObjectTracker()
        tracker.observe(setOf(1L, 2L))
        assertTrue(tracker.observe(setOf(8L, 9L)).isEmpty())
        assertTrue(tracker.baselineReset)
        assertTrue(NewObjectTracker().observe(setOf(1L, 2L, 100L)).isEmpty())
        val reused = NewObjectTracker()
        reused.observe(setOf(1L, 2L), "card-a")
        assertTrue(reused.observe(setOf(1L, 2L, 3L), "card-b").isEmpty())
        assertTrue(reused.baselineReset)
    }

    @Test fun autoFormatDoesNotPretendRawIsAJpegOriginal() {
        assertFalse(acceptsAutoFormat(MediaFormat.RAW, AutoDownloadKind.JPEG))
        assertTrue(acceptsAutoFormat(MediaFormat.RAW, AutoDownloadKind.CAMERA_JPEG))
        assertFalse(acceptsAutoFormat(MediaFormat.JPEG, AutoDownloadKind.RAW))
        assertFalse(acceptsAutoFormat(MediaFormat.OTHER, AutoDownloadKind.BOTH))
    }

    @Test fun onePortableCopyDoesNotMarkEitherOriginalAsImported() {
        val rawKey = cameraFileKey("Z6II:123", "card:1", "DSC_0001.NEF", 4000, 500)!!
        val jpegKey = cameraFileKey("Z6II:123", "card:1", "DSC_0001.JPG", 800, 500)!!
        val capture = cameraCaptureKey("Z6II:123", "DSC_0001.NEF", 500)
        assertEquals(capture, cameraCaptureKey("Z6II:123", "DSC_0001.JPG", 500))
        val raw = PhotoAsset("raw", "remote", "DSC_0001.NEF", MediaFormat.RAW, cameraFileKey = rawKey, cameraCaptureKey = capture)
        val jpeg = raw.copy(id = "jpeg", format = MediaFormat.JPEG, name = "DSC_0001.JPG", cameraFileKey = jpegKey, folder = "card:2/parent:4")
        assertEquals(jpegRepresentationKey(raw), jpegRepresentationKey(jpeg))
        assertNotEquals(rawKey, jpegRepresentationKey(raw))
        assertNotEquals(jpegKey, jpegRepresentationKey(jpeg))
    }
}
