package org.prooftray.app.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.android.ble.TimeoutableRequest
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.exception.BluetoothDisabledException
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.RequestFailedException
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend

@SuppressLint("MissingPermission")
internal class SonyControl(private val context: Context, private val report: (Boolean) -> Unit) {
    private var current: SonyBleSession? = null
    private val connections = Mutex()
    private val locationRevision = MutableStateFlow(0)
    val locationChanges = locationRevision.asStateFlow()

    suspend fun pair(address: String) {
        val (device, session) = session(address)
        session.pair(device)
    }

    suspend fun connection(address: String, manual: CameraConnection? = null): CameraConnection {
        val (device, session) = session(address)
        return if (manual == null) session.hotspot(device)
            else manual.copy(sonyAccess = session.access(device), sonyCamera = true)
    }

    suspend fun networkId(address: String): UUID? {
        val (device, session) = session(address)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "索尼系统配对已移除，请重新添加相机" }
        return session.networkId()
    }

    suspend fun location(address: String): SonyBleSession {
        val (device, session) = session(address)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "请先在手机和机身确认索尼配对" }
        check(session.supportsLocation) { "此机身未提供蓝牙位置关联服务" }
        return session
    }

    @Synchronized fun isCurrent(session: SonyBleSession): Boolean = current === session && session.live

    private suspend fun session(address: String): Pair<BluetoothDevice, SonyBleSession> = connections.withLock {
        val device = requireNotNull(context.getSystemService(BluetoothManager::class.java).adapter) { "手机不支持蓝牙" }.getRemoteDevice(address)
        val caller = currentCoroutineContext()
        val (previous, session) = synchronized(this) {
            caller.ensureActive()
            current?.takeIf { it.live && it.bluetoothDevice?.address == address }?.let { return@withLock device to it }
            val previous = current
            val next = SonyBleSession(context, ::controlState) { locationRevision.update { it + 1 } }
            current = next
            report(false)
            previous to next
        }
        previous?.close()
        try {
            caller.ensureActive()
            session.open(device)
            synchronized(this) { check(current === session) { "索尼蓝牙连接已更换" } }
        } catch (failure: Exception) {
            synchronized(this) { if (current === session) { current = null; report(false) } }
            session.close()
            throw failure
        }
        device to session
    }

    @Synchronized private fun controlState(session: SonyBleSession, ready: Boolean) { if (current === session) report(ready) }

    fun close() {
        val old = synchronized(this) {
            val session = current ?: return
            current = null
            report(false)
            session
        }
        old.close()
    }
}

