package org.prooftray.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.prooftray.app.domain.DownloadState
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.PickState
import org.prooftray.app.domain.jpegRepresentationKey

class CameraCompanionMigrationTest {
    private val raw = PhotoAsset("raw", "camera:raw", "DSC_0001.NEF", MediaFormat.RAW,
        sourceId = "camera:nikon", cameraFileKey = "raw-file", cameraCaptureKey = "capture", downloadState = DownloadState.REMOTE)
    private val copyKey = jpegRepresentationKey(raw)
    private val legacy = raw.copy(id = copyKey, uri = "content:portable", name = "DSC_0001_ProofTray_2M.jpg", format = MediaFormat.JPEG,
        cameraFileKey = copyKey, downloadState = DownloadState.DOWNLOADED, derivedFromId = raw.id,
        rating = 5, pickState = PickState.PICKED, tags = setOf("保留"), stackId = "stack", importedAtMillis = 123L,
        hiddenFromLibrary = true, sourceOrientation = 6)

    @Test fun legacyPortableLinksMoveWithoutChangingFilesOrUserMetadata() {
        val migrated = migrateCameraCompanions(listOf(raw, legacy))
        assertSame(raw, migrated[0])
        assertEquals(legacy.copy(derivedFromId = null, companionOfId = raw.id), migrated[1])
        assertEquals(migrated, migrateCameraCompanions(migrated))
    }

    @Test fun promotedCopiesRecoverTheRawLinkWithoutUsingThePairedJpegAsTheOriginal() {
        val promoted = legacy.copy(derivedFromId = null)
        val jpeg = raw.copy(id = "camera-jpeg", name = "DSC_0001.JPG", format = MediaFormat.JPEG, cameraFileKey = "jpeg-file")
        val migrated = migrateCameraCompanions(listOf(jpeg, promoted, raw))
        assertEquals(raw.id, migrated[1].companionOfId)
        assertNull(migrated[1].derivedFromId)
        assertSame(promoted, migrateCameraCompanions(listOf(jpeg, promoted))[1])
    }

    @Test fun aMissingSourceDoesNotEraseTheKnownLegacyLink() {
        val orphan = legacy.copy(cameraCaptureKey = null)
        assertEquals(orphan.copy(derivedFromId = null, companionOfId = raw.id), migrateCameraCompanions(listOf(orphan)).single())
    }

    @Test fun similarlyNamedExportsAndLocalFilesAreNotReclassified() {
        val exported = legacy.copy(id = "export", cameraFileKey = null, sourceId = "document:media")
        val local = exported.copy(id = "local", derivedFromId = null)
        val originalJpeg = raw.copy(id = "camera-jpeg", cameraFileKey = "camera-jpeg", format = MediaFormat.JPEG, name = legacy.name)
        val assets = listOf(raw, exported, local, originalJpeg)
        assertEquals(assets, migrateCameraCompanions(assets))
    }

    @Test fun codecPreservesNewLinksAndRecognizesOldPortableSnapshots() {
        val companion = legacy.copy(derivedFromId = null, companionOfId = raw.id)
        assertEquals(companion, RecordCodec.decodeAsset(RecordCodec.encodeAsset(companion)))
        val old = RecordCodec.encodeAsset(legacy).apply { remove("companionOfId") }
        assertEquals(companion, RecordCodec.decodeAsset(old))
        val exported = legacy.copy(id = "export", cameraFileKey = null, sourceId = "document:media")
        val oldExport = RecordCodec.encodeAsset(exported).apply { remove("companionOfId") }
        assertEquals(exported, RecordCodec.decodeAsset(oldExport))
    }
}
