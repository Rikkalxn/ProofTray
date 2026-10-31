package org.openbridge.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRadioPolicyTest {
    @Test fun wifiRequestPausesBluetoothBeforeTheNetworkConnects() {
        assertTrue(wifiHasPriority(requested = true, connecting = false, connected = false))
    }

    @Test fun openingThePtpSessionStillReservesWifi() {
        assertTrue(wifiHasPriority(requested = false, connecting = true, connected = false))
    }

    @Test fun connectedWifiPreventsABluetoothDataReconnect() {
        assertTrue(wifiHasPriority(requested = false, connecting = false, connected = true))
    }

    @Test fun bluetoothCanResumeAfterWifiAndItsReservationEnd() {
        assertFalse(wifiHasPriority(requested = false, connecting = false, connected = false))
    }

    @Test fun pairingHasExclusiveUseOfTheControlChannelUntilItCompletes() {
        assertFalse(bluetoothDataReady(authenticated = true, systemPaired = true, pairing = true))
        assertTrue(bluetoothDataReady(authenticated = true, systemPaired = true, pairing = false))
    }

    @Test fun aSystemBondAloneDoesNotAuthorizeAutomaticDataTransfer() {
        assertFalse(bluetoothDataReady(authenticated = false, systemPaired = true, pairing = false))
        assertFalse(bluetoothDataReady(authenticated = true, systemPaired = false, pairing = false))
    }
}
