package org.openbridge.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openbridge.app.camera.AutoDownloadState
import org.openbridge.app.camera.BleState
import org.openbridge.app.camera.CameraMake
import org.openbridge.app.camera.ConnectionPhase
import org.openbridge.app.domain.AppSettings

class CameraPrimaryActionTest {
    private val empty = LibraryUiState(ble = BleState(adapterEnabled = true))
    private val nikon = empty.copy(settings = AppSettings(controlAddress = "nikon"), camera = empty.camera.copy(make = CameraMake.NIKON))
    private val sony = empty.copy(settings = AppSettings(sonySelected = true, sonyAddress = "sony"),
        camera = empty.camera.copy(make = CameraMake.SONY))

    @Test fun newCameraAndDisabledBluetoothOfferUsableActions() {
        assertEquals(CameraAction.ADD, cameraPrimaryAction(empty).action)
        for (state in listOf(empty, nikon, sony)) {
            assertEquals(CameraAction.BLUETOOTH_SETTINGS, cameraPrimaryAction(state.copy(ble = state.ble.copy(adapterEnabled = false))).action)
        }
    }

    @Test fun connectedWifiCanBrowseEvenWhenPhoneBluetoothIsOff() {
        for (state in listOf(nikon, sony)) {
            val connected = state.copy(camera = state.camera.copy(connected = true, connectionPhase = ConnectionPhase.READY),
                ble = state.ble.copy(adapterEnabled = false))
            assertEquals(CameraAction.BROWSE, cameraPrimaryAction(connected).action)
        }
    }

    @Test fun setupCanBeCancelledButDisconnectCannotBeRestarted() {
        val connecting = nikon.copy(camera = nikon.camera.copy(connecting = true, connectionPhase = ConnectionPhase.PTP))
        assertEquals(CameraAction.CANCEL, cameraPrimaryAction(connecting).action)
        val disconnecting = connecting.copy(camera = connecting.camera.copy(connectionPhase = ConnectionPhase.DISCONNECTING))
        assertEquals(CameraAction.NONE, cameraPrimaryAction(disconnecting).action)
        assertEquals(CameraAction.CANCEL, cameraPrimaryAction(nikon.copy(automatic = AutoDownloadState(checking = true))).action)
    }

    @Test fun nikonKeepsWifiWakeWhileSonyRetainsRetryForFailedConnections() {
        assertEquals("切换到 Wi-Fi", cameraPrimaryAction(nikon).label)
        val failedNikon = nikon.copy(camera = nikon.camera.copy(connectionPhase = ConnectionPhase.FAILED))
        assertEquals(CameraAction.WAKE_WIFI, cameraPrimaryAction(failedNikon).action)
        assertEquals("连接相机 Wi-Fi", cameraPrimaryAction(sony).label)
        assertEquals(CameraAction.RETRY, cameraPrimaryAction(sony.copy(camera = sony.camera.copy(connectionPhase = ConnectionPhase.FAILED))).action)
    }

    @Test fun manualWifiAndSavedCameraHaveDifferentReconnectRoutes() {
        val manual = empty.copy(camera = empty.camera.copy(manuallyDisconnected = true))
        assertEquals(CameraAction.MANUAL, cameraPrimaryAction(manual).action)
        for (state in listOf(nikon, sony)) {
            val disconnected = state.copy(camera = state.camera.copy(manuallyDisconnected = true))
            assertEquals(CameraAction.WAKE_WIFI, cameraPrimaryAction(disconnected).action)
        }
    }
}
