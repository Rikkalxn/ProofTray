package org.prooftray.app.camera

internal object NativePtp {
    init { System.loadLibrary("prooftray") }
    external fun initialize(guid: ByteArray, name: String): ByteArray
    external fun command(operation: Int, transaction: Long, parameters: LongArray, receiveData: Boolean, sendData: Boolean = false): ByteArray
    external fun info(data: ByteArray, device: Boolean): String
    external fun handles(data: ByteArray): LongArray
    external fun pairReply(start: ByteArray, challenge: ByteArray): ByteArray
    external fun pairingKey(start: ByteArray, challenge: ByteArray, confirmation: ByteArray): ByteArray
    external fun decryptConnectionField(key: ByteArray, ciphertext: ByteArray): ByteArray
    external fun currentTime(utcMillis: Long, standardOffsetMinutes: Int, daylightMinutes: Int): ByteArray
    external fun location(latitude: Double, longitude: Double, altitude: Double, utcMillis: Long, satellites: Int, enabled: Boolean): ByteArray
}
