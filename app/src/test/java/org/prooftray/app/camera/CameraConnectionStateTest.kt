package org.prooftray.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.PhotoAsset

class CameraConnectionStateTest {
    @Test fun catalogBusyDoesNotDisableReconnect() {
        assertTrue(CameraState(connected = false, busy = true).canConnect)
        assertTrue(CameraState(connected = true, busy = true).canConnect)
    }

    @Test fun activeConnectionAttemptDisablesDuplicateConnect() {
        assertFalse(CameraState(connecting = true, busy = true).canConnect)
    }

    @Test fun cancellingConnectionReleasesAllWorkFlags() {
        val state = CameraState(connecting = true, busy = true, generation = 2, transmissionPaused = true)
        val disconnected = state.disconnected(3)
        assertTrue(disconnected.canConnect)
        assertFalse(disconnected.connected)
        assertFalse(disconnected.busy)
        assertFalse(disconnected.transmissionPaused)
        assertEquals(3, disconnected.generation)
    }

    @Test fun lateCatalogCallbackCannotMakeDisconnectedScreenBusyAgain() {
        val disconnected = CameraState(connected = true, busy = true, generation = 2).disconnected(3)
        val oldProgress = BrowseProgress(2, 20, 100, true, true, setOf("old"))
        assertSame(disconnected, disconnected.withCatalogProgress(oldProgress))
    }

    @Test fun oldCatalogCompletionCannotChangeANewConnectionAttempt() {
        val connecting = CameraState(connecting = true, busy = true, generation = 3, stage = "选择 Wi-Fi")
        assertSame(connecting, connecting.withCatalogProgress(BrowseProgress(2, 50, 50, false, false, emptySet())))
        val connected = connecting.copy(connecting = false, connected = true)
        assertSame(connected, connected.withCatalogProgress(BrowseProgress(2, 50, 50, false, false, emptySet())))
    }

    @Test fun currentCatalogProgressStillUpdatesTheConnectedScreen() {
        val state = CameraState(connected = true, generation = 3)
        val reading = state.withCatalogProgress(BrowseProgress(3, 8, 100, true, true, setOf("photo")))
        assertTrue(reading.busy)
        assertEquals(8, reading.indexed)
        assertEquals(setOf("photo"), reading.catalogIds)
        assertFalse(reading.withCatalogProgress(BrowseProgress(3, 50, 100, false, true, setOf("photo"))).busy)
    }

    @Test fun lostConnectionPreservesTransferCheckpointAndPauseChoice() {
        val asset = PhotoAsset("photo", "content://media/external/file/7", "test.nef", MediaFormat.RAW)
        val photo = RemotePhoto(asset, 42, 1, "test-camera", "")
        val task = CameraDownload("task", photo, received = 262144, outputUri = "content://media/external/file/8")
        val state = CameraState(connected = true, busy = true, tasks = listOf(task), paused = true, generation = 2)
        val disconnected = state.disconnected(3, "网络中断")
        assertEquals(state.tasks, disconnected.tasks)
        assertTrue(disconnected.paused)
        assertTrue(disconnected.canConnect)
        assertFalse(disconnected.busy)
        assertEquals("网络中断", disconnected.message)
    }
}
