package org.openbridge.app.camera

// Android removes the company ID; an LSS payload contains four client-ID bytes and one advertisement flag byte.
internal fun advertisementFlags(payloads: List<ByteArray>): Int? {
    val candidates = payloads.filter { it.size == 5 }.map { it[4].toInt() and 255 }.distinct()
    return candidates.singleOrNull()
}

internal fun advertisementHasTransfers(flags: Int?): Boolean = flags != null && flags and 2 != 0

internal fun advertisementNeedsWake(flags: Int?): Boolean = flags != null && flags and 1 != 0 && flags and 8 == 0
