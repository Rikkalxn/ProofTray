package org.prooftray.app.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.callback.FailCallback

/** Owns one Sony GATT client from MTU negotiation through the last control or location request. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class SonyGattClient(
    private val context: Context, private val pairing: Boolean, private val bindServices: (BluetoothGatt) -> Boolean,
    private val invalidated: (Exception) -> Unit
) : BluetoothGattCallback(), Closeable {
    private enum class Phase { CONNECTING, MTU, DISCOVERING, READY }
    private data class Request(val name: String, val attribute: Any, val write: Boolean, val result: CompletableDeferred<ByteArray>)

    private val operations = Mutex()
    private val listeners = mutableMapOf<BluetoothGattCharacteristic, (ByteArray) -> Unit>()
    private var opening: CompletableDeferred<Unit>? = null
    private var pending: Request? = null
    private var gatt: BluetoothGatt? = null
    private var closed = false
    @Volatile private var phase = Phase.CONNECTING
    val ready: Boolean get() = phase == Phase.READY

    suspend fun open(device: BluetoothDevice) {
        val name = "连接索尼蓝牙并发现服务"
        try {
            val complete = withTimeoutOrNull(if (pairing) 30_000L else 60_000L) {
                for (attempt in 0..1) {
                    val result = CompletableDeferred<Unit>()
                    synchronized(this@SonyGattClient) {
                        check(!closed && gatt == null) { "索尼蓝牙连接已结束" }
                        opening = result
                        gatt = device.connectGatt(context, false, this@SonyGattClient, BluetoothDevice.TRANSPORT_LE)
                            ?: throw sonyGattFailure(name, FailCallback.REASON_REQUEST_FAILED, connecting = true)
                    }
                    try { result.await(); break }
                    catch (error: SonyBluetoothFailure) {
                        if (attempt != 0 || error.gattStatus !in setOf(8, 62, 133, 147)) throw error
                        delay(500)
                    }
                }
                true
            }
            if (complete == null) throw sonyGattFailure(name, FailCallback.REASON_TIMEOUT, connecting = true)
        } catch (failure: Exception) { close(); throw failure }
    }

    @Synchronized override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (gatt !== this.gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
            val reason = if (phase == Phase.CONNECTING && status != 0) status else FailCallback.REASON_DEVICE_DISCONNECTED
            retire(reason)
        } else if (newState == BluetoothProfile.STATE_CONNECTED && phase == Phase.CONNECTING) {
            phase = Phase.MTU
            try { if (!gatt.requestMtu(158)) discover(gatt) }
            catch (_: SecurityException) { retire(FailCallback.REASON_REQUEST_FAILED) }
        }
    }

    @Synchronized override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        if (gatt === this.gatt && phase == Phase.MTU) discover(gatt)
    }

    private fun discover(gatt: BluetoothGatt) {
        phase = Phase.DISCOVERING
        try { if (!gatt.discoverServices()) retire(FailCallback.REASON_REQUEST_FAILED) }
        catch (_: SecurityException) { retire(FailCallback.REASON_REQUEST_FAILED) }
    }

    @Synchronized override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (gatt !== this.gatt || phase != Phase.DISCOVERING) return
        if (status != BluetoothGatt.GATT_SUCCESS) { retire(status); return }
        if (!bindServices(gatt)) { retire(FailCallback.REASON_NULL_ATTRIBUTE); return }
        phase = Phase.READY
        opening?.complete(Unit)
        opening = null
    }

    @Synchronized override fun onServiceChanged(gatt: BluetoothGatt) {
        if (gatt === this.gatt && phase == Phase.READY) retire(FailCallback.REASON_DEVICE_DISCONNECTED)
    }

    suspend fun read(item: BluetoothGattCharacteristic, name: String, timeout: Long): ByteArray =
        request(item, false, name, timeout) { it.readCharacteristic(item) }

    suspend fun write(item: BluetoothGattCharacteristic, value: ByteArray, name: String, timeout: Long, allowed: () -> Boolean) {
        request(item, true, name, timeout) { link ->
            check(allowed()) { "索尼蓝牙请求已失效，请等待新的位置或重新连接" }
            val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (Build.VERSION.SDK_INT >= 33) link.writeCharacteristic(item, value, type) == BluetoothStatusCodes.SUCCESS
            else { item.writeType = type; item.value = value; link.writeCharacteristic(item) }
        }
    }

    suspend fun observe(item: BluetoothGattCharacteristic, name: String, timeout: Long, listener: ((ByteArray) -> Unit)?) {
        val descriptor = item.getDescriptor(CCCD) ?: throw sonyGattFailure(name, FailCallback.REASON_NULL_ATTRIBUTE)
        val value = when {
            listener == null -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            item.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        try {
            request(descriptor, true, name, timeout) { link ->
                if (listener == null) listeners.remove(item) else listeners[item] = listener
                if (!link.setCharacteristicNotification(item, listener != null)) return@request false
                if (Build.VERSION.SDK_INT >= 33) link.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                else { descriptor.value = value; link.writeDescriptor(descriptor) }
            }
        } catch (error: Exception) {
            synchronized(this) { if (listeners[item] === listener) listeners.remove(item) }
            throw error
        }
    }

    private suspend fun request(attribute: Any, write: Boolean, name: String, timeout: Long, start: (BluetoothGatt) -> Boolean): ByteArray =
        operations.withLock {
            val result = CompletableDeferred<ByteArray>()
            val caller = currentCoroutineContext()
            try {
                synchronized(this) {
                    caller.ensureActive()
                    val link = gatt?.takeIf { ready } ?: throw sonyGattFailure(name, FailCallback.REASON_DEVICE_DISCONNECTED)
                    pending = Request(name, attribute, write, result)
                    if (!start(link)) throw sonyGattFailure(name, FailCallback.REASON_REQUEST_FAILED)
                }
                withTimeoutOrNull(timeout) { result.await() } ?: run {
                    val failure = sonyGattFailure(name, FailCallback.REASON_TIMEOUT)
                    synchronized(this) { retire(FailCallback.REASON_TIMEOUT, failure) }
                    throw failure
                }
            } catch (cancelled: CancellationException) {
                // Android cannot cancel an individual ATT request. Retire its client before admitting another request.
                close()
                throw cancelled
            } finally { synchronized(this) { pending = null } }
        }

    @Synchronized private fun received(gatt: BluetoothGatt, attribute: Any, write: Boolean, status: Int, value: ByteArray = byteArrayOf()) {
        val request = pending ?: return
        if (gatt !== this.gatt || attribute !== request.attribute || write != request.write) return
        if (status == BluetoothGatt.GATT_SUCCESS) request.result.complete(value.clone())
        else request.result.completeExceptionally(sonyGattFailure(request.name, status))
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, item: BluetoothGattCharacteristic, status: Int) =
        received(gatt, item, false, status, item.value ?: byteArrayOf())
    override fun onCharacteristicRead(gatt: BluetoothGatt, item: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
        received(gatt, item, false, status, value)
    override fun onCharacteristicWrite(gatt: BluetoothGatt, item: BluetoothGattCharacteristic, status: Int) = received(gatt, item, true, status)
    override fun onDescriptorWrite(gatt: BluetoothGatt, item: BluetoothGattDescriptor, status: Int) = received(gatt, item, true, status)
    override fun onCharacteristicChanged(gatt: BluetoothGatt, item: BluetoothGattCharacteristic) {
        item.value?.let { changed(gatt, item, it) }
    }
    override fun onCharacteristicChanged(gatt: BluetoothGatt, item: BluetoothGattCharacteristic, value: ByteArray) = changed(gatt, item, value)
    @Synchronized private fun changed(gatt: BluetoothGatt, item: BluetoothGattCharacteristic, value: ByteArray) {
        if (gatt !== this.gatt || !ready) return
        if (Build.VERSION.SDK_INT <= 30 && item.uuid == SERVICE_CHANGED && item.service?.uuid == GATT_SERVICE) {
            retire(FailCallback.REASON_DEVICE_DISCONNECTED)
        } else listeners[item]?.invoke(value)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        retire(FailCallback.REASON_DEVICE_DISCONNECTED)
    }

    private fun retire(status: Int, cause: SonyBluetoothFailure? = null) {
        val link = gatt
        gatt = null
        phase = Phase.CONNECTING
        listeners.clear()
        val request = pending
        val failure = cause ?: if (request != null) sonyGattFailure(request.name, status)
            else sonyGattFailure("索尼蓝牙连接", status, connecting = true)
        opening?.completeExceptionally(failure)
        opening = null
        request?.result?.completeExceptionally(failure)
        if (link != null) {
            runCatching { link.disconnect() }
            // Android may cache only the registration profile during the first bond. Preserve the previous client's best-effort refresh.
            if (pairing) runCatching { BluetoothGatt::class.java.getMethod("refresh").invoke(link) }
            runCatching { link.close() }
        }
        invalidated(failure)
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val GATT_SERVICE: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
        val SERVICE_CHANGED: UUID = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
    }
}
