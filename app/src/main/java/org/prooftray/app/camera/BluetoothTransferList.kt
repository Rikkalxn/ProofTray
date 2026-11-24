package org.prooftray.app.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Nikon LSS marked-file list: 48-byte header, bounded 8-byte entries, 8-byte footer. Handles are unsigned. */
internal data class BluetoothTransferSelection(val handles: List<Long>, val hasUnsupportedEntries: Boolean)

internal fun bluetoothTransferHandles(bytes: ByteArray): List<Long> = bluetoothTransferSelection(bytes).handles

internal fun bluetoothTransferSelection(bytes: ByteArray): BluetoothTransferSelection {
    require(bytes.size >= 56) { "相机待传列表不完整" }
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val count = input.getInt(40).toLong() and 0xffff_ffffL
    require(count <= 10000 && bytes.size.toLong() == 56 + count * 8) { "相机待传列表长度无效" }
    var unsupported = false
    val handles = buildList {
        repeat(count.toInt()) { index ->
            val offset = 48 + index * 8
            val type = bytes[offset + 7].toInt() and 255
            if (type in setOf(0, 1, 2, 255)) add(input.getInt(offset).toLong() and 0xffff_ffffL) else unsupported = true
        }
    }.distinct()
    return BluetoothTransferSelection(handles, unsupported)
}
