package org.openbridge.app.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.Network
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.Base64
import java.util.UUID
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openbridge.app.data.PhotoRepository
import org.openbridge.app.data.GalleryPublisher
import org.openbridge.app.data.sourceGeometry
import org.openbridge.app.data.AppSettingsRepository
import org.openbridge.app.domain.DownloadVariant
import org.openbridge.app.domain.AutoDownloadKind
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.photoMimeType
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.SortOrder

class CameraController private constructor(private val context: Context) {
    private val browsingPreferences = context.getSharedPreferences("camera-browser", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(CameraState(
        catalogBatchSize = CatalogBatchSize.fromPreference(browsingPreferences.all["batchSize"])
    ))
    val state = mutableState.asStateFlow()
    @Volatile private var catalogSort = SortOrder.NEWEST
    private val errors = CoroutineExceptionHandler { _, error ->
        Log.e("OpenBridgeCamera", "controller failure", error)
        mutableState.update { it.copy(busy = false, connecting = false, message = error.message ?: "相机任务失败") }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    private val network = CameraNetwork(context, scope)
    internal val ble = NikonBle(context, scope)
    private val sony = SonyControl(context) { ready -> mutableState.update { it.copy(sonyControlConnected = ready) } }
    val bleState = ble.state
    val endpoints = network.endpoints
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val catalogChanged = changed.asSharedFlow()
    private val repository = PhotoRepository.get(context)
    private val gallery = GalleryPublisher(context)
    private val settings = AppSettingsRepository.get(context)
    private val bluetoothPairing: BluetoothPairingCoordinator by lazy {
        BluetoothPairingCoordinator(context, scope, ble, settings) {
            automatic.cancelActiveBluetooth()
            automatic.awaitBluetoothIdle()
        }
    }
    private val jpegSaver = CameraJpegSaver(context)
    private val journal = CameraJournal(context)
    private val requests = Mutex()
    private val fileReceiver = CameraFileReceiver(context, requests, ::active)
    private val taskLock = Mutex()
    private val remote get() = browser.photos
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var link: PtpLink? = null
    @Volatile private var connectionGeneration = 0
    private var connectionJob: Job? = null
    private var wifiPreferenceJob: Job? = null
    private data class WifiPlan(val connection: CameraConnection, val expectedSerial: String? = null,
                                val route: Network? = null, val sonyId: UUID? = null)
    private var retryWifi: (() -> Unit)? = null
    private var manualBluetoothJob: Job? = null
    private var identity = ""
    private val browser = CameraCatalogBrowser(context, scope, requests, ::browseSession, ::catalogProgress) { changed.emit(Unit) }
    private val locationWrites = Mutex()
    private var locationTarget: Pair<Boolean, Int>? = null
    private val automatic: CameraAutomation = CameraAutomation(context, scope, settings, object : AutomaticTransferHost {
        override val camera = state
        override val ble = this@CameraController.ble
        override fun wifiSession() = browseSession()
        override fun clientIdentity(): ByteArray {
            val encoded = context.getSharedPreferences("camera-identity", Context.MODE_PRIVATE).getString("bleIdentity", null)
                ?: error("请先完成本应用的 BLE 配对")
            return Base64.decode(encoded, Base64.DEFAULT)
        }
        override suspend fun handles(session: CameraBrowseSession): CameraObjectSnapshot {
            suspend fun <T> request(read: (PtpLink) -> T): T = requests.withLock {
                check(browseSession()?.generation == session.generation) { "相机会话已变化" }
                read(session.link)
            }
            val storages = request { it.storageIds() }.sorted()
            val handles = mutableSetOf<Long>()
            for (storage in storages) {
                val cursor = request { it.sonyCatalogCursor(storage) }
                if (cursor == null) handles.addAll(request { it.objectHandles(storage) }.asList())
                else while (cursor.hasMore) handles.addAll(request { it.nextSonyPage(cursor) })
            }
            return CameraObjectSnapshot(handles, "${session.link.catalogRevision}:${storages.joinToString(",")}")
        }
        override suspend fun photo(session: CameraBrowseSession, handle: Long): RemotePhoto? = requests.withLock {
            check(browseSession()?.generation == session.generation) { "相机会话已变化" }
            cameraPhoto(session.link.objectInfo(handle), handle, session) { null }
        }
        override suspend fun enqueueOriginals(photos: List<RemotePhoto>) {
            photos.forEach { remote[it.asset.id] = it }
            enqueuePhotos(photos, DownloadVariant.ORIGINAL, true)
        }
        override suspend fun receiveBluetooth(photo: RemotePhoto, bytes: () -> ByteArray) = receiveBluetoothCopy(photo, bytes)
        override suspend fun reconnectBluetooth(camera: BleCamera) {
            currentCoroutineContext().ensureActive()
            val job = synchronized(this@CameraController) {
                if (!automatic.canReconnectAutomatically() || !matchesCameraName(camera.name, setOf(settings.state.value.controlName))) {
                    throw CancellationException("相机或连接意图已变化")
                }
                check(ble.pairedDevices().any { it.classic && it.address == settings.state.value.transferAddress }) {
                    "系统配对已移除，请在前台重新配对相机"
                }
                CameraService.start(context)
                bluetoothPairing.connect(camera, initializePairing = false, allowSystemPairing = false)
            }
            job.join()
        }
    })
    val autoState = automatic.state

    init {
        scope.launch {
            taskLock.withLock {
                val recovered = journal.load().map { task ->
                    if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED) {
                        task.copy(status = DownloadStatus.WAITING, message = "上次任务中断，连接同一相机并刷新目录后重试")
                    } else task
                }
                mutableState.update { it.copy(tasks = recovered) }
                journal.save(recovered)
            }
            val originals = state.value.tasks.filter { it.status == DownloadStatus.COMPLETED && it.variant == DownloadVariant.ORIGINAL }
                .mapNotNull { it.outputUri?.let(Uri::parse) }
            scope.launch { gallery.restoreIndexOnce("camera-originals-v1", originals) }
            for (signal in wake) {
                while (state.value.connected && !state.value.paused) {
                    val task = state.value.tasks.firstOrNull { it.status == DownloadStatus.QUEUED && it.channel == DownloadChannel.WIFI } ?: break
                    try { download(task) } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableState.update { it.copy(paused = true, message = "任务记录保存失败，队列已暂停：${error.message}") }
                    }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(settings.state.value.powerProfile.pollSeconds.coerceAtLeast(15) * 1000L)
                if (state.value.connected) {
                    val generation = connectionGeneration
                    runCatching { requests.withLock {
                        check(generation == connectionGeneration) { "相机会话已更换" }
                        if (state.value.tasks.none { it.status == DownloadStatus.RUNNING && it.channel == DownloadChannel.WIFI }) active().ping()
                    } }.onFailure { error ->
                        if (error is CancellationException) throw error
                        connectionLost(generation, error)
                    }
                }
            }
        }
    }

    @Synchronized
    fun connect(config: CameraConnection) {
        retryWifi = { connect(config) }
        val selected = settings.state.value
        if (selected.sonySelected && !config.sonyCamera) {
            startWifiConnection(ConnectionPhase.BLUETOOTH) { WifiPlan(sony.connection(selected.sonyAddress, config)) }
        } else startWifiConnection(ConnectionPhase.PHONE_WIFI) { WifiPlan(config) }
    }

    fun retryConnection() { retryWifi?.invoke() }

    @Synchronized
    private fun startWifiConnection(initialPhase: ConnectionPhase, prepare: suspend ((ConnectionPhase, String) -> Unit) -> WifiPlan) {
        CameraService.start(context)
        preferWifi()
        disconnectWifi()
        val generation = connectionGeneration
        mutableState.update { it.copy(connecting = true, busy = true, connectionPhase = initialPhase, manuallyDisconnected = false,
            protocolDiagnostic = null, bluetoothAssisted = initialPhase == ConnectionPhase.BLUETOOTH, stage = "正在准备相机连接", message = null) }
        connectionJob = scope.launch {
            var attempt: PtpLink? = null
            fun progress(phase: ConnectionPhase, message: String) {
                synchronized(this@CameraController) {
                    if (generation != connectionGeneration) throw CancellationException("连接操作已取消")
                    mutableState.update { it.copy(connectionPhase = phase, stage = message) }
                }
            }
            val lost: (Throwable) -> Unit = { cause -> scope.launch { connectionLost(generation, cause) } }
            try {
                automatic.awaitBluetoothIdle()
                val plan = prepare(::progress)
                val config = plan.connection
                currentCoroutineContext().ensureActive()
                progress(ConnectionPhase.PHONE_WIFI, if (config.ssid.isNotEmpty()) "正在连接热点，出现系统提示时请确认" else "正在检查手机 Wi-Fi")
                val managedHotspot = config.mode == CameraMode.AP && config.ssid.isNotEmpty()
                val route = plan.route ?: if (managedHotspot) network.requestAp(config.ssid, config.password, config.security, lost, config.bssid)
                    else network.wifi()
                currentCoroutineContext().ensureActive()
                synchronized(this@CameraController) {
                    if (generation != connectionGeneration) throw CancellationException("连接操作已取消")
                    if (!managedHotspot) network.watch(route, lost)
                }
                network.awaitAddress(route)
                val host = config.host.trim().ifEmpty {
                    check(config.mode == CameraMode.AP) { "STA 模式请填相机 IP，或从发现列表选择相机" }
                    network.hotspotAddress(route, preferDhcp = !config.sonyCamera)
                }
                progress(ConnectionPhase.PTP, "Wi-Fi 已就绪，正在建立相机传图会话")
                val preferences = context.getSharedPreferences("camera-identity", Context.MODE_PRIVATE)
                val guid = preferences.getString("ptpGuid", null)?.let(UUID::fromString) ?: UUID.randomUUID().also {
                    preferences.edit().putString("ptpGuid", it.toString()).apply()
                }
                val expectedMake = when {
                    config.sonyCamera -> CameraMake.SONY
                    initialPhase == ConnectionPhase.BLUETOOTH -> CameraMake.NIKON
                    else -> CameraMake.OTHER
                }
                val session = PtpLink(route, host, config.port, lost, config.sonyAccess, expectedMake) { detail ->
                    mutableState.update { if (it.generation == generation) it.copy(protocolDiagnostic = detail) else it }
                }
                attempt = session
                synchronized(this@CameraController) {
                    if (generation != connectionGeneration) throw CancellationException("连接操作已取消")
                    link = session
                }
                requests.withLock { session.connect(guid) }
                currentCoroutineContext().ensureActive()
                val device = session.device
                val serial = cameraSerial(device.optString("serial"))
                check(cameraSerialMatches(serial, plan.expectedSerial)) { "Wi-Fi 相机与已连接的蓝牙相机不一致，已停止连接" }
                val cameraIdentity = serial?.let { "${device.optString("model")}:$it" } ?: config.sonyAccess?.cameraKey
                    ?: plan.sonyId?.let { "sony:$it" } ?: "session:${UUID.randomUUID()}"
                browser.resetSession()
                synchronized(this@CameraController) {
                    check(generation == connectionGeneration && session.connected) { "连接已取消或会话已结束" }
                    identity = cameraIdentity
                    mutableState.update { it.copy(connected = true, connecting = false, busy = false, connectionPhase = ConnectionPhase.READY,
                        cameraName = device.optString("model"), make = session.make, stage = "已连接，可导入原片", sourceId = identity,
                        generation = generation, indexed = 0, total = 0, hasMore = false, catalogIds = emptySet()) }
                }
                Log.i("OpenBridgeCamera", "connect stage=ready; model=${device.optString("model")}; firmware=${device.optString("version")}")
                refresh()
            } catch (error: Exception) {
                if (error !is CancellationException) Log.w("OpenBridgeCamera", "connect failed", error)
                attempt?.close()
                synchronized(this@CameraController) {
                    if (generation == connectionGeneration) {
                        network.release()
                        link = null
                        mutableState.update { current ->
                            if (error is CancellationException) current.disconnected(generation)
                            else current.disconnected(generation, error.message ?: "连接失败").copy(stage = "连接未完成")
                        }
                        releaseWifiPreference()
                    }
                }
                if (error is CancellationException) throw error
            }
        }
    }

    @Synchronized
    fun disconnect() {
        retryWifi = null
        releaseWifiPreference()
        disconnectWifi()
        disconnectControl()
    }

    @Synchronized
    private fun connectionLost(generation: Int, error: Throwable) {
        if (generation != connectionGeneration || (!state.value.connected && !state.value.connecting)) return
        if (state.value.connectionPhase == ConnectionPhase.DISCONNECTING) return
        Log.w("OpenBridgeCamera", "connection lost; releasing connection and catalog work", error)
        disconnectWifi(if (error is PtpResponseException) error.message else "与相机的 Wi-Fi 连接已断开，请重新连接后重试")
        releaseWifiPreference()
    }

    @Synchronized
    private fun disconnectWifi(reason: String? = null) {
        connectionGeneration++
        connectionJob?.cancel()
        browser.stop()
        link?.close()
        link = null
        network.release()
        network.stopDiscovery()
        mutableState.update { it.disconnected(connectionGeneration, reason) }
    }

    @Synchronized
    fun scanBle() {
        mutableState.update { it.copy(manuallyDisconnected = false) }
        CameraService.start(context); automatic.cancelActiveBluetooth(); ble.scan(includeSony = true)
    }
    @Synchronized
    fun stopBleScan() {
        ble.stopScan()
        ble.connectionMessage(if (ble.state.value.authenticated) "已停止搜索，蓝牙控制仍连接" else "已停止搜索")
    }
    @Synchronized
    fun connectBle(camera: BleCamera) {
        mutableState.update { it.copy(manuallyDisconnected = false) }
        CameraService.start(context)
        if (camera.make == CameraMake.SONY) { pairSony(camera); return }
        sony.close()
        val previous = settings.state.value
        if (previous.sonySelected) { disconnectWifi(); settings.update { it.copy(sonySelected = false) } }
        val switchingCamera = previous.controlName.isNotBlank() && !matchesCameraName(camera.name, setOf(previous.controlName))
        bluetoothPairing.cancel()
        if (switchingCamera) {
            releaseWifiPreference()
            disconnectWifi()
            retryWifi = null
        }
        automatic.allowStandbyReconnect(true)
        releaseStaleWifiPreference()
        val known = settings.state.value.transferAddress.isNotBlank() && matchesCameraName(camera.name, setOf(settings.state.value.controlName))
        if (!camera.classic) settings.update { it.copy(controlAddress = camera.address, controlName = camera.name) }
        bluetoothPairing.connect(camera, initializePairing = !known || camera.classic)
    }
    private fun pairSony(camera: BleCamera) {
        disconnect()
        ble.stopScan()
        val generation = connectionGeneration
        retryWifi = { connectBle(camera) }
        mutableState.update { it.copy(connecting = true, busy = true, connectionPhase = ConnectionPhase.BLUETOOTH,
            stage = "请在手机和索尼机身上确认配对", message = null) }
        connectionJob = scope.launch {
            try {
                automatic.awaitBluetoothIdle()
                sony.pair(camera.address)
                currentCoroutineContext().ensureActive()
                synchronized(this@CameraController) {
                    if (generation != connectionGeneration) return@launch
                    settings.update { it.copy(sonySelected = true, sonyAddress = camera.address, sonyName = camera.name) }
                    mutableState.update { it.copy(connecting = false, busy = false, connectionPhase = ConnectionPhase.IDLE,
                        stage = "索尼相机已配对，可以导入照片", message = null) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                synchronized(this@CameraController) {
                    if (generation != connectionGeneration) return@launch
                    sony.close()
                    mutableState.update { it.disconnected(generation, error.message ?: "索尼配对失败，请重新打开机身配对界面") }
                }
            }
        }
    }

    @Synchronized
    fun discoverClassic() {
        if (settings.state.value.sonySelected) return
        mutableState.update { it.copy(manuallyDisconnected = false) }
        automatic.allowStandbyReconnect(true)
        if (settings.state.value.controlAddress.isBlank()) ble.pairingStatus("请先搜索并选择相机的 BLE 控制设备", false, false)
        else {
            CameraService.start(context)
            releaseStaleWifiPreference()
            val saved = settings.state.value
            bluetoothPairing.connect(BleCamera(saved.controlAddress, saved.controlName), rediscover = true, initializePairing = true)
        }
    }
    @Synchronized
    fun wakeWifi() {
        retryWifi = { wakeWifi() }
        val selected = settings.state.value
        if (selected.sonySelected) {
            startWifiConnection(ConnectionPhase.BLUETOOTH) { progress ->
                progress(ConnectionPhase.BLUETOOTH, "正在查找已配对的索尼相机")
                val id = sony.networkId(selected.sonyAddress)
                val lan = id?.let { network.findPairedSony(it) }
                if (lan != null) {
                    val config = sony.connection(selected.sonyAddress, CameraConnection(mode = CameraMode.STA, host = lan.second))
                    return@startWifiConnection WifiPlan(config, route = lan.first, sonyId = id)
                }
                progress(ConnectionPhase.CAMERA_WIFI, "正在通过蓝牙开启索尼 Wi-Fi")
                val config = sony.connection(selected.sonyAddress)
                WifiPlan(config.copy(security = network.hotspotSecurity(config.ssid, config.bssid)), sonyId = id)
            }
            return
        }
        automatic.allowStandbyReconnect(true)
        startWifiConnection(ConnectionPhase.BLUETOOTH) { progress ->
            progress(ConnectionPhase.BLUETOOTH, "正在连接已保存的相机")
            bluetoothPairing.awaitCurrent()
            val saved = settings.state.value
            check(saved.controlAddress.isNotBlank()) { "请先搜索并配对相机，再连接相机 Wi-Fi" }
            val current = ble.state.value
            if (!bluetoothDataReady(current.authenticated, current.systemPaired, current.pairingInProgress) ||
                current.connectedAddress != saved.controlAddress) {
                bluetoothPairing.connect(BleCamera(saved.controlAddress, saved.controlName), rediscover = true, initializePairing = false).join()
            }
            currentCoroutineContext().ensureActive()
            val control = ble.state.value
            check(bluetoothDataReady(control.authenticated, control.systemPaired, control.pairingInProgress)) { control.message }
            progress(ConnectionPhase.CAMERA_WIFI, "蓝牙已就绪，正在读取相机热点配置")
            val serial = cameraSerial(control.serialNumber)
            val wifi = ble.startCameraHotspot { settings ->
                network.checkApSecurity(settings.security)
                progress(ConnectionPhase.CAMERA_WIFI, "正在通过蓝牙开启机身 Wi-Fi 热点")
            }
            if (needsBluetoothHandoff(setOf(control.cameraName, control.serverName))) {
                ble.disconnect()
                ble.connectionMessage("正在切换到相机 Wi-Fi")
            }
            WifiPlan(CameraConnection(ssid = wifi.ssid, password = wifi.password, security = wifi.security), serial)
        }
    }

    fun disconnectWifiSession() = endConnection(all = false)
    fun disconnectByUser() = endConnection(all = true)

    @Synchronized private fun endConnection(all: Boolean) {
        if (state.value.connectionPhase == ConnectionPhase.DISCONNECTING) return
        if (all) {
            retryWifi = null
            automatic.allowStandbyReconnect(false)
            mutableState.update { it.copy(manuallyDisconnected = true) }
        }
        fun finish() {
            if (all) {
                disconnect()
                mutableState.update { it.copy(stage = "相机已断开", manuallyDisconnected = true) }
            } else {
                disconnectWifi()
                releaseWifiPreference()
            }
        }
        if (state.value.connecting) { if (all) finish() else disconnect(); return }
        val session = link
        if (session?.make == CameraMake.SONY && session.connected) {
            val generation = connectionGeneration
            mutableState.update { it.copy(connecting = true, connectionPhase = ConnectionPhase.DISCONNECTING, stage = "正在断开相机") }
            val deadline = scope.launch { delay(1800); session.close() }
            connectionJob = scope.launch {
                try { requests.withLock { session.finishSonyImport() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Closing the transport also terminates the session if the camera has already left Wi-Fi. */ }
                finally {
                    deadline.cancel()
                    synchronized(this@CameraController) {
                        if (generation == connectionGeneration) finish()
                    }
                }
            }
            return
        }
        finish()
        if (all) return
        val options = settings.state.value
        if (options.sonySelected) return
        val control = ble.state.value
        if (options.controlAddress.isNotBlank() &&
            ((options.autoDownload && options.autoKind == AutoDownloadKind.CAMERA_JPEG) || options.locationSync) &&
            !bluetoothDataReady(control.authenticated, control.systemPaired, control.pairingInProgress)) connectSavedControl()
    }

    @Synchronized
    private fun preferWifi() {
        manualBluetoothJob?.cancel()
        wifiPreferenceJob?.cancel()
        ble.reserveWifi()
        automatic.cancelActiveBluetooth()
        wifiPreferenceJob = scope.launch {
            val owner = currentCoroutineContext()[Job]
            delay(5 * 60 * 1000L)
            synchronized(this@CameraController) {
                if (wifiPreferenceJob === owner && !state.value.connected && !state.value.connecting) {
                    ble.releaseWifiReservation()
                    ble.connectionMessage("等待 Wi-Fi 连接超时，已恢复蓝牙待机")
                }
            }
        }
    }

    @Synchronized
    private fun releaseWifiPreference() {
        wifiPreferenceJob?.cancel()
        wifiPreferenceJob = null
        ble.releaseWifiReservation()
    }

    @Synchronized
    private fun releaseStaleWifiPreference() {
        if (!state.value.connected && !state.value.connecting) releaseWifiPreference()
    }

    fun discoverSta() { network.discover() }
    fun pairedDevices(): List<BleCamera> = ble.pairedDevices()
    @Synchronized
    fun disconnectControl() {
        sony.close()
        automatic.allowStandbyReconnect(false)
        manualBluetoothJob?.cancel()
        bluetoothPairing.cancel()
        ble.disconnect()
        automatic.cancelActiveBluetooth()
    }

    @Synchronized
    fun checkBluetoothTransfers() {
        if (settings.state.value.sonySelected) return
        if (!automatic.beginManualCheck()) return
        mutableState.update { it.copy(manuallyDisconnected = false) }
        manualBluetoothJob = scope.launch {
            var result = "蓝牙检查已取消"
            try {
                CameraService.start(context)
                synchronized(this@CameraController) {
                    check(!state.value.connected && !state.value.connecting) { "Wi-Fi 正在连接或传输，请先断开 Wi-Fi 会话" }
                    releaseWifiPreference()
                }
                automatic.manualProgress("正在检查已关联相机的控制连接")
                bluetoothPairing.awaitCurrent()
                val saved = settings.state.value
                check(saved.controlAddress.isNotBlank()) { "请先在设备设置中选择相机的 BLE 控制设备" }
                val control = ble.state.value
                if (!bluetoothDataReady(control.authenticated, control.systemPaired, control.pairingInProgress) ||
                    control.connectedAddress != saved.controlAddress) {
                    automatic.manualProgress("正在重新发现并连接已关联相机")
                    bluetoothPairing.connect(BleCamera(saved.controlAddress, saved.controlName),
                        rediscover = true, initializePairing = false).join()
                }
                currentCoroutineContext().ensureActive()
                val connected = ble.state.value
                check(bluetoothDataReady(connected.authenticated, connected.systemPaired, connected.pairingInProgress)) {
                    connected.message
                }
                automatic.manualProgress("正在等待蓝牙通道并检查待传照片")
                result = automatic.checkBluetoothOnce()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w("OpenBridgeBluetooth", "manual transfer check failed", error)
                result = "检查未完成：${error.message ?: "请重试"}"
            } finally { automatic.finishManualCheck(result) }
        }
    }

    @Synchronized
    fun switchToNikonBluetooth() {
        val saved = settings.state.value
        if (saved.sonySelected || saved.controlAddress.isBlank()) return
        val retry = state.value.connectionPhase == ConnectionPhase.FAILED
        disconnectWifi()
        releaseWifiPreference()
        retryWifi = null
        mutableState.update { it.copy(manuallyDisconnected = false) }
        val control = ble.state.value
        val ready = bluetoothDataReady(control.authenticated, control.systemPaired, control.pairingInProgress)
        if (!retry && ready && control.connectedAddress == saved.controlAddress) {
            CameraService.start(context)
            automatic.allowStandbyReconnect(true)
        } else connectSavedControl()
    }

    @Synchronized
    fun connectSavedControl() {
        if (settings.state.value.sonySelected) return
        mutableState.update { it.copy(manuallyDisconnected = false) }
        val address = settings.state.value.controlAddress
        check(address.isNotBlank()) { "请先选择 BLE 控制设备" }
        CameraService.start(context)
        automatic.allowStandbyReconnect(true)
        releaseStaleWifiPreference()
        bluetoothPairing.connect(BleCamera(address, settings.state.value.controlName), rediscover = true, initializePairing = false)
    }

    fun resumeAutomaticDownloads() {
        mutableState.update { it.copy(manuallyDisconnected = false) }
        CameraService.start(context)
        automatic.allowStandbyReconnect(true)
    }

    fun canSyncLocation(): Boolean = (link?.connected == true && link?.supportsLocation() == true) || ble.locationSession() != null
    fun locationRequested(): Boolean = (link?.connected == true && link?.supportsLocation() == true) || ble.state.value.locationRequested

    suspend fun syncLocation(payload: ByteArray): String = locationWrites.withLock {
        val bleSession = ble.locationSession()
        if (bleSession != null) {
            ble.updateLocation(payload, bleSession)
            locationTarget = false to bleSession
            "BLE"
        } else requests.withLock {
            val session = active()
            session.updateLocation(payload)
            locationTarget = true to connectionGeneration
            "Wi-Fi"
        }
    }

    suspend fun clearLocation() = locationWrites.withLock {
        val target = locationTarget ?: return@withLock
        val payload = NativePtp.location(0.0, 0.0, Double.NaN, 0, 0, false)
        if (target.first) requests.withLock {
            if (target.second == connectionGeneration && link?.connected == true) active().updateLocation(payload)
        } else if (ble.locationSession() == target.second) ble.updateLocation(payload, target.second)
        locationTarget = null
    }

    fun beginRegistration() {
        scope.launch {
            try {
                val code = requests.withLock { active().registrationCode() }
                mutableState.update { it.copy(pairingCode = code, message = "请核对机身上的配对码，并在机身确认主机") }
            } catch (error: Exception) { mutableState.update { it.copy(message = error.message) } }
        }
    }

    fun confirmRegistration() {
        if (state.value.pairingCode == null) return
        scope.launch {
            try {
                requests.withLock { active().confirmRegistration() }
                mutableState.update { it.copy(pairingCode = null, message = "相机已确认主机登记，身份会在后续连接中复用") }
                refresh()
            } catch (error: Exception) { mutableState.update { it.copy(message = error.message) } }
        }
    }

    @Synchronized fun setCatalogSort(order: SortOrder) {
        if (catalogSort == order) return
        catalogSort = order
        browser.refresh(state.value.catalogBatchSize, order.catalogDirection(), sortChanged = true)
    }

    fun refresh() = browser.refresh(state.value.catalogBatchSize, catalogSort.catalogDirection())
    fun loadMore() = browser.loadMore(state.value.catalogBatchSize, catalogSort.catalogDirection())
    fun autoLoadMore(readId: Long) {
        val camera = state.value
        if (camera.canAutoLoadCatalog && camera.catalogReadId == readId) {
            browser.loadMore(camera.catalogBatchSize, catalogSort.catalogDirection(), readId)
        }
    }
    fun stopCatalogRead() = browser.stopReading()
    fun jumpToDay(day: SonyCaptureDay) {
        val camera = state.value
        if (camera.connected && !camera.connecting && day in camera.catalogDates) {
            browser.refresh(camera.catalogBatchSize, catalogSort.catalogDirection(), day)
        }
    }
    fun requestPreview(id: String, detailed: Boolean): () -> Unit = browser.requestPreview(id, detailed)

    fun setCatalogBatchSize(size: Int?) {
        require(CatalogBatchSize.valid(size)) { "请输入 1–5000 的整数，或选择不限" }
        browsingPreferences.edit().apply {
            if (size == null) putString("batchSize", CatalogBatchSize.UNLIMITED_PREF) else putInt("batchSize", size)
        }.apply()
        mutableState.update { it.copy(catalogBatchSize = size) }
    }

    suspend fun clearImportList(selectedIds: Set<String>? = null): Unit = withContext(Dispatchers.IO) {
        val source = state.value.sourceId.takeIf { state.value.connected }
        val pending = state.value.tasks.any {
            it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING) &&
                (source == null || it.photo.cameraIdentity == source)
                && (selectedIds == null || it.photo.asset.id in selectedIds)
        }
        check(!pending) { "请先完成或取消待传输任务，再清空选片列表" }
        browser.clearImportList(source, selectedIds)
    }

    private fun browseSession(): CameraBrowseSession? {
        val camera = state.value
        val session = link?.takeIf { it.connected && camera.connected && !camera.connecting } ?: return null
        return CameraBrowseSession(session, identity, state.value.cameraName, connectionGeneration)
    }

    private fun catalogProgress(progress: BrowseProgress) {
        mutableState.update { it.withCatalogProgress(progress) }
        if (!progress.busy && state.value.connected && progress.generation == state.value.generation) wake.trySend(Unit)
    }

    suspend fun enqueue(ids: Set<String>): Unit = withContext(Dispatchers.IO) {
        check(state.value.connected) { "请先连接相机" }
        val photos = ids.mapNotNull { remote[it] }
        check(photos.size == ids.size) { "请连接原相机并刷新目录后再添加导入" }
        val variant = if (active().make == CameraMake.NIKON) settings.state.value.manualVariant else DownloadVariant.ORIGINAL
        enqueuePhotos(photos, variant, false)
    }

    private suspend fun enqueuePhotos(photos: List<RemotePhoto>, variant: DownloadVariant, automatic: Boolean) {
        var queued = emptyList<CameraDownload>()
        taskLock.withLock {
            val existing = state.value.tasks.filter { it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.CANCELLED }
                .map { it.photo.asset.id to it.variant }.toSet()
            val added = photos.filter { (it.asset.id to variant) !in existing }.map {
                CameraDownload(UUID.randomUUID().toString(), it, variant = variant, automatic = automatic)
            }
            queued = added
            val tasks = state.value.tasks + added
            journal.save(tasks)
            mutableState.update { it.copy(tasks = tasks) }
        }
        try {
            if (queued.isNotEmpty()) publish(queued.map {
                it.photo.asset.copy(downloadState = if (variant == DownloadVariant.ORIGINAL) DownloadState.QUEUED else DownloadState.REMOTE)
            })
        } finally { wake.trySend(Unit) }
        Unit
    }

    fun pause(paused: Boolean) { mutableState.update { it.copy(paused = paused) }; if (!paused) wake.trySend(Unit) }

    suspend fun clearDownloadHistory() = withContext(Dispatchers.IO) {
        val removed = taskLock.withLock {
            val ended = state.value.tasks.filter { it.canClearHistory }
            val remaining = state.value.tasks.filterNot { it.canClearHistory }
            journal.save(remaining)
            mutableState.update { it.copy(tasks = remaining) }
            cancelled.removeAll(ended.map { it.id }.toSet())
            ended
        }
        val noLongerQueued = removed.filter { it.status == DownloadStatus.WAITING && it.variant == DownloadVariant.ORIGINAL }
            .map { it.photo.asset.copy(downloadState = DownloadState.REMOTE) }
        if (noLongerQueued.isNotEmpty()) publish(noLongerQueued, resetQueued = true)
    }
    fun cancel(id: String) {
        cancelled += id
        if (state.value.tasks.any { it.id == id && it.channel == DownloadChannel.BLUETOOTH && it.status == DownloadStatus.RUNNING }) {
            automatic.cancelActiveBluetooth()
        }
        scope.launch {
            val task = state.value.tasks.find { it.id == id } ?: return@launch
            if (task.status == DownloadStatus.RUNNING) updateTask(id) { it.copy(message = "正在取消导入") }
            if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.WAITING ||
                (task.status == DownloadStatus.CANCELLED && task.outputUri != null)) {
                val removed = removeOutput(task.outputUri)
                updateTask(id) {
                    it.copy(status = DownloadStatus.CANCELLED, outputUri = if (removed) null else it.outputUri,
                        message = if (removed) "已取消" else "已取消，未完成文件待清理")
                }
                if (task.variant == DownloadVariant.ORIGINAL) {
                    publish(listOf(task.photo.asset.copy(downloadState = DownloadState.REMOTE)), resetQueued = true)
                }
            }
        }
    }
    fun retry(id: String) {
        scope.launch {
            val task = state.value.tasks.find { it.id == id } ?: return@launch
            check(task.status in setOf(DownloadStatus.WAITING, DownloadStatus.CANCELLED)) { "只有中断或取消的任务可以重试" }
            if (task.status == DownloadStatus.CANCELLED) {
                val removed = removeOutput(task.outputUri)
                if (!removed) { updateTask(id) { it.copy(message = "请先清理上次的未完成副本") }; return@launch }
                cancelled -= id
                updateTask(id) { it.copy(status = DownloadStatus.WAITING, outputUri = null, received = 0, payloadBytes = 0) }
            }
            if (task.channel == DownloadChannel.BLUETOOTH) {
                checkBluetoothTransfers()
                return@launch
            }
            val current = remote[task.photo.asset.id]
            if (!state.value.connected || current == null || current.cameraIdentity != identity || task.photo.cameraIdentity != identity) {
                val message = if (state.value.connected) "请刷新导入列表；若照片已重新编目，请重新选择原片导入"
                    else "请连接原来的相机，刷新导入列表后重试"
                updateTask(id) { it.copy(status = DownloadStatus.WAITING, message = message) }
                return@launch
            }
            cancelled -= id
            updateTask(id) { it.copy(photo = current, status = DownloadStatus.QUEUED, message = "等待重试") }
            wake.trySend(Unit)
        }
    }

    private suspend fun download(task: CameraDownload) {
        var current = task
        try {
            if (task.id in cancelled) throw CancellationException("已取消导入")
            val photo = remote[task.photo.asset.id] ?: error("目录尚未重新确认，请刷新相机目录后重试")
            check(photo.cameraIdentity == identity && task.photo.cameraIdentity == identity) { "已连接的相机与任务不一致" }
            val transferSession = active()
            val latest = requests.withLock {
                check(active() === transferSession) { "相机会话已更换" }
                transferSession.objectInfo(photo.handle, verify = true)
            }
            check(latest.getString("name") == photo.asset.name && latest.getLong("size") == photo.asset.sizeBytes &&
                latest.getString("captured") == photo.captured) { "相机文件已改变，不能继续旧任务" }
            if (latest.has("sonyMedia")) {
                check(cameraObjectId(latest, photo.cameraIdentity) == task.photo.asset.id) {
                    "存储卡或原片已变化，请刷新后重新选择原片"
                }
            }
            if (task.variant == DownloadVariant.CAMERA_JPEG) {
                updateTask(task.id) { it.copy(status = DownloadStatus.RUNNING, message = "正在通过 Wi-Fi 获取机内便携 JPEG") }
                val bytes = requests.withLock {
                    check(active() === transferSession) { "相机会话已更换" }
                    transferSession.portableJpeg(photo.handle)
                }
                val complete = jpegSaver.save(task.copy(photo = photo), bytes, { change -> updateTask(task.id, change) }, { task.id in cancelled }) {
                    requests.withLock {
                        if (link === transferSession && transferSession.connected) transferSession.nikonPhotoHeader(photo) else null
                    }?.sourceGeometry(photo.asset.format)
                }
                publish(listOf(complete))
                updateTask(task.id) { it.copy(status = DownloadStatus.COMPLETED, message = "便携 JPEG 已保存；原片未导入") }
                return
            }
            check(photo.asset.sizeBytes > 0 && (latest.has("sonyMedia") || photo.asset.sizeBytes != 0xffff_ffffL)) {
                "当前机身未提供可验证的文件大小，暂不导入此文件"
            }
            var output = task.outputUri?.let(Uri::parse)
            if (output == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, photo.asset.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, requireNotNull(photoMimeType(photo.asset.name)) { "无法确定原片格式" })
                    put(MediaStore.MediaColumns.RELATIVE_PATH, GalleryPublisher.relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = GalleryPublisher.collection
                output = context.contentResolver.insert(collection, values) ?: error("无法创建待导入文件")
                current = task.copy(outputUri = output.toString())
                try { updateTask(task.id) { current } } catch (error: Exception) {
                    context.contentResolver.delete(output, null, null); throw error
                }
            }
            val uri = output
            updateTask(task.id) { it.copy(status = DownloadStatus.RUNNING, message = "正在接收原片") }
            fileReceiver.receive(photo, transferSession, uri, task.received, object : CameraTransferProgress {
                override fun checkCancelled() {
                    if (task.id in cancelled) throw CancellationException("已取消导入")
                }
                override fun received(bytes: Long) {
                    mutableState.update { state ->
                        state.copy(tasks = state.tasks.map { if (it.id == task.id) it.copy(received = bytes) else it })
                    }
                }
                override suspend fun checkpoint(bytes: Long, message: String) {
                    updateTask(task.id) { it.copy(received = bytes, message = message) }
                }
                override suspend fun awaitResume() = awaitDownloadResume(task.id)
            })
            if (task.id in cancelled) throw CancellationException("已取消导入")
            gallery.publish(uri)
            val completed = repository.readCameraDownload(photo.asset, uri)
            publish(listOf(completed))
            updateTask(task.id) { it.copy(status = DownloadStatus.COMPLETED, message = "原片已保存") }
            Log.i("OpenBridgeCamera", "download stage=complete; bytes=${photo.asset.sizeBytes}")
        } catch (error: Exception) {
            Log.w("OpenBridgeCamera", "download interrupted; cancelled=${task.id in cancelled}", error)
            val record = state.value.tasks.find { it.id == task.id } ?: current
            if (task.id in cancelled) {
                val deleted = removeOutput(record.outputUri)
                updateTask(task.id) { it.copy(status = DownloadStatus.CANCELLED, outputUri = if (deleted) null else it.outputUri,
                    message = if (deleted) "已取消" else "已取消，未完成文件清理失败") }
                if (task.variant == DownloadVariant.ORIGINAL) {
                    publish(listOf(task.photo.asset.copy(downloadState = DownloadState.REMOTE)), resetQueued = true)
                }
            } else updateTask(task.id) { it.copy(status = DownloadStatus.WAITING, message = error.message ?: "连接中断，请重试") }
        }
    }

    private suspend fun awaitDownloadResume(id: String) {
        if (state.value.paused) {
            mutableState.update { it.copy(transmissionPaused = true) }
            updateTask(id) { it.copy(message = "已暂停，保留断点") }
            try { state.first { !it.paused || !it.connected || id in cancelled } }
            finally { mutableState.update { it.copy(transmissionPaused = false) } }
            if (id in cancelled) throw CancellationException("已取消导入")
        }
    }

    private suspend fun receiveBluetoothCopy(photo: RemotePhoto, bytes: () -> ByteArray) {
        val task = taskLock.withLock {
            val previous = state.value.tasks.firstOrNull {
                val sameFile = it.photo.asset.id == photo.asset.id ||
                    (photo.asset.cameraFileKey != null && it.photo.asset.cameraFileKey == photo.asset.cameraFileKey)
                it.channel == DownloadChannel.BLUETOOTH && sameFile && it.status == DownloadStatus.WAITING
            }
            val next = previous?.copy(photo = photo, status = DownloadStatus.RUNNING, message = "蓝牙正在接收便携 JPEG")
                ?: CameraDownload(UUID.randomUUID().toString(), photo, DownloadStatus.RUNNING, message = "蓝牙正在接收便携 JPEG",
                    variant = DownloadVariant.CAMERA_JPEG, automatic = true, channel = DownloadChannel.BLUETOOTH)
            val tasks = state.value.tasks.filterNot { it.id == next.id } + next
            journal.save(tasks)
            mutableState.update { it.copy(tasks = tasks) }
            next
        }
        try {
            currentCoroutineContext().ensureActive()
            if (task.id in cancelled) throw CancellationException("已取消")
            publish(listOf(photo.asset))
            val completed = jpegSaver.save(task, bytes(), { change -> updateTask(task.id, change) }, { task.id in cancelled })
            publish(listOf(completed))
            updateTask(task.id) { it.copy(status = DownloadStatus.COMPLETED, message = "蓝牙便携 JPEG 已保存；原片未导入") }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                val stopped = task.id in cancelled
                val output = state.value.tasks.find { it.id == task.id }?.outputUri
                val removed = if (stopped) removeOutput(output) else false
                updateTask(task.id) { it.copy(status = if (stopped) DownloadStatus.CANCELLED else DownloadStatus.WAITING,
                    outputUri = if (stopped && removed) null else it.outputUri,
                    message = if (stopped) { if (removed) "已取消" else "已取消，未完成副本待清理" }
                        else "蓝牙传图中断：${error.message}；小图重试会重新接收") }
            }
            throw error
        }
    }

    private suspend fun updateTask(id: String, change: (CameraDownload) -> CameraDownload) = taskLock.withLock {
        val tasks = state.value.tasks.map { if (it.id == id) change(it) else it }
        journal.save(tasks)
        mutableState.update { it.copy(tasks = tasks) }
    }

    suspend fun clearImportHistory() {
        // Queue transitions cannot pass the guard while the shared repository mutex and database transaction clear history.
        taskLock.withLock {
            repository.clearImportHistory { importHistoryBlockReason(state.value.tasks, autoState.value) != null }
        }
        changed.emit(Unit)
    }

    private fun removeOutput(value: String?): Boolean {
        if (value == null) return true
        val uri = Uri.parse(value)
        return try {
            if (context.contentResolver.delete(uri, null, null) > 0) true
            else context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { !it.moveToFirst() } ?: false
        } catch (_: FileNotFoundException) { true }
        catch (_: Exception) { false }
    }

    private suspend fun publish(assets: List<PhotoAsset>, resetQueued: Boolean = false) {
        repository.mergeCameraAssets(assets, resetQueued)
        changed.emit(Unit)
    }

    private fun active(): PtpLink = link?.takeIf { it.connected } ?: error("相机未连接")

    companion object {
        @Volatile private var instance: CameraController? = null
        fun get(context: Context): CameraController = instance ?: synchronized(this) {
            instance ?: CameraController(context.applicationContext).also { instance = it }
        }
    }
}
