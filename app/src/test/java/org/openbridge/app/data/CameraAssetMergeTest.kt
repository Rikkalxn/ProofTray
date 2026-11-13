package org.openbridge.app.data

import org.junit.Assert.*
import org.junit.Test
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PickState

class CameraAssetMergeTest {
    private val remote = PhotoAsset("photo", "camera:photo", "DSC_0001.NEF", MediaFormat.RAW, width = 6000, height = 4000,
        downloadState = DownloadState.REMOTE, sourceId = "camera", cameraFileKey = "original", cameraCaptureKey = "capture", inImportList = true)
    private val marked = remote.copy(rating = 5, pickState = PickState.PICKED, tags = setOf("保留"), stackId = "stack",
        sourceOrientation = 6, width = 4000, height = 6000, previewUri = "cache:old", previewRevision = "old")

    @Test fun aNewCameraPhotoKeepsItsIncomingIdentityAndMetadata() {
        assertSame(remote, mergeCameraAsset(null, remote, false))
        assertSame(remote, mergeCameraAsset(null, remote, true))
    }

    @Test fun cameraRefreshCannotDowngradeALocalFileOrItsUserMetadata() {
        val local = marked.copy(uri = "content:original", downloadState = DownloadState.DOWNLOADED, importedAtMillis = 100L,
            jpegCopyImportedAtMillis = 200L, derivedFromId = "parent", hiddenFromLibrary = true, inImportList = false)
        for (state in listOf(DownloadState.REMOTE, DownloadState.QUEUED)) {
            val incoming = remote.copy(downloadState = state, previewUri = "cache:new", previewRevision = "new", sourceOrientation = 8)
            val merged = mergeCameraAsset(local, incoming, true)
            assertEquals(local.copy(inImportList = true, previewUri = "cache:new", previewRevision = "new"), merged)
        }
    }

    @Test fun refreshingALocalFileCanFillAMissingOrientationWithoutReplacingDimensions() {
        val local = marked.copy(uri = "content:original", downloadState = DownloadState.DOWNLOADED, sourceOrientation = null)
        val merged = mergeCameraAsset(local, remote.copy(sourceOrientation = 8), false)
        assertEquals(local.copy(sourceOrientation = 8), merged)
    }

    @Test fun queuedMarkersSurviveBrowsingUnlessExplicitlyReset() {
        val queued = marked.copy(downloadState = DownloadState.QUEUED, hiddenFromLibrary = true)
        for (reset in listOf(false, true)) {
            val merged = mergeCameraAsset(queued, remote, reset)
            assertEquals(if (reset) DownloadState.REMOTE else DownloadState.QUEUED, merged.downloadState)
            assertEquals(marked.rating, merged.rating)
            assertEquals(marked.pickState, merged.pickState)
            assertEquals(marked.tags, merged.tags)
            assertEquals(marked.stackId, merged.stackId)
            assertTrue(merged.hiddenFromLibrary)
        }
    }

    @Test fun aFinishedDownloadUsesTheLocalFileAndKeepsMarksWhileBecomingVisible() {
        val previous = marked.copy(downloadState = DownloadState.QUEUED, hiddenFromLibrary = true)
        val downloaded = remote.copy(uri = "content:original", downloadState = DownloadState.DOWNLOADED, importedAtMillis = 300L,
            width = 4500, height = 6750, sourceOrientation = 8, camera = "Nikon", metadataComplete = true)
        val merged = mergeCameraAsset(previous, downloaded, false)
        assertEquals(downloaded.copy(rating = 5, pickState = PickState.PICKED, tags = setOf("保留"), stackId = "stack",
            previewUri = "cache:old", previewRevision = "old"), merged)
    }

    @Test fun aRefreshWithoutOrientationKeepsResolvedGeometryAndFillsMissingDimensions() {
        val merged = mergeCameraAsset(marked, remote, false)
        assertEquals(6, merged.sourceOrientation)
        assertEquals(4000, merged.width)
        assertEquals(6000, merged.height)
        val partial = mergeCameraAsset(marked.copy(width = null), remote, false)
        assertEquals(6000, partial.width)
        assertEquals(6000, partial.height)
    }

    @Test fun freshOrientationReplacesStaleGeometry() {
        val merged = mergeCameraAsset(marked, remote.copy(sourceOrientation = 1), false)
        assertEquals(1, merged.sourceOrientation)
        assertEquals(6000, merged.width)
        assertEquals(4000, merged.height)
    }

    @Test fun reimportingALocalExportRetainsItsParentButNewDownloadsUseTheIncomingParent() {
        val downloaded = remote.copy(uri = "content:new", downloadState = DownloadState.DOWNLOADED, derivedFromId = "incoming-parent")
        val localExport = marked.copy(uri = "content:old", downloadState = DownloadState.DOWNLOADED, derivedFromId = "existing-parent")
        assertEquals("existing-parent", mergeCameraAsset(localExport, downloaded, false).derivedFromId)
        assertEquals("incoming-parent", mergeCameraAsset(marked, downloaded, false).derivedFromId)
    }

    @Test fun cameraCompanionLinksSurviveRefreshAndCanBeFilledDuringReimport() {
        val companion = marked.copy(id = "portable", cameraFileKey = "portable-key", format = MediaFormat.JPEG,
            downloadState = DownloadState.DOWNLOADED, companionOfId = remote.id)
        val refreshed = companion.copy(companionOfId = null, rating = 0, tags = emptySet())
        assertEquals(companion, mergeCameraAsset(companion, refreshed, false))
        assertEquals(companion, mergeCameraAsset(companion.copy(companionOfId = null), companion, false))
        val queued = companion.copy(downloadState = DownloadState.QUEUED, companionOfId = null)
        assertEquals(remote.id, mergeCameraAsset(queued, companion, false).companionOfId)
    }

    @Test fun absentPreviewFieldsDoNotEraseTheCachedPreview() {
        val unchanged = mergeCameraAsset(marked, remote, false)
        assertEquals("cache:old", unchanged.previewUri)
        assertEquals("old", unchanged.previewRevision)
        val refreshed = mergeCameraAsset(marked, remote.copy(previewUri = "cache:new", previewRevision = "new"), false)
        assertEquals("cache:new", refreshed.previewUri)
        assertEquals("new", refreshed.previewRevision)
    }
}
