package org.openbridge.app.camera

/** Nikon's client-name field is 32 bytes. Use a printable model name, as the reference clients do. */
internal fun bluetoothClientName(deviceName: String): ByteArray {
    val clean = deviceName.trim().map { if (it in '!'..'~') it else '_' }.joinToString("").trim('_').ifBlank { "OpenBridge" }
    val text = "Android_$clean".take(31)
    val result = ByteArray(32)
    text.toByteArray(Charsets.US_ASCII).copyInto(result)
    return result
}
