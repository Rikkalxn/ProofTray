package org.openbridge.app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openbridge.app.MainActivity
import org.openbridge.app.R
import org.openbridge.app.data.AppSettingsRepository

data class LocationSyncState(
    val running: Boolean = false, val message: String = "位置同步未开启", val accuracyMetres: Float? = null, val lastSentMillis: Long = 0
)

/** Starts only from a visible user action. Position acquisition is intermittent and stops while no usable camera channel exists. */
class CameraLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings by lazy { AppSettingsRepository.get(this) }
    private val controller by lazy { CameraController.get(this) }
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private var startupFailure: String? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var sampleRequested = true

    override fun onCreate() {
        super.onCreate()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            startupFailure = "需要精确位置授权后才能启动"
            mutableState.value = LocationSyncState(message = startupFailure!!)
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("location", "相机位置同步", NotificationManager.IMPORTANCE_LOW))
        try {
            startForeground(32, notification("位置同步已启用"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (error: Exception) {
            startupFailure = "系统拒绝启动位置服务：${error.message}"
            mutableState.value = LocationSyncState(message = startupFailure!!)
            stopSelf()
            return
        }
        scope.launch { controller.bleState.map { it.authenticated to it.locationRequested }.distinctUntilChanged().collect { wake.trySend(Unit) } }
        scope.launch { controller.state.map { it.connected }.distinctUntilChanged().collect { wake.trySend(Unit) } }
        scope.launch {
            settings.state.map { (it.locationSync && !it.sonySelected) to it.powerProfile }.distinctUntilChanged().collect { wake.trySend(Unit) }
        }
        scope.launch {
            var positionSent = false
            var failures = 0
            while (settings.state.value.locationSync && !settings.state.value.sonySelected) {
                if (!controller.canSyncLocation()) {
                    report("等待支持位置同步的相机连接")
                    wake.receive()
                    continue
                }
                if (!sampleRequested && !controller.locationRequested()) {
                    if (positionSent) {
                        runCatching { withContext(Dispatchers.IO) { controller.clearLocation() } }
                        positionSent = false
                    }
                    report("等待相机位置请求，GPS 处于待机")
                    wake.receive()
                    continue
                }
                if (sampleRequested) failures = 0
                sampleRequested = false
                try {
                    report("获取当前位置")
                    val fix = freshLocation()
                    if (fix == null) {
                        failures = (failures + 1).coerceAtMost(3)
                        withContext(Dispatchers.IO) { controller.clearLocation() }
                        positionSent = false
                        report("未取得新鲜且准确的位置；已停止发送旧坐标")
                    } else {
                        val route = withContext(Dispatchers.IO) {
                            val altitude = fix.altitude.takeIf { fix.hasAltitude() && it.isFinite() && kotlin.math.abs(it) <= 32767 } ?: Double.NaN
                            val packet = NativePtp.location(fix.latitude, fix.longitude, altitude, fix.time, 0, true)
                            controller.syncLocation(packet)
                        }
                        val message = "已通过 $route 同步位置，精度约 ${fix.accuracy.toInt()} 米"
                        failures = 0
                        mutableState.value = LocationSyncState(true, message, fix.accuracy, System.currentTimeMillis())
                        positionSent = true
                        manager.notify(32, notification(message))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    failures = (failures + 1).coerceAtMost(3)
                    report("位置同步等待重试：${error.message}")
                }
                val interval = (settings.state.value.powerProfile.locationSeconds.toLong() * (1 shl failures)).coerceAtMost(900)
                withTimeoutOrNull(interval * 1000L) { wake.receive() }
            }
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun freshLocation(): Location? {
        check(locations.isLocationEnabled) { "手机定位开关未开启" }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { locations.isProviderEnabled(it) }
        val cached = providers.mapNotNull { locations.getLastKnownLocation(it) }.filter(::usable).minByOrNull { it.accuracy }
        if (cached != null) return cached
        val result = CompletableDeferred<Location>()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { if (usable(location)) result.complete(Location(location)) }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            providers.forEach { locations.requestLocationUpdates(it, 10000L, 10f, listener, Looper.getMainLooper()) }
            return withTimeoutOrNull(30000) { result.await() }
        } finally { locations.removeUpdates(listener) }
    }

    private fun usable(location: Location): Boolean {
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return location.elapsedRealtimeNanos > 0 && age in 0..60_000_000_000L && location.hasAccuracy() &&
            location.accuracy.isFinite() && location.accuracy in 0f..100f &&
            location.latitude.isFinite() && location.latitude in -90.0..90.0 && location.longitude.isFinite() &&
            location.longitude in -180.0..180.0 && location.time > 0
    }

    private fun report(message: String) {
        mutableState.value = mutableState.value.copy(running = true, message = message)
        getSystemService(NotificationManager::class.java).notify(32, notification(message))
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "location").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${getString(R.string.app_name)} 位置同步")
            .setContentText(message).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sampleRequested = true
        wake.trySend(Unit)
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { controller.clearLocation() } }
        mutableState.value = mutableState.value.copy(running = false, message = startupFailure ?: "位置同步已停止")
        super.onDestroy()
    }

    companion object {
        private val mutableState = MutableStateFlow(LocationSyncState())
        val state = mutableState.asStateFlow()
        fun startFromForeground(context: Context) { context.startForegroundService(Intent(context, CameraLocationService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, CameraLocationService::class.java)) }
    }
}
