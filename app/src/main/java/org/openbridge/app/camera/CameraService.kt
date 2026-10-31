package org.openbridge.app.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.openbridge.app.MainActivity
import org.openbridge.app.R

class CameraService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("camera", "相机连接与传输", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenBridge:camera-transfer")
            .apply { setReferenceCounted(false) }
        startForeground(31, notification("相机连接准备中"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        scope.launch {
            var lastText = ""
            val controller = CameraController.get(this@CameraService)
            combine(controller.state, controller.bleState, controller.autoState) { camera, ble, auto -> Triple(camera, ble, auto) }
                .collectLatest { (state, ble, auto) ->
                    val running = state.tasks.firstOrNull { it.status == DownloadStatus.RUNNING }
                    val needsWake = state.busy || (running != null && !state.transmissionPaused)
                    val lock = wakeLock
                    if (needsWake && lock?.isHeld == false) lock.acquire(10 * 60 * 1000L)
                    else if (!needsWake && lock?.isHeld == true) lock.release()
                    val text = running?.let {
                        val percent = if (it.expectedBytes > 0) {
                            "${(it.received.toDouble() / it.expectedBytes * 100).toInt().coerceIn(0, 99)}%"
                        } else "接收中"
                        "${it.photo.asset.name} · $percent · ${it.message}"
                    } ?: when {
                        auto.watching || auto.checking -> auto.message
                        state.connected || state.busy -> state.stage
                        ble.authenticated || ble.scanning -> ble.message
                        else -> state.stage
                    }
                    if (text != lastText) { notifications.notify(31, notification(text)); lastText = text }
                }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        CameraController.get(this).disconnect()
        super.onDestroy()
    }
    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return Notification.Builder(this, "camera").setSmallIcon(R.drawable.ic_notification).setContentTitle("OpenBridge")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    companion object {
        fun start(context: Context) { context.startForegroundService(Intent(context, CameraService::class.java)) }
    }
}
