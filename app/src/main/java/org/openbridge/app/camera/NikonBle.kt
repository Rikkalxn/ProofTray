package org.openbridge.app.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.openbridge.app.data.BluetoothRole

data class BleCamera(val address: String, val name: String, val classic: Boolean = false)
data class BleState(
    val cameras: List<BleCamera> = emptyList(), val scanning: Boolean = false,
    val authenticated: Boolean = false, val message: String = "蓝牙未连接", val connectedAddress: String = "",
    val imageTransferRequested: Boolean = false, val locationRequested: Boolean = false, val serialNumber: String = "",
    val history: List<String> = emptyList(), val cameraName: String = "", val serverName: String = "",
    val pairingInProgress: Boolean = false, val systemPaired: Boolean = false, val classicAddress: String = "",
    val wifiRequested: Boolean = false, val connectionRequested: Boolean = false, val clientName: String = ""
)

private fun BleState.record(message: String): BleState = copy(message = message,
    history = if (history.lastOrNull() == message) history else (history + message).takeLast(12))

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class NikonBle(private val context: Context, private val scope: CoroutineScope) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val mutableState = MutableStateFlow(BleState())
    val state = mutableState.asStateFlow()
    private val mutableTransferRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val transferRequests = mutableTransferRequests.asSharedFlow()
    @Volatile private var gatt: BluetoothGatt? = null
    private var connectionJob: Job? = null
    private var scanJob: Job? = null
    @Volatile private var scanGeneration = 0
    private var activeScan: ScanCallback? = null
    @Volatile private var generation = 0
    @Volatile private var connectedToken: Int? = null
    private val writes = Mutex()
    @Volatile private var reply: CompletableDeferred<Int>? = null
    @Volatile private var readReply: CompletableDeferred<ByteArray>? = null
    @Volatile private var featureFlags = 0
    @Volatile private var sessionKey: ByteArray? = null
    private val discovered = ConcurrentHashMap<String, BluetoothDevice>()
    private val serviceIds = listOf(uuid("de00"), uuid("de01"), UUID.fromString("6155bdb9-c76d-628d-5542-d43d01de0000"))

    private fun scanCallback(token: Int) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (scanGeneration != token) return
            val name = cameraBluetoothName(result.scanRecord?.deviceName).ifBlank { cameraBluetoothName(result.device.name) }
            val item = BleCamera(result.device.address, name.ifBlank { "Nikon 相机" })
            if (discovered.size < 64 || discovered.containsKey(item.address)) discovered[item.address] = result.device
            mutableState.update { it.copy(cameras = (it.cameras.filterNot { old -> old.address == item.address } + item).takeLast(30)) }
        }
        override fun onScanFailed(errorCode: Int) {
            if (scanGeneration == token) mutableState.update { it.copy(scanning = false, message = "蓝牙扫描失败：$errorCode") }
        }
    }

    @Synchronized
    fun scan() {
        runCatching {
            check(adapter != null && adapter.isEnabled) { "请先打开手机蓝牙" }
            stopScan()
            discovered.clear()
            val token = scanGeneration
            val callback = scanCallback(token).also { activeScan = it }
            mutableState.update { it.copy(cameras = emptyList(), scanning = true, message = "请在相机上进入“连接至智能设备 / 开始配对”") }
            val filters = serviceIds.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            adapter.bluetoothLeScanner.startScan(filters, settings, callback)
            scanJob = scope.launch {
                delay(20000)
                synchronized(this@NikonBle) { if (scanGeneration == token) stopScan() }
            }
        }.onFailure { mutableState.update { state -> state.copy(scanning = false, message = it.message ?: "无法扫描蓝牙") } }
    }

    suspend fun rediscover(names: Set<String>, previousAddress: String): BleCamera {
        require(names.any(String::isNotBlank)) { "尚未记录相机名称，请先搜索并选择相机" }
        scan()
        if (state.value.scanning) connectionMessage("正在重新发现已关联相机，请保持相机开机")
        try {
            val result = withTimeout(22000) {
                state.first { value -> value.cameras.any { !it.classic && matchesCameraName(it.name, names) } || !value.scanning }
            }
            val camera = result.cameras.firstOrNull { !it.classic && matchesCameraName(it.name, names) }
                ?: error("未发现已关联相机的 BLE 广播，请保持相机开机后重试")
            Log.i("OpenBridgeBluetooth", "BLE rediscovery matched target; addressChanged=${camera.address != previousAddress}")
            return camera
        } finally { stopScan() }
    }

    /** Wait for a selected camera's pending-image advertisement without opening GATT or repeatedly waking the camera. */
    suspend fun awaitTransferAdvertisement(names: Set<String>): BleCamera {
        check(adapter != null && adapter.isEnabled) { "请先打开手机蓝牙" }
        require(names.any(String::isNotBlank)) { "请先关联相机" }
        val scanner = adapter.bluetoothLeScanner ?: error("蓝牙扫描不可用")
        val pending = CompletableDeferred<BleCamera>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val name = cameraBluetoothName(record.deviceName).ifBlank { cameraBluetoothName(result.device.name) }
                if (!matchesCameraName(name, names)) return
                // Android removes the two-byte company ID: four client-ID bytes followed by LSS advertisement flags.
                val manufacturer = record.manufacturerSpecificData
                val requested = (0 until manufacturer.size()).any {
                    val payload = manufacturer.valueAt(it)
                    payload.size == 5 && payload[4].toInt() and 2 != 0
                }
                if (!requested) return
                discovered[result.device.address] = result.device
                Log.i("OpenBridgeBluetooth", "selected camera advertises pending images")
                pending.complete(BleCamera(result.device.address, name))
            }
            override fun onScanFailed(errorCode: Int) { pending.completeExceptionally(IllegalStateException("待机扫描失败：$errorCode")) }
        }
        val filters = serviceIds.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        try {
            scanner.startScan(filters, scanSettings, callback)
            return pending.await()
        } finally { runCatching { scanner.stopScan(callback) } }
    }

    @Synchronized
    fun connect(address: String, preserveHistory: Boolean = false): Deferred<BleState> {
        disconnect()
        val token = generation
        val outcome = CompletableDeferred<BleState>()
        val name = runCatching { cameraBluetoothName(adapter.getRemoteDevice(address).name) }.getOrDefault("")
        mutableState.update { it.copy(history = if (preserveHistory) it.history else emptyList(), cameraName = name, serverName = "")
            .record("正在建立 BLE 通道") }
        connectionJob = scope.launch(Dispatchers.IO) {
            var ownedGatt: BluetoothGatt? = null
            try {
                val ready = CompletableDeferred<Unit>()
                val notifications = Channel<ByteArray>(16)
                fun notification(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
                    if (characteristic.uuid == uuid("2000")) {
                        val stage = bytes.firstOrNull()?.toInt()?.and(255)
                        Log.i("OpenBridgeBluetooth", "authentication notification stage=$stage bytes=${bytes.size}")
                        if (notifications.trySend(bytes.clone()).isFailure) {
                            Log.w("OpenBridgeBluetooth", "authentication notification queue unavailable")
                        }
                    }
                    if (characteristic.uuid == uuid("2008") && bytes.size >= 2) {
                        val flags = (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
                        val requested = (flags shr 8 and 15) == 1
                        mutableState.update { it.copy(connectionRequested = requested,
                            imageTransferRequested = requested && !it.wifiRequested, locationRequested = (flags shr 4 and 15) in 1..2) }
                        Log.i("OpenBridgeBluetooth", "camera connection request=$requested wifiPreferred=${state.value.wifiRequested}")
                        if (requested && !state.value.wifiRequested) mutableTransferRequests.tryEmit(Unit)
                    }
                }
                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
                        if (generation != token) return
                        Log.i("OpenBridgeBluetooth", "GATT connection status=$status state=$newState")
                        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                            connectedToken = token
                            step(token, "蓝牙已连接，正在发现相机服务")
                            if (!connection.discoverServices()) ready.completeExceptionally(BleTransportException(257, "服务发现未能启动"))
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                            connectedToken = null
                            val error = BleTransportException(status)
                            ready.completeExceptionally(error)
                            reply?.completeExceptionally(error)
                            readReply?.completeExceptionally(error)
                            notifications.close(error)
                            mutableState.update { if (generation == token) it.copy(authenticated = false).record(error.message.orEmpty()) else it }
                        }
                    }
                    override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
                        if (generation != token) return
                        Log.i("OpenBridgeBluetooth", "GATT services status=$status")
                        if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
                        else ready.completeExceptionally(BleTransportException(status, "发现服务失败"))
                    }
                    override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                        if (generation == token) reply?.complete(status)
                    }
                    override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                        if (generation == token) {
                            val id = characteristic.uuid.toString().substring(4, 8)
                            Log.i("OpenBridgeBluetooth", "GATT write result characteristic=$id status=$status")
                            reply?.complete(status)
                        }
                    }
                    override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                        if (Build.VERSION.SDK_INT < 33 && generation == token) completeRead(characteristic.value ?: byteArrayOf(), status)
                    }
                    override fun onCharacteristicRead(
                        connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int
                    ) {
                        if (generation == token) completeRead(value, status)
                    }
                    override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                        if (Build.VERSION.SDK_INT < 33 && generation == token) {
                            notification(characteristic, characteristic.value ?: byteArrayOf())
                        }
                    }
                    override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                        if (generation == token) notification(characteristic, value)
                    }
                }
                val device = discovered[address] ?: adapter.getRemoteDevice(address)
                val connection = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: error("无法创建 BLE 连接")
                ownedGatt = connection
                synchronized(this@NikonBle) {
                    check(generation == token) { "蓝牙连接操作已取消" }
                    gatt = connection
                }
                withTimeout(20000) { ready.await() }
                step(token, "已发现相机服务，正在订阅握手响应")
                val service = smartService(connection) ?: error("相机没有提供 Nikon 智能设备服务")
                Log.i("OpenBridgeBluetooth", "smart-device service=${service.uuid}")
                for ((id, indication) in listOf("2000" to true, "2008" to false)) {
                    val characteristic = service.getCharacteristic(uuid(id)) ?: error("相机缺少配对特征 $id")
                    check(connection.setCharacteristicNotification(characteristic, true))
                    val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        ?: error("配对特征不支持通知")
                    writeOperation {
                        descriptor.value = if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        connection.writeDescriptor(descriptor)
                    }
                }
                val preferences = context.getSharedPreferences("camera-identity", Context.MODE_PRIVATE)
                val start = ByteArray(17).also { SecureRandom().nextBytes(it); it[0] = 1 }
                val identity = preferences.getString("bleIdentity", null)?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
                    ?: ByteArray(8).also {
                        SecureRandom().nextBytes(it); it[0] = 1
                        preferences.edit().putString("bleIdentity", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)).apply()
                    }
                check(identity.size == 8)
                identity.copyInto(start, 9)
                step(token, "正在验证相机身份：发送请求")
                write("2000", start)
                val challenge = authenticationRecord(withTimeout(15000) { notifications.receive() }, 2)
                val response = NativePtp.pairReply(start, challenge)
                step(token, "相机挑战校验通过，正在等待最终确认")
                write("2000", response)
                val confirmation = authenticationRecord(withTimeout(15000) { notifications.receive() }, 4)
                val key = NativePtp.pairingKey(start, challenge, confirmation)
                check(key.size == 8) { "蓝牙会话密钥生成失败" }
                synchronized(this@NikonBle) {
                    check(generation == token && gatt === connection) { "蓝牙连接已取消" }
                    sessionKey = key
                }
                Log.i("OpenBridgeBluetooth", "authentication stage 4 accepted; session key derived")
                step(token, "身份验证通过，正在读取相机信息")
                val serverName = try { read("2003").toString(Charsets.UTF_8).substringBefore('\u0000').trim() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { "" }
                step(token, "正在读取蓝牙能力与相机信息")
                featureFlags = try {
                    val feature = read("2009")
                    if (feature.size >= 4) ByteBuffer.wrap(feature).order(ByteOrder.LITTLE_ENDIAN).int else 0
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { 0 }
                val serial = try { cameraSerial(read("200b").toString(Charsets.UTF_8)).orEmpty() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { "" }
                check(generation == token && connectedToken == token && gatt === connection) { "读取相机能力时 BLE 已断开" }
                // 2008 is a clock/location/connection request, not an authentication acknowledgement.
                mutableState.update { if (generation == token) it.copy(authenticated = true, connectedAddress = address, serialNumber = serial,
                    serverName = serverName)
                    .record("BLE 身份握手完成；传图需已配对的经典蓝牙设备") else it }
                Log.i("OpenBridgeBluetooth", "authentication completed")
                outcome.complete(state.value)
            } catch (error: Exception) {
                if (error !is CancellationException) Log.w("OpenBridgeCamera", "BLE handshake failed", error)
                synchronized(this@NikonBle) {
                    if (generation == token) {
                        // Reject callbacks from the failed GATT before closing it, preserving the actual failure on screen.
                        generation++
                        connectedToken = null
                        sessionKey?.fill(0)
                        sessionKey = null
                        if (gatt === ownedGatt) gatt = null
                        mutableState.update { it.copy(authenticated = false, connectedAddress = "", serialNumber = "",
                            imageTransferRequested = false, locationRequested = false).record("蓝牙连接失败：${error.message ?: "操作超时"}") }
                    }
                }
                runCatching { ownedGatt?.disconnect(); ownedGatt?.close() }
                outcome.completeExceptionally(error)
                if (error is CancellationException) throw error
            }
        }
        connectionJob?.invokeOnCompletion { error -> if (error != null) outcome.completeExceptionally(error) }
        return outcome
    }

    private fun step(token: Int, message: String) {
        if (generation != token) return
        Log.i("OpenBridgeBluetooth", "handshake: $message")
        mutableState.update { if (generation == token) it.record(message) else it }
    }

    fun pairingStatus(message: String, active: Boolean, paired: Boolean, address: String = "") {
        mutableState.update { it.copy(pairingInProgress = active, systemPaired = paired, classicAddress = address).record(message) }
    }

    fun connectionMessage(message: String) {
        Log.i("OpenBridgeBluetooth", message)
        mutableState.update { it.record(message) }
    }

    fun offerClassicDevice(device: BleCamera) {
        mutableState.update { it.copy(cameras = (it.cameras.filterNot { old -> old.address == device.address } + device).takeLast(30)) }
    }

    suspend fun prepareSystemPairing() {
        check(state.value.authenticated) { "请先完成 BLE 身份验证" }
        val token = generation
        step(token, "正在登记手机名称")
        val namePayload = bluetoothClientName(Build.MODEL)
        write("2002", namePayload)
        mutableState.update { if (generation == token) it.copy(clientName = namePayload.toString(Charsets.UTF_8).substringBefore('\u0000')) else it }
        val characteristic = gatt?.let(::smartService)?.getCharacteristic(uuid("2006")) ?: return
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) return
        step(token, "正在同步相机时间并准备系统配对")
        try {
            val now = Instant.now()
            val rules = ZoneId.systemDefault().rules
            val offset = rules.getStandardOffset(now).totalSeconds / 60
            val daylight = rules.getDaylightSavings(now).toMinutes().toInt()
            write("2006", NativePtp.currentTime(now.toEpochMilli(), offset, daylight))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.w("OpenBridgeBluetooth", "optional pairing clock update failed", error)
            step(token, "校时未完成，继续系统配对")
        }
    }

    fun reserveWifi() {
        mutableState.update { it.copy(wifiRequested = true, imageTransferRequested = false) }
    }

    fun releaseWifiReservation() {
        mutableState.update { it.copy(wifiRequested = false, imageTransferRequested = it.connectionRequested) }
    }

    suspend fun requestWifi() {
        try {
            check(state.value.authenticated) { "请先完成 BLE 身份验证" }
            check(state.value.systemPaired) { "请先完成系统蓝牙配对，再开启相机 Wi-Fi" }
            write("2005", byteArrayOf(1))
            Log.i("OpenBridgeBluetooth", "Wi-Fi wake request accepted by GATT")
            mutableState.update { it.record("已发送开启 Wi-Fi 请求；请确认机身提示，再连接相机热点") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.w("OpenBridgeBluetooth", "Wi-Fi wake request failed", error)
            mutableState.update { it.record("请求 Wi-Fi 失败：${error.message}") }
            throw error
        }
    }

    fun locationSession(): Int? {
        val current = state.value
        if (!bluetoothDataReady(current.authenticated, current.systemPaired, current.pairingInProgress) ||
            connectedToken != generation || sessionKey == null) return null
        val characteristic = gatt?.let(::smartService)?.getCharacteristic(uuid("2007")) ?: return null
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) return null
        if (featureFlags != 0 && featureFlags and (1 shl 7) == 0) return null
        return generation
    }

    fun transferRole(preference: BluetoothRole): BluetoothRole {
        val role = if (preference != BluetoothRole.AUTOMATIC) preference
            else if (featureFlags and (1 shl 13) != 0) BluetoothRole.WAIT_FOR_CAMERA else BluetoothRole.CONNECT_TO_CAMERA
        Log.i("OpenBridgeBluetooth", "Bluetooth role=$role; features=0x${featureFlags.toString(16)}")
        return role
    }

    suspend fun requestBluetoothTransfer() {
        check(state.value.authenticated) { "请先连接 BLE 控制通道" }
        check(state.value.systemPaired) { "请先完成系统蓝牙配对" }
        check(featureFlags == 0 || featureFlags and (1 shl 5) != 0) { "相机未报告蓝牙图片传输能力" }
        stopScan()
        val control = read("2008")
        if (control.size == 2 && control[1].toInt() and 15 == 1) {
            // Acknowledge the wake-up request while preserving the time/location request nibbles.
            control[1] = (control[1].toInt() and 0xf0).toByte()
            check(write("2008", control) { !state.value.wifiRequested && !state.value.pairingInProgress }) { "蓝牙传输准备已取消" }
        }
        check(write("2005", byteArrayOf(2)) {
            val current = state.value
            !current.wifiRequested && bluetoothDataReady(current.authenticated, current.systemPaired, current.pairingInProgress)
        }) { "正在配对或使用 Wi-Fi，蓝牙数据传输暂候" }
    }

    suspend fun releaseBluetoothTransfer() {
        if (state.value.authenticated && featureFlags and (1 shl 10) != 0) {
            write("2005", byteArrayOf(0x80.toByte())) { !state.value.wifiRequested && !state.value.pairingInProgress }
        }
    }

    fun acknowledgeTransferRequest() { mutableState.update { it.copy(imageTransferRequested = false) } }

    fun pairedDevices(): List<BleCamera> {
        check(adapter != null && adapter.isEnabled) { "请先打开手机蓝牙" }
        return adapter.bondedDevices.map {
            BleCamera(it.address, cameraBluetoothName(it.name).ifBlank { "已配对设备" }, it.type != BluetoothDevice.DEVICE_TYPE_LE)
        }
            .sortedBy { it.name }
    }

    suspend fun updateLocation(payload: ByteArray, expectedSession: Int) {
        require(payload.size == 41)
        check(locationSession() == expectedSession) { "蓝牙位置同步会话已失效" }
        write("2007", payload)
        check(locationSession() == expectedSession) { "蓝牙位置同步时连接已变化" }
    }

    private suspend fun write(id: String, value: ByteArray, allowed: () -> Boolean = { true }): Boolean {
        val connection = gatt ?: error("蓝牙未连接")
        val characteristic = smartService(connection)?.getCharacteristic(uuid(id)) ?: error("相机不支持特征 $id")
        return writeOperation(allowed) {
            val intent = if (id == "2005") " flags=0x${(value.first().toInt() and 255).toString(16)}" else ""
            Log.i("OpenBridgeBluetooth", "GATT write characteristic=$id bytes=${value.size}$intent")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            connection.writeCharacteristic(characteristic)
        }
    }

    private fun completeRead(bytes: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) readReply?.complete(bytes.clone())
        else readReply?.completeExceptionally(IllegalStateException("BLE 读取被拒：$status"))
    }

    private suspend fun read(id: String): ByteArray = writes.withLock {
        val connection = gatt ?: error("蓝牙未连接")
        val characteristic = smartService(connection)?.getCharacteristic(uuid(id)) ?: error("相机缺少特征 $id")
        val completion = CompletableDeferred<ByteArray>()
        readReply = completion
        try {
            check(connection.readCharacteristic(characteristic)) { "BLE 读取提交失败" }
            withTimeoutOrNull(5000) { completion.await() } ?: byteArrayOf()
        } finally { if (readReply === completion) readReply = null }
    }

    private suspend fun writeOperation(allowed: () -> Boolean = { true }, start: () -> Boolean): Boolean = writes.withLock {
        if (!allowed()) return@withLock false
        val completion = CompletableDeferred<Int>()
        reply = completion
        try {
            check(start()) { "BLE 操作提交失败" }
            val status = withTimeout(10000) { completion.await() }
            check(status == BluetoothGatt.GATT_SUCCESS) { "BLE 写入被拒，状态码 $status" }
            true
        } finally { if (reply === completion) reply = null }
    }

    @Synchronized
    fun stopScan() {
        scanGeneration++
        scanJob?.cancel()
        activeScan?.let { callback -> runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) } }
        activeScan = null
        mutableState.update { it.copy(scanning = false) }
    }

    @Synchronized
    fun disconnect() {
        generation++
        connectedToken = null
        featureFlags = 0
        sessionKey?.fill(0)
        sessionKey = null
        reply?.cancel()
        readReply?.cancel()
        connectionJob?.cancel()
        stopScan()
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        mutableState.update { it.copy(authenticated = false, connectedAddress = "", imageTransferRequested = false,
            connectionRequested = false, locationRequested = false, serialNumber = "") }
    }

    fun close() = disconnect()

    private fun smartService(connection: BluetoothGatt): BluetoothGattService? = serviceIds.asSequence()
        .mapNotNull(connection::getService).firstOrNull { it.getCharacteristic(uuid("2000")) != null }

    private fun uuid(short: String): UUID = UUID.fromString("0000$short-3dd4-4255-8d62-6dc7b9bd5561")
}
