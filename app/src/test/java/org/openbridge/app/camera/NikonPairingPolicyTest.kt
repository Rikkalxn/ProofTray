package org.openbridge.app.camera

import no.nordicsemi.android.ble.callback.FailCallback
import org.junit.Assert.*
import org.junit.Test
import org.openbridge.app.domain.AppSettings

class NikonPairingPolicyTest {
    @Test fun onlyAttributeRejectionsMayBeIgnoredForOptionalPairingWrites() {
        for (status in listOf(3, 5, 8, 13, 19)) assertTrue(isNikonAttributeRejection(status))
        for (status in listOf(0, 133, 257, FailCallback.REASON_TIMEOUT, FailCallback.REASON_DEVICE_DISCONNECTED)) {
            assertFalse(isNikonAttributeRejection(status))
        }
    }

    @Test fun clockIsNotWrittenMerelyBecauseAModernCameraExposesIt() {
        assertFalse(AppSettings().pairingTimeSync)
        for (name in listOf("Z_6_2_1234567", "Z 6_2_1234567", "Z6II", "Z9_123", "unknown")) {
            assertFalse(shouldSyncNikonPairingClock(name, false))
            assertTrue(shouldSyncNikonPairingClock(name, true))
        }
    }

    @Test fun identifiedLegacyPairingInitializationsRemainEnabled() {
        for (model in listOf("D850", "D500", "D7500", "D5600", "D3400", "A900", "A300", "B700", "B500",
            "W300", "W300s", "W100", "P1000", "P1100")) {
            assertTrue(shouldSyncNikonPairingClock(model + "_1234", false))
        }
    }
}
