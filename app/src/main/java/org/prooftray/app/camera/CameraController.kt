package org.prooftray.app.camera

import android.content.Context
import android.net.Network
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.prooftray.app.data.AppSettingsRepository
import org.prooftray.app.domain.DownloadVariant
import org.prooftray.app.domain.AutoDownloadKind
import org.prooftray.app.domain.SortOrder

/**
 * Facade over the camera stack. It owns the connection lifecycle (BLE control, camera Wi-Fi, PTP session) and hands
 * catalog reading, the import queue and location routing to [CameraCatalogBrowser], [CameraDownloadQueue] and [CameraLocationRouter].
 */
class CameraController private constructor(private val context: Context) {
    private val browsingPreferences = context.getSharedPreferences("camera-browser", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(CameraState(
        catalogBatchSize = CatalogBatchSize.fromPreference(browsingPreferences.all["batchSize"])
    ))
    val state = mutableState.asStateFlow()
    @Volatile private var catalogSort = SortOrder.NEWEST
    private val errors = CoroutineExceptionHandler { _, error ->
        Log.e("ProofTray.Camera", "controller failure", error)
        mutableState.update { it.copy(busy = false, connecting = false, message = error.message ?: "相机任务失败") }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    private val network = CameraNetwork(context, scope)
    private val identities = CameraIdentityStore(context)
    internal val ble = NikonBle(context, scope, identities)
    private val sony = SonyControl(context) { ready -> mutableState.update { it.copy(sonyControlConnected = ready) } }
    internal val sonyLocationChanges = sony.locationChanges
    val bleState = ble.state
    val endpoints = network.endpoints
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val catalogChanged = changed.asSharedFlow()
    private val settings = AppSettingsRepository.get(context)
    private val bluetoothPairing: BluetoothPairingCoordinator by lazy {
        BluetoothPairingCoordinator(context, scope, ble, settings) {
            automatic.cancelActiveBluetooth()
            automatic.awaitBluetoothIdle()
        }
    }
    private val requests = Mutex()
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
    private val remote get() = browser.photos
    internal val location = CameraLocationRouter(settings, state, sony, ble, requests, ::browseSession)
    private val downloads = CameraDownloadQueue(context, scope, mutableState, requests, object : DownloadSource {
        override val identity: String get() = this@CameraController.identity
        override fun photo(id: String) = remote[id]
        override fun active() = this@CameraController.active()
        override fun isLive(link: PtpLink) = this@CameraController.link === link && link.connected
    }) { changed.emit(Unit) }
    private val automatic: CameraAutomation = CameraAutomation(context, scope, settings, object : AutomaticTransferHost {
        override val camera = state
        override val ble = this@CameraController.ble
        override fun wifiSession() = browseSession()
        override fun clientIdentity(): ByteArray = identities.bleIdentity() ?: error("请先完成本应用的 BLE 配对")
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
            downloads.enqueue(photos, DownloadVariant.ORIGINAL, automatic = true)
        }
        override suspend fun receiveBluetooth(photo: RemotePhoto, bytes: () -> ByteArray) = downloads.receiveBluetooth(photo, bytes)
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
            while (true) {
                delay(settings.state.value.powerProfile.pollSeconds.coerceAtLeast(15) * 1000L)
                if (!state.value.connected) continue
                val generation = connectionGeneration
                try {
                    requests.withLock {
                        check(generation == connectionGeneration) { "相机会话已更换" }
                        if (state.value.tasks.none { it.status == DownloadStatus.RUNNING && it.channel == DownloadChannel.WIFI }) active().ping()
                    }
                } catch (stop: CancellationException) { throw stop }
                catch (error: Exception) { connectionLost(generation, error) }
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

    private fun ensureGeneration(generation: Int) {
        if (generation != connectionGeneration) throw CancellationException("连接操作已取消")
    }

    @Synchronized
    private fun startWifiConnection(initialPhase: ConnectionPhase, prepare: suspend ((ConnectionPhase, String) -> Unit) -> WifiPlan) {
        location.sonyStopped = false
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
                    ensureGeneration(generation)
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
                    ensureGeneration(generation)
                    if (!managedHotspot) network.watch(route, lost)
                }
                network.awaitAddress(route)
                val host = config.host.trim().ifEmpty {
                    check(config.mode == CameraMode.AP) { "STA 模式请填相机 IP，或从发现列表选择相机" }
                    network.hotspotAddress(route, preferDhcp = !config.sonyCamera)
                }
                progress(ConnectionPhase.PTP, "Wi-Fi 已就绪，正在建立相机传图会话")
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
                    ensureGeneration(generation)
                    link = session
                }
                requests.withLock { session.connect(identities.ptpGuid()) }
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
                Log.i("ProofTray.Camera", "connect stage=ready; model=${device.optString("model")}; firmware=${device.optString("version")}")
                refresh()
            } catch (error: Exception) {
                if (error !is CancellationException) Log.w("ProofTray.Camera", "connect failed", error)
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
        Log.w("ProofTray.Camera", "connection lost; releasing connection and catalog work", error)
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

    private fun clearManualDisconnect() = mutableState.update { it.copy(manuallyDisconnected = false) }

    @Synchronized
    fun scanBle() {
        clearManualDisconnect()
        CameraService.start(context)
        automatic.cancelActiveBluetooth()
        ble.scan(includeSony = true)
    }

    @Synchronized
    fun stopBleScan() {
        ble.stopScan()
        ble.connectionMessage(if (ble.state.value.authenticated) "已停止搜索，蓝牙控制仍连接" else "已停止搜索")
    }

    @Synchronized
    fun connectBle(camera: BleCamera) {
        clearManualDisconnect()
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
                    location.sonyStopped = false
                    mutableState.update { it.copy(connecting = false, busy = false, connectionPhase = ConnectionPhase.IDLE,
                        stage = "索尼相机已配对，可以导入照片", message = null) }
                }
            } catch (stop: CancellationException) { throw stop }
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
        clearManualDisconnect()
        automatic.allowStandbyReconnect(true)
        val saved = settings.state.value
        if (saved.controlAddress.isBlank()) {
            ble.pairingStatus("请先搜索并选择相机的 BLE 控制设备", false, false)
            return
        }
        CameraService.start(context)
        releaseStaleWifiPreference()
        bluetoothPairing.connect(BleCamera(saved.controlAddress, saved.controlName), rediscover = true, initializePairing = true)
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
            val control = awaitSavedControl("请先搜索并配对相机，再连接相机 Wi-Fi")
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

    /** Reconnects the saved Nikon BLE control link when it is not the live one, then returns its ready state. */
    private suspend fun awaitSavedControl(missingMessage: String, reconnecting: () -> Unit = {}): BleState {
        bluetoothPairing.awaitCurrent()
        val saved = settings.state.value
        check(saved.controlAddress.isNotBlank()) { missingMessage }
        val current = ble.state.value
        if (!current.dataReady || current.connectedAddress != saved.controlAddress) {
            reconnecting()
            bluetoothPairing.connect(BleCamera(saved.controlAddress, saved.controlName), rediscover = true, initializePairing = false).join()
        }
        currentCoroutineContext().ensureActive()
        val control = ble.state.value
        check(control.dataReady) { control.message }
        return control
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
                catch (stop: CancellationException) { throw stop }
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
        val keepControl = (options.autoDownload && options.autoKind == AutoDownloadKind.CAMERA_JPEG) || options.locationSync
        if (options.controlAddress.isNotBlank() && keepControl && !ble.state.value.dataReady) connectSavedControl()
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
        location.sonyStopped = true
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
        clearManualDisconnect()
        manualBluetoothJob = scope.launch {
            var result = "蓝牙检查已取消"
            try {
                CameraService.start(context)
                synchronized(this@CameraController) {
                    check(!state.value.connected && !state.value.connecting) { "Wi-Fi 正在连接或传输，请先断开 Wi-Fi 会话" }
                    releaseWifiPreference()
                }
                automatic.manualProgress("正在检查已关联相机的控制连接")
                awaitSavedControl("请先在设备设置中选择相机的 BLE 控制设备") { automatic.manualProgress("正在重新发现并连接已关联相机") }
                automatic.manualProgress("正在等待蓝牙通道并检查待传照片")
                result = automatic.checkBluetoothOnce()
            } catch (stop: CancellationException) { throw stop }
            catch (error: Exception) {
                Log.w("ProofTray.Bluetooth", "manual transfer check failed", error)
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
        clearManualDisconnect()
        val control = ble.state.value
        if (!retry && control.dataReady && control.connectedAddress == saved.controlAddress) {
            CameraService.start(context)
            automatic.allowStandbyReconnect(true)
        } else connectSavedControl()
    }

    @Synchronized
    fun connectSavedControl() {
        if (settings.state.value.sonySelected) return
        clearManualDisconnect()
        val address = settings.state.value.controlAddress
        check(address.isNotBlank()) { "请先选择 BLE 控制设备" }
        CameraService.start(context)
        automatic.allowStandbyReconnect(true)
        releaseStaleWifiPreference()
        bluetoothPairing.connect(BleCamera(address, settings.state.value.controlName), rediscover = true, initializePairing = false)
    }

    fun resumeAutomaticDownloads() {
        clearManualDisconnect()
        CameraService.start(context)
        automatic.allowStandbyReconnect(true)
    }

    fun resumeLocationSync() {
        location.sonyStopped = false
        clearManualDisconnect()
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
        check(!downloads.hasPending(source, selectedIds)) { "请先完成或取消待传输任务，再清空选片列表" }
        browser.clearImportList(source, selectedIds)
    }

    private fun browseSession(): CameraBrowseSession? {
        val camera = state.value
        val session = link?.takeIf { it.connected && camera.connected && !camera.connecting } ?: return null
        return CameraBrowseSession(session, identity, state.value.cameraName, connectionGeneration)
    }

    private fun catalogProgress(progress: BrowseProgress) {
        mutableState.update { it.withCatalogProgress(progress) }
        if (!progress.busy && state.value.connected && progress.generation == state.value.generation) downloads.wake()
    }

    suspend fun enqueue(ids: Set<String>): Unit = withContext(Dispatchers.IO) {
        check(state.value.connected) { "请先连接相机" }
        val photos = ids.mapNotNull { remote[it] }
        check(photos.size == ids.size) { "请连接原相机并刷新目录后再添加导入" }
        val variant = if (active().make == CameraMake.NIKON) settings.state.value.manualVariant else DownloadVariant.ORIGINAL
        downloads.enqueue(photos, variant, automatic = false)
    }

    fun pause(paused: Boolean) = downloads.pause(paused)
    fun cancel(id: String) = downloads.cancel(id, automatic::cancelActiveBluetooth)
    fun retry(id: String) = downloads.retry(id, ::checkBluetoothTransfers)
    suspend fun clearDownloadHistory() = downloads.clearHistory()
    suspend fun clearImportHistory() = downloads.clearImportHistory { autoState.value }

    private fun active(): PtpLink = link?.takeIf { it.connected } ?: error("相机未连接")

    companion object {
        @Volatile private var instance: CameraController? = null
        fun get(context: Context): CameraController = instance ?: synchronized(this) {
            instance ?: CameraController(context.applicationContext).also { instance = it }
        }
    }
}
