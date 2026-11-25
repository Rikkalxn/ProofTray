package org.prooftray.app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.callback.FailCallback

internal fun usesSonySystemPairing(sdk: Int, manufacturer: String): Boolean =
    sdk >= 37 || listOf("OPPO", "OnePlus", "realme").any { manufacturer.contains(it, ignoreCase = true) }

/** On the command-based path, let classic discovery start and finish before opening the pairing GATT link. */
@SuppressLint("MissingPermission")
internal suspend fun awaitSonyDiscoveryGrace(context: Context, adapter: BluetoothAdapter) {
    if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
    val events = Channel<String>(Channel.CONFLATED)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.action?.let { events.trySend(it) }
        }
    }
    val filter = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED).apply { addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    else context.registerReceiver(receiver, filter)
    var started = false
    try {
        currentCoroutineContext().ensureActive()
        val pending = if (adapter.isDiscovering) adapter.cancelDiscovery() else adapter.startDiscovery().also { started = it }
        if (pending) withTimeoutOrNull(3000) {
            while (events.receive() != BluetoothAdapter.ACTION_DISCOVERY_FINISHED) adapter.cancelDiscovery()
            started = false
        }
    } catch (error: SecurityException) {
        Log.i("ProofTray.Bluetooth", "Classic discovery unavailable before Sony pairing", error)
    } finally {
        context.unregisterReceiver(receiver)
        events.close()
        if (started) runCatching { adapter.isDiscovering && adapter.cancelDiscovery() }
    }
}

internal class SonyBluetoothFailure(message: String, val diagnostic: String, val gattStatus: Int? = null, cause: Exception? = null) :
    IOException(message, cause) {
    val attributeRejected: Boolean get() = gattStatus != null && (gattStatus in 0x01..0x13 || gattStatus == 0x9d ||
        gattStatus == FailCallback.REASON_NULL_ATTRIBUTE)
}

/** A new Android bond may become usable on a fresh GATT link. A camera rejection from EE03 always ends the attempt. */
internal fun canReconnectSonyPairing(status: Int?, confirmed: Boolean, newBond: Boolean): Boolean {
    if (!confirmed && !newBond) return false
    return when (status) {
        0x9d -> newBond
        FailCallback.REASON_DEVICE_DISCONNECTED, FailCallback.REASON_TIMEOUT, FailCallback.REASON_NULL_ATTRIBUTE, 0x01, 0x0a, 0x12, 0x85, 0x93 -> true
        else -> false
    }
}

internal fun sonyGattFailure(operation: String, status: Int, connecting: Boolean = false, cause: Exception? = null): SonyBluetoothFailure {
    val code = bluetoothGattCode(status)
    val reason = if (!connecting && status == 0x9d) "机身当前未接受此项请求" else bluetoothGattReason(status, connecting)
    return SonyBluetoothFailure("${operation}失败。$reason。", "$operation · $code", status, cause)
}
