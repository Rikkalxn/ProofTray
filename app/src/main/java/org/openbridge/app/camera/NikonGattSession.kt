package org.openbridge.app.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.TimeoutableRequest
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.exception.BluetoothDisabledException
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.RequestFailedException
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver

internal fun nikonUuid(id: String): UUID = UUID.fromString("0000$id-3dd4-4255-8d62-6dc7b9bd5561")

/** SnapBridge sets notifications before indications, and waits for each CCCD acknowledgement before authentication. */
internal suspend fun subscribeNikonProfile(present: (String) -> Boolean, subscribe: suspend (id: String, indication: Boolean) -> Int?) {
    var control = subscribe("2008", false)
    if (control == 0x81) {
        // After an initial 129, SnapBridge reasserts this CCCD for 50 intervals, then checks for a non-busy reply.
        suspend fun poll() { delay(100); control = subscribe("2008", false) }
        repeat(50) { poll() }
        // Only limit the still-busy tail, not the camera's required recovery window.
        if (control == 0x81) withTimeout(30_000) {
            while (control == 0x81) poll()
        }
    }
    check(control == 0) { "尼康控制点通知订阅失败（$control）" }
    for (id in listOf("200a", "2081")) {
        if (present(id)) subscribe(id, false)?.let { check(it == 0) { "尼康特征 $id 订阅失败，GATT 状态码 $it" } }
    }
    // The reference waits for an indication descriptor callback. Identity is verified by the subsequent challenge exchange.
    suspend fun indicate(id: String) { check(subscribe(id, true) != null) { "未收到尼康特征 $id 的指示订阅回调" } }
    indicate("2000")
    if (present("2020")) {
        indicate("2020")
        if (present("2021")) indicate("2021")
    }
}

