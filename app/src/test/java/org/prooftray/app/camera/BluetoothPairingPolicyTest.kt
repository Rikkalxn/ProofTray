package org.prooftray.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException

class BluetoothPairingPolicyTest {
    @Test fun recognizesUnderscoreCameraNamesReturnedByClassicDiscovery() {
        assertTrue(isNikonCameraName("Z_6_2_1234567"))
        assertTrue(isNikonCameraName("Z 6_2_1234567"))
        assertTrue(isNikonCameraName("Zf_1234567"))
        assertTrue(isNikonCameraName("Nikon Z 8"))
    }

    @Test fun unrelatedBluetoothNamesAreNotCameraCandidates() {
        assertFalse(isNikonCameraName("Headphones"))
        assertFalse(isNikonCameraName(""))
    }

    @Test fun systemPairingMatchesTheVerifiedCameraNameNotJustTheModelPrefix() {
        val names = setOf("Z_6_2_1234567")
        assertTrue(matchesCameraName("Z_6_2_1234567", names))
        assertFalse(matchesCameraName("Z_6_2_7654321", names))
        assertFalse(matchesCameraName("Z_6_2", names))
        assertFalse(matchesCameraName(null, names))
    }

    @Test fun emptyExpectedNamesCannotSelectAnArbitraryCamera() {
        assertFalse(matchesCameraName("Z_6_2_1234567", emptySet()))
        assertFalse(matchesCameraName("", setOf("")))
    }

    @Test fun displayPlaceholdersCannotIdentifyACameraForAutomaticReconnect() {
        assertFalse(matchesCameraName("Nikon 相机", setOf("Nikon 相机")))
        assertFalse(matchesCameraName("已保存相机", setOf("已保存相机")))
    }

    @Test fun transientGattFailuresCanUseOneRediscoveryRetry() {
        assertTrue(retryableBleFailure(BleTransportException(19)))
        assertTrue(retryableBleFailure(BleTransportException(133)))
        assertTrue(retryableBleFailure(BleTransportException(8)))
    }

    @Test fun authenticationErrorsAndUserCancellationDoNotRetryAutomatically() {
        assertFalse(retryableBleFailure(BleTransportException(5)))
        assertFalse(retryableBleFailure(IllegalArgumentException("invalid camera proof")))
        assertFalse(retryableBleFailure(CancellationException("user stopped connection")))
    }

    @Test fun ordinaryReconnectDoesNotRewritePairingInformation() {
        assertFalse(initializeCameraPairing(explicitRequest = false, systemBondExists = true))
    }

    @Test fun explicitPairingCanInitializeAnAlreadyBondedCamera() {
        assertTrue(initializeCameraPairing(explicitRequest = true, systemBondExists = true))
    }

    @Test fun anUnbondedCameraMustBeInitializedBeforeSystemPairing() {
        assertTrue(initializeCameraPairing(explicitRequest = false, systemBondExists = false))
    }
}
