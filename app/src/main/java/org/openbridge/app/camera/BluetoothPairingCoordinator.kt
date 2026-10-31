package org.openbridge.app.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.openbridge.app.data.AppSettingsRepository

/** BLE verifies the camera; BR/EDR pairing is a separate system-confirmed step, followed by reconnecting BLE control. */
@SuppressLint("MissingPermission")
internal class BluetoothPairingCoordinator(
    private val context: Context, private val scope: CoroutineScope, private val ble: NikonBle, private val settings: AppSettingsRepository,
    private val beforeConnect: suspend () -> Unit
) {
    private data class Event(val device: BluetoothDevice? = null, val bond: Int? = null, val discoveryFinished: Boolean = false)
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var job: Job? = null
    @Volatile private var generation = 0

    @Synchronized
    fun connect(camera: BleCamera, rediscover: Boolean = false, initializePairing: Boolean = true): Job {
        val previous = job
        previous?.cancel()
        val token = ++generation
        ble.pairingStatus("正在连接并检查系统配对", active = true, paired = false)
        ble.disconnect()
        job = scope.launch {
            var pairedAddress = ""
            fun progress(message: String) {
                if (generation == token) ble.pairingStatus(message, true, pairedAddress.isNotBlank(), pairedAddress)
                Log.i("OpenBridgeBluetooth", "pairing: $message")
            }
            try {
                previous?.join()
                beforeConnect()
                var control = if (camera.classic) settings.state.value.controlAddress else camera.address
                check(control.isNotBlank()) { "请先搜索并连接相机的 BLE 控制设备" }
                val knownNames = if (rediscover || camera.classic) savedNames(control) else setOf(camera.name).filter(String::isNotBlank).toSet()
                val savedClassic = settings.state.value.transferAddress.takeIf(String::isNotBlank)?.let(adapter::getRemoteDevice)
                if (savedClassic?.bondState == BluetoothDevice.BOND_BONDED && matchesCameraName(savedClassic.name, knownNames)) {
                    pairedAddress = savedClassic.address
                }
                if (rediscover || camera.classic) {
                    progress("正在重新发现相机当前的 BLE 地址")
                    control = ble.rediscover(knownNames, control).address
                }
                val verified = connectControl(control, knownNames, ::progress)
                control = verified.connectedAddress
                currentCoroutineContext().ensureActive()
                val names = setOf(verified.cameraName, verified.serverName).filter(String::isNotBlank).toSet()
                val savedName = verified.cameraName.ifBlank { verified.serverName }
                settings.update { it.copy(controlAddress = control, controlName = savedName) }
                val chosen = if (camera.classic) adapter.getRemoteDevice(camera.address) else null
                val existing = if (chosen != null) chosen.takeIf { it.bondState == BluetoothDevice.BOND_BONDED }
                    else adapter.bondedDevices.firstOrNull {
                        it.type != BluetoothDevice.DEVICE_TYPE_LE && matchesCameraName(it.name, names)
                    }
                if (initializeCameraPairing(initializePairing, existing != null)) ble.prepareSystemPairing()
                val paired = existing ?: run {
                    check(chosen != null || names.isNotEmpty()) { "未读到相机名称，无法关联系统配对设备" }
                    // Complete the application exchange before leaving BLE and searching for the camera's classic address.
                    ble.disconnect()
                    progress("身份验证已完成，正在搜索对应的系统配对设备")
                    awaitSystemPairing(names, chosen, ::progress)
                }
                currentCoroutineContext().ensureActive()
                check(paired.bondState == BluetoothDevice.BOND_BONDED) { "系统尚未确认相机配对成功" }
                pairedAddress = paired.address
                settings.update { it.copy(controlAddress = control, transferAddress = paired.address) }
                if (!ble.state.value.authenticated || ble.state.value.connectedAddress != control) {
                    progress("系统配对已完成，正在重新发现 BLE 控制地址")
                    control = ble.rediscover(names + paired.name.orEmpty(), control).address
                    val restored = connectControl(control, names, ::progress)
                    control = restored.connectedAddress
                    settings.update { it.copy(controlAddress = control, controlName = restored.cameraName.ifBlank { restored.serverName }) }
                }
                currentCoroutineContext().ensureActive()
                if (generation == token) ble.pairingStatus("系统已配对，BLE 控制已连接", false, true, paired.address)
            } catch (error: Exception) {
                if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                Log.w("OpenBridgeBluetooth", "pairing sequence failed", error)
                val prefix = if (pairedAddress.isBlank()) "配对未完成" else "系统已配对，控制连接未完成"
                if (generation == token) ble.pairingStatus("$prefix：${error.message ?: "请重新尝试"}", false,
                    pairedAddress.isNotBlank(), pairedAddress)
            }
        }
        return checkNotNull(job)
    }

    private fun savedNames(control: String): Set<String> {
        val saved = settings.state.value
        if (saved.controlName.isNotBlank()) return setOf(saved.controlName)
        val classicName = saved.transferAddress.takeIf(String::isNotBlank)?.let { adapter.getRemoteDevice(it).name }
        return listOfNotNull(classicName, adapter.getRemoteDevice(control).name).filter(String::isNotBlank).toSet()
    }

    suspend fun awaitCurrent() {
        val current = synchronized(this) { job }
        current?.join()
    }

    private suspend fun connectControl(address: String, names: Set<String>, progress: (String) -> Unit): BleState {
        var current = address
        for (attempt in 0..1) {
            try { return ble.connect(current, preserveHistory = true).await() }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (attempt != 0 || names.isEmpty() || !retryableBleFailure(error)) throw error
                progress("控制连接中断，重新扫描后再尝试一次")
                delay(1000)
                current = ble.rediscover(names, current).address
            }
        }
        error("BLE 控制连接未能建立")
    }

    @Synchronized
    fun cancel() {
        generation++
        job?.cancel()
        job = null
        ble.pairingStatus("蓝牙连接已结束", active = false, paired = ble.state.value.systemPaired, address = ble.state.value.classicAddress)
    }

    private suspend fun awaitSystemPairing(
        names: Set<String>, selected: BluetoothDevice?, progress: (String) -> Unit
    ): BluetoothDevice {
        val events = Channel<Event>(64)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                    events.trySend(Event(discoveryFinished = true))
                    return
                }
                @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (intent.action == BluetoothDevice.ACTION_FOUND) {
                    if (isNikonCameraName(device.name.orEmpty()) || matchesCameraName(device.name, names)) {
                        ble.offerClassicDevice(BleCamera(device.address, cameraBluetoothName(device.name).ifBlank { "Nikon 相机" }, true))
                    }
                    if (matchesCameraName(device.name, names)) events.trySend(Event(device))
                } else if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    events.trySend(Event(device, intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, device.bondState)))
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        var ownsDiscovery = false
        try {
            var target = selected
            var attempts = 0
            while (target == null && attempts++ < 3) {
                currentCoroutineContext().ensureActive()
                check(adapter != null && adapter.isEnabled) { "手机蓝牙已关闭" }
                if (!adapter.isDiscovering) {
                    delay(1000)
                    val started = adapter.startDiscovery()
                    ownsDiscovery = started
                    Log.i("OpenBridgeBluetooth", "classic discovery attempt=$attempts started=$started active=${adapter.isDiscovering}")
                    if (!started && !adapter.isDiscovering) { delay(1000); continue }
                }
                target = withTimeoutOrNull(20000) {
                    var found: BluetoothDevice? = null
                    while (found == null) {
                        val event = events.receive()
                        if (event.discoveryFinished) { ownsDiscovery = false; break }
                        if (event.device != null && matchesCameraName(event.device.name, names)) found = event.device
                    }
                    found
                }
            }
            val device = target ?: error("未找到对应相机的系统配对设备；请保持机身在开始配对界面后重试")
            adapter.cancelDiscovery()
            ownsDiscovery = false
            if (device.bondState == BluetoothDevice.BOND_BONDED) return device
            progress("已找到相机，请核对手机和机身的配对码并确认")
            if (device.bondState != BluetoothDevice.BOND_BONDING) {
                check(device.createBond() || device.bondState == BluetoothDevice.BOND_BONDING) { "系统未接受配对请求，请稍后重试" }
            }
            return withTimeout(120000) {
                while (true) {
                    if (device.bondState == BluetoothDevice.BOND_BONDED) return@withTimeout device
                    val event = events.receive()
                    if (event.device?.address != device.address) continue
                    Log.i("OpenBridgeBluetooth", "classic target bond state=${event.bond}")
                    if (event.bond == BluetoothDevice.BOND_BONDED) return@withTimeout device
                    check(event.bond != BluetoothDevice.BOND_NONE) { "系统配对未完成或已取消，请核对机身提示" }
                }
                @Suppress("UNREACHABLE_CODE") error("配对已结束")
            }
        } finally {
            if (ownsDiscovery) runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
            events.close()
        }
    }
}
