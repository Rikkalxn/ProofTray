package org.openbridge.app.camera

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openbridge.app.data.AppSettingsRepository
import org.openbridge.app.data.BluetoothRole
import org.openbridge.app.data.PhotoRepository
import org.openbridge.app.domain.AutoDownloadKind
import org.openbridge.app.domain.NewObjectTracker
import org.openbridge.app.domain.acceptsAutoFormat
import org.openbridge.app.domain.jpegRepresentationKey

internal data class CameraObjectSnapshot(val handles: Set<Long>, val revision: String)

internal interface AutomaticTransferHost {
    val camera: StateFlow<CameraState>
    val ble: NikonBle
    fun wifiSession(): CameraBrowseSession?
    fun clientIdentity(): ByteArray
    suspend fun handles(session: CameraBrowseSession): CameraObjectSnapshot
    suspend fun photo(session: CameraBrowseSession, handle: Long): RemotePhoto?
    suspend fun enqueueOriginals(photos: List<RemotePhoto>)
    suspend fun receiveBluetooth(photo: RemotePhoto, bytes: () -> ByteArray)
    suspend fun reconnectBluetooth(camera: BleCamera)
}

/** Bluetooth checks pending images once on connection, then waits for camera notifications instead of polling. */
internal class CameraAutomation(
    private val context: Context, scope: CoroutineScope, private val settings: AppSettingsRepository, private val host: AutomaticTransferHost
) {
    private data class Policy(
        val enabled: Boolean, val kind: AutoDownloadKind,
        val control: String, val transfer: String, val role: BluetoothRole
    )
    private val mutableState = MutableStateFlow(AutoDownloadState())
    val state = mutableState.asStateFlow()
    private data class ActiveTransfer(val connection: BluetoothLssLink, val manual: Boolean)
    private val bluetooth = AtomicReference<ActiveTransfer?>(null)
    private val bluetoothTransfers = Mutex()
    private val retryVersion = AtomicInteger()
    private val standbyFailures = AtomicInteger()
    private val nextStandbyConnect = AtomicLong()
    private val standbyAllowed = MutableStateFlow(true)
    private var backgroundState = AutoDownloadState()
    private val repository = PhotoRepository(context)
    private class TransferPaused : Exception("下载队列已暂停，请先恢复队列")
    private data class WifiStatus(val connected: Boolean, val connecting: Boolean, val generation: Int)
    private data class BleControl(val ready: Boolean, val address: String, val wifiRequested: Boolean, val allowReconnect: Boolean = true)
    private val bluetoothHandoff = MutableStateFlow<BleControl?>(null)

    init {
        scope.launch {
            val policy = settings.state.map {
                val bluetooth = it.autoKind == AutoDownloadKind.CAMERA_JPEG
                Policy(it.autoDownload, it.autoKind, if (bluetooth) it.controlAddress else "",
                    if (bluetooth) it.transferAddress else "", if (bluetooth) it.bluetoothRole else BluetoothRole.CONNECT_TO_CAMERA)
            }.distinctUntilChanged()
            val connection = host.camera.map { WifiStatus(it.connected, it.connecting, it.generation) }.distinctUntilChanged()
            val control = combine(host.ble.state, bluetoothHandoff, standbyAllowed) { current, handoff, allowed ->
                handoff?.copy(wifiRequested = current.wifiRequested, allowReconnect = allowed) ?: BleControl(
                    bluetoothDataReady(current.authenticated, current.systemPaired, current.pairingInProgress),
                    current.connectedAddress, current.wifiRequested, allowed)
            }
                .distinctUntilChanged()
            combine(policy, connection, control) { value, wifi, ble ->
                val smallJpeg = value.kind == AutoDownloadKind.CAMERA_JPEG
                Triple(value, wifi, ble.takeIf { smallJpeg })
            }.distinctUntilChanged()
                .onEach { cancelAutomaticBluetooth() }
                .collectLatest { (value, wifi, control) ->
                    val controlState = control ?: BleControl(false, "", false)
                    try {
                        when {
                            !value.enabled -> report(false, "自动下载未开启")
                            value.kind == AutoDownloadKind.CAMERA_JPEG -> {
                                if (wifiHasPriority(controlState.wifiRequested, wifi.connecting, wifi.connected)) {
                                    report(false, "Wi-Fi 优先，蓝牙自动传图暂候")
                                } else if (value.control.isBlank() || value.transfer.isBlank()) report(false, "请先关联相机控制和传图设备")
                                else if (!controlState.ready || controlState.address != value.control) {
                                    when {
                                        host.ble.state.value.pairingInProgress -> report(false, "正在恢复相机控制连接")
                                        !controlState.allowReconnect -> report(false, "蓝牙已手动断开，重新连接后恢复自动下载")
                                        else -> watchBluetoothStandby()
                                    }
                                }
                                else watchBluetooth(value)
                            }
                            else -> {
                                val session = host.wifiSession()
                                if (session == null) report(false, "原图自动下载等待 Wi-Fi 连接") else watchWifi(session, value)
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { report(false, "自动下载已停止：${error.message}") }
                    finally { cancelAutomaticBluetooth() }
                }
        }
    }

    fun allowStandbyReconnect(allowed: Boolean) {
        if (allowed) { standbyFailures.set(0); nextStandbyConnect.set(0) }
        standbyAllowed.value = allowed
    }

    private suspend fun watchBluetoothStandby() {
        val name = settings.state.value.controlName
        if (cameraBluetoothName(name).isBlank()) { report(false, "请先连接所选相机以记录名称"); return }
        if (host.ble.pairedDevices().none { it.address == settings.state.value.transferAddress }) {
            report(false, "系统配对已移除，请先手动配对相机")
            return
        }
        while (standbyFailures.get() < 3) {
            delay((nextStandbyConnect.get() - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            report(true, "BLE 已断开，低功耗等待所选相机的待传广播")
            val camera = host.ble.awaitTransferAdvertisement(setOf(name))
            if (host.ble.state.value.pairingInProgress || state.value.checking) return
            val failures = standbyFailures.incrementAndGet()
            nextStandbyConnect.set(SystemClock.elapsedRealtime() + settings.state.value.powerProfile.pollSeconds.coerceAtLeast(30) * 1000L)
            report(true, "相机有待传照片，正在恢复蓝牙连接（$failures/3）")
            host.reconnectBluetooth(camera)
            currentCoroutineContext().ensureActive()
        }
        report(false, "待机重连连续失败，已停止尝试；请点击检查 / 重试")
    }

    @Synchronized
    fun beginManualCheck(): Boolean {
        if (state.value.checking) return false
        retryVersion.incrementAndGet()
        allowStandbyReconnect(true)
        mutableState.value = state.value.copy(checking = true, message = "正在检查蓝牙连接", lastCheckResult = "")
        return true
    }

    @Synchronized
    fun manualProgress(message: String) {
        if (state.value.checking) mutableState.value = state.value.copy(message = message)
    }

    @Synchronized
    fun finishManualCheck(result: String) {
        mutableState.value = backgroundState.copy(lastCheckMillis = System.currentTimeMillis(), lastCheckResult = result)
    }

    suspend fun checkBluetoothOnce(): String {
        val saved = settings.state.value
        val policy = Policy(true, AutoDownloadKind.CAMERA_JPEG, saved.controlAddress, saved.transferAddress, saved.bluetoothRole)
        return checkNotNull(transferBluetoothBatch(policy, manual = true, ::manualProgress))
    }

    fun cancelActiveBluetooth() {
        bluetooth.getAndSet(null)?.connection?.close()
        bluetoothHandoff.value = null
    }

    private fun cancelAutomaticBluetooth() {
        val active = bluetooth.get() ?: return
        if (!active.manual && bluetooth.compareAndSet(active, null)) {
            active.connection.close()
            bluetoothHandoff.value = null
        }
    }

    suspend fun awaitBluetoothIdle() { bluetoothTransfers.withLock { } }

    private suspend fun watchWifi(session: CameraBrowseSession, policy: Policy) {
        val tracker = NewObjectTracker()
        val attempts = mutableMapOf<Long, Int>()
        var retrySoon = false
        report(true, "正在记录已有文件；准备完成后只自动下载新拍照片")
        val initial = host.handles(session)
        tracker.observe(initial.handles, initial.revision)
        report(true, "已就绪：只下载本次连接后新增的原片")
        while (session.link.connected) {
            val waitSeconds = if (retrySoon) 5 else settings.state.value.powerProfile.pollSeconds
            session.link.awaitCatalogChange(waitSeconds * 1000L)
            retrySoon = false
            delay(600) // Merge bursts and allow RAW/JPEG pairs to finish writing.
            currentCoroutineContext().ensureActive()
            if (host.camera.value.paused) { report(true, "自动下载已暂停"); continue }
            val pendingTasks = host.camera.value.tasks.count {
                it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
            }
            if (pendingTasks >= 100) { report(true, "已有 100 项待处理，等待队列腾出空间"); continue }
            val snapshot = host.handles(session)
            val candidates = tracker.observe(snapshot.handles, snapshot.revision)
            if (tracker.baselineReset) { report(true, "相机目录发生移除或更换，已重建基线"); continue }
            val history = repository.importedKeys()
            val batch = mutableListOf<RemotePhoto>()
            for (handle in candidates.take(minOf(10, 100 - pendingTasks))) {
                val photo = try { host.photo(session, handle) }
                catch (error: PtpResponseException) {
                    val count = (attempts[handle] ?: 0) + 1
                    attempts[handle] = count
                    if (error.response in setOf(0x2019, 0x2009) && count <= 3) { retrySoon = true; continue }
                    throw error
                }
                val imported = photo?.asset?.cameraFileKey?.let { it in history } == true
                val supported = photo != null && photo.asset.sizeBytes > 0 && acceptsAutoFormat(photo.asset.format, policy.kind)
                if (supported && !imported) batch += checkNotNull(photo) else tracker.acknowledge(handle)
            }
            if (batch.isNotEmpty()) {
                host.enqueueOriginals(batch)
                batch.forEach { tracker.acknowledge(it.handle) }
            }
            report(true, when {
                retrySoon -> "相机正在写入新照片，稍后重试"
                batch.isEmpty() -> "等待新拍照片"
                else -> "新增 ${batch.size} 项已加入原图下载队列"
            })
        }
    }

    private suspend fun watchBluetooth(policy: Policy) = coroutineScope {
        standbyFailures.set(0)
        val triggers = Channel<Unit>(Channel.CONFLATED)
        val notifications = launch { host.ble.transferRequests.collect { triggers.trySend(Unit) } }
        // A request may predate the notification subscription, especially after manual pairing or reconnecting.
        triggers.trySend(Unit)
        var failures = 0
        var retryAt = 0L
        var version = retryVersion.get()
        var retryJob: Job? = null
        report(true, "蓝牙已就绪，正在检查相机待传照片")
        Log.i("OpenBridgeBluetooth", "automatic check scheduled: control connection ready")
        try {
            for (signal in triggers) {
                if (state.value.checking) continue
                if (version != retryVersion.get()) { version = retryVersion.get(); failures = 0; retryAt = 0 }
                if (failures >= 3) { report(true, "蓝牙传图连续失败，已停止自动尝试；请检查设备关联后手动重试"); continue }
                if (SystemClock.elapsedRealtime() < retryAt) continue
                retryJob?.cancel()
                retryJob = null
                if (host.camera.value.paused) {
                    report(true, "蓝牙自动下载已暂停")
                    host.camera.first { !it.paused }
                }
                try {
                    val result = transferBluetoothBatch(policy, manual = false) { report(true, it) }
                    failures = 0
                    if (result != null) report(true, result)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: TransferPaused) { report(true, "蓝牙已暂停，关闭数据连接并保留待传照片"); triggers.trySend(Unit) }
                catch (error: Exception) {
                    failures++
                    Log.w("OpenBridgeBluetooth", "automatic transfer attempt=$failures failed", error)
                    report(true, "蓝牙传图未完成（$failures/3）：${error.message}")
                    val backoff = settings.state.value.powerProfile.pollSeconds * 1000L
                    retryAt = SystemClock.elapsedRealtime() + backoff
                    if (failures < 3) retryJob = launch { delay(backoff); triggers.trySend(Unit) }
                }
            }
        } finally { notifications.cancel(); retryJob?.cancel(); triggers.close() }
    }

    private suspend fun transferBluetoothBatch(policy: Policy, manual: Boolean, progress: (String) -> Unit): String? = bluetoothTransfers.withLock {
        currentCoroutineContext().ensureActive()
        if (!manual && state.value.checking) return@withLock null
        check(!wifiHasPriority(host.ble.state.value.wifiRequested, host.camera.value.connecting, host.camera.value.connected)) {
            "Wi-Fi 正在连接或传输，请断开 Wi-Fi 会话后重试蓝牙"
        }
        val control = host.ble.state.value
        check(bluetoothDataReady(control.authenticated, control.systemPaired, control.pairingInProgress) &&
            control.connectedAddress == policy.control) { "所选相机的蓝牙控制连接尚未就绪" }
        check(policy.transfer.isNotBlank()) { "请先关联已配对的蓝牙传图设备" }
        if (host.camera.value.paused) throw TransferPaused()
        val power = context.getSystemService(PowerManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenBridge:bluetooth-transfer")
        wake.acquire(10 * 60 * 1000L)
        val connection = BluetoothLssLink(context, policy.transfer, host.ble.transferRole(policy.role))
        val active = ActiveTransfer(connection, manual)
        bluetooth.set(active)
        var handedOff = false
        try {
            progress("正在建立蓝牙数据通道并读取待传列表")
            connection.prepare()
            host.ble.requestBluetoothTransfer()
            currentCoroutineContext().ensureActive()
            if (needsBluetoothHandoff(setOf(control.cameraName, control.serverName))) {
                bluetoothHandoff.value = BleControl(true, control.connectedAddress, false)
                handedOff = true
                Log.i("OpenBridgeBluetooth", "handing BLE control over to classic data connection")
                host.ble.disconnect()
                host.ble.connectionMessage("BLE 控制已释放，正在通过经典蓝牙传图")
            }
            connection.connect(host.clientIdentity())
            val dataSerial = cameraSerial(connection.device.optString("serial"))
            val controlSerial = cameraSerial(control.serialNumber)
            check(cameraSerialMatches(dataSerial, controlSerial)) { "传图设备与 BLE 控制设备的机身序列号不同，请检查关联" }
            connection.startAutomatic()
            val model = connection.device.optString("model")
            val source = dataSerial?.let { "$model:$it" } ?: "bluetooth:${policy.transfer}"
            val history = repository.importedKeys().toMutableSet()
            val selection = connection.pendingImages()
            val marked = selection.handles.toSet()
            var allSupported = !selection.hasUnsupportedEntries
            var downloaded = 0
            val waiting = host.camera.value.tasks.filter {
                it.channel == DownloadChannel.BLUETOOTH && it.status == DownloadStatus.WAITING && it.photo.cameraIdentity == source
            }.associateBy { it.photo.handle }
            val handles = (marked + waiting.keys).toList()
            Log.i("OpenBridgeBluetooth", "pending images=${handles.size}; unsupported=${selection.hasUnsupportedEntries}; manual=$manual")
            for ((index, handle) in handles.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (host.camera.value.paused) throw TransferPaused()
                val photo = cameraPhoto(connection.objectInfo(handle), handle, source, model)
                if (photo == null) { allSupported = false; continue }
                val previous = waiting[handle]?.photo
                if (handle !in marked && previous != null && (previous.asset.name != photo.asset.name ||
                        previous.asset.sizeBytes != photo.asset.sizeBytes || previous.captured != photo.captured)) {
                    error("待重试照片的句柄已改变，请连接 Wi-Fi 重新确认文件")
                }
                val key = jpegRepresentationKey(photo.asset)
                if (key in history) continue
                val cancelled = host.camera.value.tasks.any {
                    it.channel == DownloadChannel.BLUETOOTH && it.status == DownloadStatus.CANCELLED &&
                        jpegRepresentationKey(it.photo.asset) == key
                }
                if (cancelled) { allSupported = false; continue }
                progress("蓝牙便携 JPEG：${index + 1} / ${handles.size}")
                host.receiveBluetooth(photo) { connection.portableJpeg(handle) }
                history += key
                downloaded++
            }
            if (allSupported) connection.finishAutomatic()
            host.ble.acknowledgeTransferRequest()
            val result = when {
                !allSupported -> "本批下载 $downloaded 张；其他照片保留在相机待传列表"
                handles.isEmpty() -> "相机待传列表为空；新照片需在相机启用自动选择发送"
                downloaded == 0 -> "待传照片已导入，无需重复下载；已回到 BLE 待机"
                else -> "本批下载 $downloaded 张便携 JPEG，已回到 BLE 待机"
            }
            report(settings.state.value.autoDownload, result)
            result
        } finally {
            connection.close()
            try {
                if (handedOff && currentCoroutineContext().isActive && bluetooth.get() === active && !host.ble.state.value.wifiRequested) {
                    progress("蓝牙数据通道已关闭，正在恢复 BLE 待机")
                    delay(600)
                    restoreBluetoothStandby(control)
                    Log.i("OpenBridgeBluetooth", "BLE standby restored after data transfer")
                }
                // Closing RFCOMM ended the handed-off session. Releasing on the restored GATT link would disconnect standby again.
                if (!handedOff && host.wifiSession() == null) runCatching { host.ble.releaseBluetoothTransfer() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w("OpenBridgeBluetooth", "BLE standby recovery failed", error) }
            finally {
                bluetooth.compareAndSet(active, null)
                if (handedOff) bluetoothHandoff.value = null
                if (wake.isHeld) wake.release()
            }
        }
    }

    private suspend fun restoreBluetoothStandby(previous: BleState) {
        var address = previous.connectedAddress
        val names = setOf(previous.cameraName, previous.serverName).filter(String::isNotBlank).toSet()
        for (attempt in 0..1) {
            try {
                val restored = host.ble.connect(address, preserveHistory = true).await()
                check(cameraSerialMatches(cameraSerial(restored.serialNumber), cameraSerial(previous.serialNumber))) {
                    "恢复待机时相机身份不一致，请重新关联"
                }
                if (address != settings.state.value.controlAddress) settings.update { it.copy(controlAddress = address) }
                return
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (attempt != 0 || names.isEmpty() || !retryableBleFailure(error)) throw error
                delay(1000)
                address = host.ble.rediscover(names, address).address
            }
        }
    }

    @Synchronized
    private fun report(watching: Boolean, message: String) {
        backgroundState = AutoDownloadState(watching, message, System.currentTimeMillis())
        if (!state.value.checking) mutableState.value = backgroundState.copy(lastCheckResult = state.value.lastCheckResult)
    }
}
