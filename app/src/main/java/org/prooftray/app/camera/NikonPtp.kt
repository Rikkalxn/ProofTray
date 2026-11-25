package org.prooftray.app.camera

/** SnapBridge also accepts SessionAlreadyOpen for OpenSession when reconnecting to the camera. */
internal fun nikonSessionAlreadyOpen(operation: Int, response: Int): Boolean = operation == 0x1002 && response == 0x201e

/** GetVendorCodes(Operation) returns an array of 32-bit values. Only the 16-bit entries are PTP opcodes. */
internal fun nikonOperations(base: Set<Int>, read: (Int, LongArray) -> ByteArray): Set<Int> {
    if (0x9439 !in base) return base
    val codes = NativePtp.handles(read(0x9439, longArrayOf(9)))
    return buildSet {
        addAll(base)
        for (code in codes) if (code in 0..0xffffL) add(code.toInt())
    }
}
