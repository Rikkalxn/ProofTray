package org.prooftray.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAdvertisementTest {
    @Test fun sleepingPendingCameraNeedsWakeButQuickWakeCameraDoesNot() {
        val asleep = advertisementFlags(listOf(byteArrayOf(1, 2, 3, 4, 3)))
        assertTrue(advertisementHasTransfers(asleep))
        assertTrue(advertisementNeedsWake(asleep))
        val quickWake = advertisementFlags(listOf(byteArrayOf(1, 2, 3, 4, 11)))
        assertTrue(advertisementHasTransfers(quickWake))
        assertFalse(advertisementNeedsWake(quickWake))
    }

    @Test fun missingOrAmbiguousPayloadsNeverWakeAnUnidentifiedCamera() {
        assertNull(advertisementFlags(listOf(byteArrayOf(1, 2, 3))))
        assertNull(advertisementFlags(listOf(byteArrayOf(1, 2, 3, 4, 3), byteArrayOf(1, 2, 3, 4, 8))))
        assertFalse(advertisementHasTransfers(null))
        assertFalse(advertisementNeedsWake(null))
        assertFalse(advertisementHasTransfers(1))
    }
}
