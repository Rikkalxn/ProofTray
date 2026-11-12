package org.openbridge.app.camera

import org.junit.Assert.*
import org.junit.Test
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset

class ImportHistoryPolicyTest {
    private val photo = RemotePhoto(PhotoAsset("id", "camera:id", "test.NEF", MediaFormat.RAW), 1, 1, "camera", "")

    @Test fun queuedAndRunningImportsBlockClearingEvenBeforeAnOutputExists() {
        for (status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)) {
            assertNotNull(importHistoryBlockReason(listOf(CameraDownload("task", photo, status)), AutoDownloadState()))
        }
    }

    @Test fun bluetoothCheckingAndTransferringAlsoBlockClearing() {
        assertNotNull(importHistoryBlockReason(emptyList(), AutoDownloadState(checking = true)))
        assertNotNull(importHistoryBlockReason(emptyList(), AutoDownloadState(transferring = true)))
    }

    @Test fun idleAndFinishedOrWaitingTasksDoNotBlockClearing() {
        val tasks = listOf(DownloadStatus.COMPLETED, DownloadStatus.CANCELLED, DownloadStatus.WAITING).map {
            CameraDownload(it.name, photo, it)
        }
        assertNull(importHistoryBlockReason(tasks, AutoDownloadState(watching = true)))
    }
}
