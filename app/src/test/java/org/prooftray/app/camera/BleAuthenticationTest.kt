package org.prooftray.app.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BleAuthenticationTest {
    @Test fun readsTheStandardSeventeenByteRecord() {
        val record = ByteArray(17) { it.toByte() }.apply { this[0] = 2 }
        assertArrayEquals(record, authenticationRecord(record, 2))
    }

    @Test fun extraDataDoesNotChangeTheStandardFields() {
        val record = ByteArray(17) { it.toByte() }.apply { this[0] = 4 }
        assertArrayEquals(record, authenticationRecord(record + byteArrayOf(0, 0, 0), 4))
    }

    @Test fun finalRecordCarriesACameraIdWithoutRequiringANonceEcho() {
        val record = ByteArray(17).apply { this[0] = 4; "1234567".toByteArray().copyInto(this, 9) }
        val parsed = authenticationRecord(record, 4)
        assertArrayEquals(ByteArray(8), parsed.copyOfRange(1, 9))
        assertEquals("1234567", parsed.copyOfRange(9, 16).toString(Charsets.UTF_8))
    }

    @Test fun wrongStageIsRejectedInsteadOfBeingMarkedAuthenticated() {
        for (stage in listOf(0, 1, 2, 3, 5, 255)) {
            val record = ByteArray(17).apply { this[0] = stage.toByte() }
            assertThrows(IllegalStateException::class.java) { authenticationRecord(record, 4) }
        }
    }

    @Test fun truncatedAndOversizedRecordsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { authenticationRecord(ByteArray(16), 2) }
        assertThrows(IllegalArgumentException::class.java) { authenticationRecord(ByteArray(513), 2) }
    }
}
