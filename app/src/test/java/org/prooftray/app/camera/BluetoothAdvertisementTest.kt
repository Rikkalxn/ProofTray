package org.prooftray.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAdvertisementTest {
    @Test fun sleepingPendingCameraNeedsWakeButQuickWakeCameraDoesNot() {
        val asleep = nikonAdvertisement(listOf(byteArrayOf(1, 2, 3, 4, 3)))?.flags
        assertTrue(advertisementHasTransfers(asleep))
        assertTrue(advertisementNeedsWake(asleep))
        val quickWake = nikonAdvertisement(listOf(byteArrayOf(1, 2, 3, 4, 11)))?.flags
        assertTrue(advertisementHasTransfers(quickWake))
        assertFalse(advertisementNeedsWake(quickWake))
    }

    @Test fun missingOrAmbiguousPayloadsNeverWakeAnUnidentifiedCamera() {
        assertNull(nikonAdvertisement(listOf(byteArrayOf(1, 2, 3))))
        assertNull(nikonAdvertisement(listOf(byteArrayOf(1, 2, 3, 4, 3), byteArrayOf(1, 2, 3, 4, 8))))
        assertNull(nikonAdvertisement(listOf(byteArrayOf(1, 2, 3, 4, 3), byteArrayOf(1, 2, 3, 5, 3))))
        assertFalse(advertisementHasTransfers(null))
        assertFalse(advertisementNeedsWake(null))
        assertFalse(advertisementHasTransfers(1))
    }

    @Test fun advertisingClientIsThePrefixOfTheRegisteredPhoneIdentity() {
        val identity = byteArrayOf(1, -1, 2, 3, 4, 5, 6, 7)
        val advertisement = nikonAdvertisement(listOf(byteArrayOf(1, -1, 2, 3, 2)))
        assertEquals(nikonClientId(identity), requireNotNull(advertisement).clientId)
    }
}
