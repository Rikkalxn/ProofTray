package org.prooftray.app.domain

import org.junit.Assert.*
import org.junit.Test

class ImportHistoryTest {
    private val remote = PhotoAsset("raw", "camera:raw", "DSC_0001.NEF", MediaFormat.RAW,
        downloadState = DownloadState.REMOTE, sourceId = "camera", cameraFileKey = "original-key", cameraCaptureKey = "capture-key",
        inImportList = true, importedAtMillis = 100L, jpegCopyImportedAtMillis = 200L, previewUri = "cache:raw",
        rating = 4, pickState = PickState.PICKED, tags = setOf("保留"))

    @Test fun remoteCandidateForgetsBothVariantsButKeepsPhotoIdentityAndMarks() {
        val reset = resetImportHistory(listOf(remote), false).single()
        assertFalse(reset.importedAs(DownloadVariant.ORIGINAL))
        assertFalse(reset.importedAs(DownloadVariant.CAMERA_JPEG))
        assertEquals(remote.copy(importedAtMillis = null, jpegCopyImportedAtMillis = null), reset)
    }

    @Test fun downloadedOriginalAndPortableCopyRemainUntouched() {
        val original = remote.copy(uri = "content:raw", downloadState = DownloadState.DOWNLOADED)
        val portable = original.copy(id = "jpeg", uri = "content:jpeg", format = MediaFormat.JPEG, companionOfId = remote.id)
        val reset = resetImportHistory(listOf(original, portable), false)
        assertSame(original, reset[0])
        assertSame(portable, reset[1])
        assertTrue(reset[0].importedAs(DownloadVariant.ORIGINAL))
    }

    @Test fun orphanQueuedMarkerIsClearedOnceNoImportTasksAreActive() {
        val reset = resetImportHistory(listOf(remote.copy(downloadState = DownloadState.QUEUED)), false).single()
        assertEquals(DownloadState.REMOTE, reset.downloadState)
        assertFalse(reset.importedAs(DownloadVariant.ORIGINAL))
    }

    @Test(expected = IllegalStateException::class) fun activeImportRejectsTheEntireReset() {
        resetImportHistory(listOf(remote), true)
    }
}