/** Foreground control session for the camera selected by the user. Nordic serializes the GATT requests and CCCD writes. */
@SuppressLint("MissingPermission")
internal class SonyBleSession(
    context: Context, private val report: (SonyBleSession, Boolean) -> Unit, locationChanged: () -> Unit
) : BleManager(context) {
    @Volatile private var control: BluetoothGattService? = null
    @Volatile private var pairing: BluetoothGattService? = null
    @Volatile private var location: BluetoothGattService? = null
    private val stopped = AtomicBoolean(false)
    private val hotspotRequests = Mutex()
    private val gattRequests = Mutex()
    @Volatile private var hotspotStatus: SonyHotspotStatus? = null
    val live: Boolean get() = !stopped.get() && isReady
    val supportsLocation: Boolean get() = listOf(SonyLocationProtocol.STATUS, SonyLocationProtocol.LOCATION,
        SonyLocationProtocol.CAPABILITIES, SonyLocationProtocol.LOCK, SonyLocationProtocol.ENABLE,
        SonyLocationProtocol.TIME_SETTING, SonyLocationProtocol.AREA_SETTING).all { location.find(it) != null }
    val locationSync = SonyLocationSession(object : SonyLocationTransport {
        override val live: Boolean get() = this@SonyBleSession.live
        override suspend fun read(id: Int): ByteArray = this@SonyBleSession.read(requiredLocation(id))
        override suspend fun write(id: Int, value: ByteArray, allowed: () -> Boolean) {
            try { this@SonyBleSession.write(requiredLocation(id), value, allowed = allowed) }
            catch (error: RequestFailedException) {
                if (id != SonyLocationProtocol.LOCK || !value.contentEquals(byteArrayOf(1))) throw error
                val message = when (error.status) {
                    0x9d -> "机身暂不允许位置关联，请检查机身设置"
                    0x9f -> "位置关联正由其他设备使用，请先关闭其他手机或 Creators’ App 的位置关联"
                    else -> "机身拒绝位置关联（${error.status}）"
                }
                throw IllegalStateException(message, error)
            }
        }
        override suspend fun observe(callback: ((ByteArray) -> Unit)?) {
            val status = requiredLocation(SonyLocationProtocol.STATUS)
            setNotificationCallback(status).with { _, data -> data.value?.let { callback?.invoke(it) } }
            val notify = status.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val request = if (callback != null) {
                if (notify) enableNotifications(status) else enableIndications(status)
            } else if (notify) disableNotifications(status) else disableIndications(status)
            await(request, 8000)
        }
        override fun close() = this@SonyBleSession.close()
    }, SystemClock::elapsedRealtimeNanos, locationChanged)

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        control = gatt.getService(SonyControlData.controlService)
        pairing = gatt.getService(SonyControlData.pairingService)
        location = gatt.getService(SonyLocationProtocol.service)
        return control != null
    }

    override fun onDeviceReady() { if (!stopped.get()) report(this, true) }
    override fun onServicesInvalidated() {
        control = null; pairing = null; location = null
        hotspotStatus?.failed(IOException("索尼蓝牙连接已结束"))
        report(this, false)
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        hotspotStatus?.failed(IOException("索尼蓝牙连接已结束"))
        control = null; pairing = null; location = null
        try { super.close() } finally { report(this, false) }
    }

    override fun initialize() {
        // Negotiate room for hotspot, access and location records within the connect request's timeout.
        requestMtu(247).enqueue()
    }

    suspend fun open(device: BluetoothDevice) {
        connect(device).useAutoConnect(false).timeout(18_000).suspend()
    }

    suspend fun pair(device: BluetoothDevice) {
        val command = requireNotNull(pairing.find(0xee01)) { "请在索尼机身进入「智能手机连接」的配对界面" }
        val result = CompletableDeferred<Boolean>()
        pairing.find(0xee03)?.let { status ->
            setNotificationCallback(status).with { _, data ->
                data.value?.let(SonyControlData::pairResult)?.let { result.complete(it) }
            }
            if (status.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) await(enableNotifications(status), 6000)
            else await(enableIndications(status), 6000)
        }
        pairing.find(0xee04)?.let { read(it) }
        write(command, byteArrayOf(6, 8, 1, 0, 0, 0, 0), 55_000)
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            check(device.createBond() || device.bondState != BluetoothDevice.BOND_NONE) { "无法开始系统配对，请在机身重新选择连接" }
        }
        val bonded = withTimeoutOrNull(45_000) {
            while (device.bondState != BluetoothDevice.BOND_BONDED) {
                if (result.isCompleted && !result.await()) error("机身拒绝或取消了配对")
                delay(150)
            }
            true
        } ?: false
        check(bonded && (!result.isCompleted || result.await())) { "相机配对未确认，请核对机身和手机提示" }
    }

    suspend fun networkId(): UUID? {
        val id = control.find(0xcca2) ?: return null
        val bytes = try { read(id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return null }
        if (bytes.size != 16 || bytes.all { it == 0.toByte() }) return null
        return ByteBuffer.wrap(bytes).let { UUID(it.long, it.long) }
    }

    suspend fun access(device: BluetoothDevice): SonyAccess? {
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "索尼系统配对已移除，请重新添加相机" }
        return SonyControlData.access(read(required(0xcc17)))
    }

    suspend fun hotspot(device: BluetoothDevice): CameraConnection = hotspotRequests.withLock {
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "请先在手机和机身上确认索尼配对" }
        val status = required(0xcc09)
        val watch = SonyHotspotStatus()
        hotspotStatus = watch
        val notify = status.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        setNotificationCallback(status).with { _, data -> data.value?.let(SonyControlData::wifiStatus)?.let(watch::notified) }
        try {
            watch.guard {
                if (notify) await(enableNotifications(status), 6000) else await(enableIndications(status), 6000)
                startHotspot(watch, status)
                watch.beginInfo()
                val ssid = wifiText(0xcc06)
                val passphrase = wifiText(0xcc07)
                val bssid = control.find(0xcc0c)?.let { item ->
                    try { SonyControlData.bssid(read(item)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
                CameraConnection(ssid = ssid, password = passphrase, sonyAccess = access(device), sonyCamera = true, bssid = bssid)
            }
        } catch (error: Exception) {
            close()
            throw error
        } finally {
            watch.close()
            if (hotspotStatus === watch) hotspotStatus = null
            if (live) {
                if (notify) disableNotifications(status).enqueue() else disableIndications(status).enqueue()
            }
        }
    }

    private suspend fun startHotspot(watch: SonyHotspotStatus, status: BluetoothGattCharacteristic) {
        val readable = status.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        suspend fun refresh() {
            val revision = watch.revision()
            watch.read(SonyControlData.wifiStatus(read(status)), revision)
        }
        val ready = withTimeoutOrNull(30_000) {
            if (readable) refresh()
            // Reassert ON for an initial Launched snapshot; Launching waits without sending another command.
            if (watch.beginStart()) write(required(0xcc08), byteArrayOf(1))
            watch.checkFailure()
            while (watch.latest()?.ready != true) {
                if (!watch.changed() && readable) refresh()
                watch.checkFailure()
            }
            true
        }
        check(ready == true) { "等待机身开启 Wi-Fi 超时，请检查机身连接状态" }
    }

    private suspend fun wifiText(id: Int): String {
        val characteristic = required(id)
        return withTimeoutOrNull(16_000) {
            var text: String? = null
            while (text == null) {
                val data = read(characteristic)
                if (data.size > 3) text = SonyControlData.wifiText(data).takeIf { it.isNotEmpty() }
                if (text == null) delay(400)
            }
            text
        } ?: error("等待索尼相机热点超时，请确认相机已开机且存储卡可读")
    }

    private fun required(id: Int) = requireNotNull(control.find(id)) { "机身没有提供所需的索尼蓝牙功能（${id.toString(16)}）" }
    private fun requiredLocation(id: Int) = requireNotNull(location.find(id)) { "机身位置关联服务已失效" }

    private fun BluetoothGattService?.find(id: Int): BluetoothGattCharacteristic? =
        this?.characteristics?.firstOrNull { (it.uuid.mostSignificantBits ushr 32) == id.toLong() }

    private suspend fun read(characteristic: BluetoothGattCharacteristic): ByteArray {
        var value: ByteArray? = null
        await(readCharacteristic(characteristic).with { _, data -> value = data.value?.clone() }, 8000)
        return requireNotNull(value) { "相机返回空的蓝牙数据" }
    }

    private suspend fun write(
        characteristic: BluetoothGattCharacteristic, value: ByteArray, timeout: Long = 8000, allowed: () -> Boolean = { true }
    ) {
        await(writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT), timeout, allowed)
    }

    private suspend fun await(request: TimeoutableRequest, timeout: Long, allowed: () -> Boolean = { true }) = gattRequests.withLock {
        check(!stopped.get() && allowed()) { "索尼蓝牙请求已失效，请等待新的位置或重新连接" }
        try { request.timeout(timeout).suspend() }
        catch (error: Exception) {
            if (error is CancellationException || error is DeviceDisconnectedException || error is BluetoothDisabledException ||
                (error is RequestFailedException && error.status == FailCallback.REASON_TIMEOUT)) close()
            throw error
        }
    }
}
