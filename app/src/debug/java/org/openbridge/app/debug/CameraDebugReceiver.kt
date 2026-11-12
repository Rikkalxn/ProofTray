package org.openbridge.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import org.openbridge.app.camera.CameraController
import org.openbridge.app.camera.CameraConnection

/** Debug APK only. The manifest requires the shell's DUMP permission; no pairing-confirmation or permission-grant commands exist. */
class CameraDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val camera = CameraController.get(context.applicationContext)
            when (intent.getStringExtra("command") ?: "status") {
                "status" -> Unit
                "scan" -> camera.scanBle()
                "connect" -> camera.connectSavedControl()
                "pair" -> camera.discoverClassic()
                "wifi" -> camera.wakeWifi()
                "ap" -> camera.connect(CameraConnection())
                "disconnect" -> camera.disconnectControl()
                else -> error("Unknown debug camera command")
            }
            val ble = camera.bleState.value
            resultCode = 0
            resultData = JSONObject().apply {
                @Suppress("DEPRECATION") val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                put("version", version)
                put("authenticated", ble.authenticated); put("systemPaired", ble.systemPaired)
                put("pairing", ble.pairingInProgress); put("scanning", ble.scanning)
                put("candidateCount", ble.cameras.size); put("message", ble.message); put("history", JSONArray(ble.history))
                put("clientName", ble.clientName); put("wifiRequested", ble.wifiRequested)
                put("wifiConnected", camera.state.value.connected); put("wifiConnecting", camera.state.value.connecting)
                put("wifiPhase", camera.state.value.connectionPhase.name); put("bluetoothEnabled", ble.adapterEnabled)
                put("wifiMessage", camera.state.value.message); put("automatic", camera.autoState.value.message)
                put("checking", camera.autoState.value.checking); put("checkResult", camera.autoState.value.lastCheckResult)
            }.toString()
        } catch (error: Exception) {
            resultCode = 1
            resultData = JSONObject().put("error", error.message ?: error.javaClass.simpleName).toString()
        }
    }
}
