package org.prooftray.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prooftray.app.ui.LibraryUiState
import org.prooftray.app.ui.cameraConnectionSummary

class CameraConnectionSummaryTest {
    @Test fun bondAloneDoesNotClaimAWorkingTransferLink() {
        val summary = cameraConnectionSummary(LibraryUiState(ble = BleState(systemPaired = true)))
        assertFalse(summary.bluetoothReady)
        assertFalse(summary.title.contains("就绪"))
    }

    @Test fun wifiSetupRemainsBusyUntilPtpSessionSucceeds() {
        val state = LibraryUiState(camera = CameraState(connecting = true, connectionPhase = ConnectionPhase.PTP,
            stage = "正在建立相机传图会话"), ble = BleState(authenticated = true, systemPaired = true))
        assertTrue(cameraConnectionSummary(state).busy)
        val connected = state.copy(camera = state.camera.copy(connecting = false, connected = true, connectionPhase = ConnectionPhase.READY))
        assertEquals("Wi-Fi 原片通道已就绪", cameraConnectionSummary(connected).title)
    }

    @Test fun bluetoothDataHandoffIsNotShownAsAConnectionFailure() {
        val state = LibraryUiState(ble = BleState(systemPaired = true), automatic = AutoDownloadState(transferring = true))
        assertEquals("蓝牙传图中", cameraConnectionSummary(state).title)
    }
}