/** One manager per authenticated control link. The separate wake-only connection never discovers services. */
@SuppressLint("MissingPermission")
internal class NikonGattSession(
    context: Context, private val serviceIds: List<UUID>, private val discovering: () -> Unit,
    private val controlPoint: (ByteArray) -> Unit, private val ended: (Exception) -> Unit
) : BleManager(context), ConnectionObserver {
    private val failure = AtomicReference<Exception?>()
    private val linked = CompletableDeferred<Unit>()
    private val release = GattRelease(::closeTransport) { finished ->
        val timer = releaseTimer.schedule(Runnable {
            Log.w("OpenBridgeBluetooth", "Retiring Nikon GATT: release deadline reached")
            finished()
        }, 3000, TimeUnit.MILLISECONDS)
        Closeable { timer.cancel(false) }
    }
    suspend fun awaitRelease() = release.awaitClosed { Log.w("OpenBridgeBluetooth", "Retired Nikon GATT close failed; retried old manager", it) }
    private val indications = Channel<ByteArray>(16)
    // Check radio ownership at the head of the queue, before submitting a write that changes camera mode.
    private val admission = Mutex()
    @Volatile private var service: BluetoothGattService? = null
    @Volatile private var opened = false
    val live: Boolean get() = failure.get() == null && service != null && isReady

    init { setConnectionObserver(this) }

    // An awake Nikon starts service discovery directly on link-up.
    override fun getServiceDiscoveryDelay(bonded: Boolean) = 0
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        service = serviceIds.asSequence().mapNotNull(gatt::getService).firstOrNull { it.getCharacteristic(nikonUuid("2000")) != null }
        return service != null
    }

    override fun onServicesInvalidated() {
        val changedOnLiveLink = opened && service != null && isConnected
        service = null
        if (changedOnLiveLink) runOnCallbackThread { stop(BleTransportException("尼康服务已变化，需重新建立控制连接")) }
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        linked.complete(Unit)
        if (failure.get() == null && !opened) discovering()
    }
    override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
        if (opened) stop(nikonDisconnectFailure(reason))
    }
    override fun onDeviceConnecting(device: BluetoothDevice) = Unit
    override fun onDeviceReady(device: BluetoothDevice) = Unit
    override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
    override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit // ConnectRequest carries the original GATT status.

    suspend fun open(device: BluetoothDevice) = operation("建立连接") {
        try {
            coroutineScope {
                val ready = async(start = CoroutineStart.UNDISPATCHED) { connect(device).useAutoConnect(false).suspend() }
                try {
                    withTimeout(30_000) { select<Unit> { linked.onAwait { }; ready.onAwait { } } }
                    withTimeout(20_000) { ready.await() }
                } finally { ready.cancel() }
            }
        }
        catch (error: RequestFailedException) {
            if (error.status == FailCallback.REASON_DEVICE_NOT_SUPPORTED) error("机身没有提供 Nikon 智能设备服务")
            if (error.status >= 0) throw BleTransportException(error.status, "建立连接失败")
            throw error
        }
        opened = true
        if (!live) throw BleTransportException("服务发现后蓝牙连接已结束")
    }

    suspend fun subscribe() = operation("订阅") {
        setIndicationCallback(required("2000")).with { _, data ->
            data.value?.let { if (failure.get() == null) indications.trySend(it.clone()) }
        }
        setNotificationCallback(required("2008")).with { _, data ->
            data.value?.let { if (failure.get() == null) controlPoint(it) }
        }
        subscribeNikonProfile({ characteristic(it) != null }) { id, indication ->
            val target = required(id)
            val request = if (indication) enableIndications(target) else enableNotifications(target)
            val status = try { await(request, 5000); BluetoothGatt.GATT_SUCCESS }
                catch (error: RequestFailedException) {
                    when {
                        error.status >= 0 -> error.status
                        error.status == FailCallback.REASON_NULL_ATTRIBUTE || error.status == FailCallback.REASON_REQUEST_FAILED -> null
                        else -> throw error
                    }
                }
            Log.i("OpenBridgeBluetooth", "GATT subscribe characteristic=$id indication=$indication status=$status")
            status
        }
    }

    suspend fun authenticationStage(expected: Int) = authenticationRecord(withTimeout(5000) { indications.receive() }, expected)

    suspend fun read(id: String): ByteArray = operation("读取 $id") { readValue(id) }

    private suspend fun readValue(id: String): ByteArray {
        var result: ByteArray? = null
        await(readCharacteristic(required(id)).with { _, data -> result = data.value?.clone() }, 5000)
        return result ?: byteArrayOf()
    }

    suspend fun write(id: String, value: ByteArray, endsControlLink: Boolean = false, tolerateAttributeRejection: Boolean = false,
        allowed: () -> Boolean = { true }): Boolean =
        operation("写入 $id", requireLiveAfter = !endsControlLink) {
            if (!allowed()) return@operation false
            try { writeValue(id, value) }
            catch (error: RequestFailedException) {
                if (!tolerateAttributeRejection || !isNikonAttributeRejection(error.status)) throw error
                currentCoroutineContext().ensureActive()
                Log.i("OpenBridgeBluetooth", "Optional pairing write rejected: characteristic=$id status=${error.status}")
                return@operation false
            }
            true
        }

    private suspend fun writeValue(id: String, value: ByteArray) {
        val target = required(id)
        await(writeCharacteristic(target, value, target.writeType), 5000)
        Log.i("OpenBridgeBluetooth", "GATT write characteristic=$id type=${target.writeType} bytes=${value.size} completed")
    }

    suspend fun syncRequestedTime(currentTime: () -> ByteArray, allowed: () -> Boolean): Boolean = operation("响应校时请求") {
        syncNikonRequestedTime(currentTime, ::readValue, ::writeValue, allowed)
    }

    suspend fun acknowledgeConnectionRequest(allowed: () -> Boolean) = operation("确认连接请求") {
        check(allowed()) { "相机传输切换已取消" }
        val control = NikonControlPoint.decode(readValue("2008")) ?: return@operation
        if (!control.connectionRequested) return@operation
        check(allowed()) { "相机传输切换已取消" }
        writeValue("2008", control.copy(connection = 0).encode())
    }

    fun readable(id: String): Boolean = ((characteristic(id)?.properties ?: 0) and BluetoothGattCharacteristic.PROPERTY_READ) != 0

    fun writable(id: String): Boolean {
        val writes = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        return (characteristic(id)?.properties ?: 0) and writes != 0
    }
    private fun characteristic(id: String) = service?.getCharacteristic(nikonUuid(id))
    private fun required(id: String): BluetoothGattCharacteristic {
        val current = service ?: throw (failure.get() ?: BleTransportException("尼康服务所属连接已结束"))
        return checkNotNull(current.getCharacteristic(nikonUuid(id))) { "相机缺少尼康特征 $id" }
    }

    // The base TimeoutableRequest KTX overload cancels in-flight work; the value-returning read/write overloads do not.
    private suspend fun await(request: TimeoutableRequest, timeout: Long) { request.timeout(timeout).suspend() }

    private suspend fun <T> operation(name: String, requireLiveAfter: Boolean = true, block: suspend () -> T): T = admission.withLock {
        currentCoroutineContext().ensureActive()
        failure.get()?.let { throw it }
        try { block().also { if (requireLiveAfter) failure.get()?.let { cause -> throw cause } } }
        catch (error: Exception) {
            failure.get()?.let { throw it }
            val cause = when (error) {
                is CancellationException, is BleTransportException -> error
                is DeviceDisconnectedException -> BleTransportException("BLE $name 时连接已结束")
                is BluetoothDisabledException -> BleTransportException("手机蓝牙已关闭", false)
                is RequestFailedException -> if (error.status == FailCallback.REASON_TIMEOUT) BleTransportException("BLE $name 超时")
                    else throw IllegalStateException("BLE $name 被拒，状态码 ${error.status}", error)
                else -> throw error
            }
            stop(cause)
            throw cause
        }
    }

    fun stop(cause: Exception = CancellationException("蓝牙连接操作已取消")) {
        if (!failure.compareAndSet(null, cause)) return
        indications.close(cause)
        setConnectionObserver(null)
        release.begin { finished ->
            ended(cause)
            cancelQueue()
            if (isConnected && cause is CancellationException && !admission.isLocked) {
                disconnect().timeout(2000).then { finished() }.invalid { finished() }.enqueue()
            } else finished()
        }
    }

    private fun closeTransport() {
        super.close()
        Log.i("OpenBridgeBluetooth", "Retired Nikon GATT transport closed")
    }

    private companion object {
        val releaseTimer = Executors.newSingleThreadScheduledExecutor { job -> Thread(job, "ProofTray-BLE-close").apply { isDaemon = true } }
    }
}
