package org.openbridge.app.camera

/** Standard ATT errors returned by an attribute operation, not connection reasons or Android's generic GATT 133/257 errors. */
internal fun isNikonAttributeRejection(status: Int): Boolean = status in 0x01..0x13

// Identified camera prefixes for the mandatory first-pair clock initialization in the local protocol reference.
private val pairingClockModels = setOf("D850", "D500", "D7500", "D5600", "D3400", "A900", "A300", "B700", "B500",
    "W300", "W300s", "W100", "P1000", "P1100")

internal fun shouldSyncNikonPairingClock(serverName: String, requested: Boolean): Boolean =
    requested || cameraBluetoothName(serverName).substringBefore('_') in pairingClockModels
