package org.openbridge.app.camera

internal fun wifiHasPriority(requested: Boolean, connecting: Boolean, connected: Boolean): Boolean = requested || connecting || connected

internal fun bluetoothDataReady(authenticated: Boolean, systemPaired: Boolean, pairing: Boolean): Boolean =
    authenticated && systemPaired && !pairing
