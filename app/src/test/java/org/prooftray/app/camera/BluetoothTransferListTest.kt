package org.prooftray.app.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothTransferListTest {
    @Test fun handlesAreUnsignedAndVideosAreExcluded() {
        val bytes = ByteArray(56 + 3 * 8)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(40, 3)
        buffer.putInt(48, -1)
        bytes[55] = 1
        buffer.putInt(56, 33)
        bytes[63] = 8
        buffer.putInt(64, -1)
        bytes[71] = 0
        assertEquals(listOf(0xffff_ffffL), bluetoothTransferHandles(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun declaredCountMustFitPayload() {
        val bytes = ByteArray(56)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 1)
        bluetoothTransferHandles(bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteHeadersAreRejected() { bluetoothTransferHandles(ByteArray(40)) }
}
