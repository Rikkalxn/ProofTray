package org.prooftray.app.camera

internal data class NikonControlPoint(val time: Int, val location: Int, val connection: Int) {
    val timeRequested: Boolean get() = time == 1
    val locationRequested: Boolean get() = location in 1..2
    val connectionRequested: Boolean get() = connection == 1

    fun encode(): ByteArray = byteArrayOf(((time and 15) or ((location and 15) shl 4)).toByte(), (connection and 15).toByte())

    companion object {
        fun decode(bytes: ByteArray): NikonControlPoint? {
            if (bytes.size < 2) return null
            return NikonControlPoint(bytes[0].toInt() and 15, (bytes[0].toInt() ushr 4) and 15, bytes[1].toInt() and 15)
        }
    }
}

/** Runs inside the owning session's operation; recheck ownership after every suspension before another request. */
internal suspend fun syncNikonRequestedTime(currentTime: () -> ByteArray, read: suspend (String) -> ByteArray,
    write: suspend (String, ByteArray) -> Unit, allowed: () -> Boolean): Boolean {
    if (!allowed()) return false
    write("2006", currentTime())
    if (!allowed()) return false
    val control = checkNotNull(NikonControlPoint.decode(read("2008"))) { "相机返回的控制请求不完整" }
    if (!allowed()) return false
    if (control.timeRequested) write("2008", control.copy(time = 0).encode())
    return allowed()
}
