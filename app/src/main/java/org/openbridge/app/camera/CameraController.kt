package org.openbridge.app.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.isActive
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
import org.openbridge.app.data.AppSettingsRepository
import org.openbridge.app.domain.DownloadVariant
import org.openbridge.app.domain.AutoDownloadKind
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset

class CameraController private constructor(private val context: Context) {
    private val browsingPreferences = context.getSharedPreferences("camera-browser", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(CameraState(
        catalogBatchSize = browsingPreferences.getInt("batchSize", 50).takeIf { it in setOf(25, 50, 100, 200) } ?: 50
    ))
    val state = mutableState.asStateFlow()
    private val errors = CoroutineExceptionHandler { _, error ->
        Log.e("OpenBridgeCamera", "controller failure", error)
        mutableState.update { it.copy(busy = false, connecting = false, message = error.message ?: "相机任务失败") }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    private val network = CameraNetwork(context, scope)
    internal val ble = NikonBle(context, scope)
    val bleState = ble.state
    val endpoints = network.endpoints
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val catalogChanged = changed.asSharedFlow()
    private val repository = PhotoRepository(context)
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
    private val taskLock = Mutex()
    private val remote get() = browser.photos
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var link: PtpLink? = null
    @Volatile private var connectionGeneration = 0
    private var connectionJob: Job? = null
    private var wifiPreferenceJob: Job? = null
    private var wifiWakeJob: Job? = null
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
        override suspend fun handles(session: CameraBrowseSession): CameraObjectSnapshot = requests.withLock {
            check(browseSession()?.generation == session.generation) { "相机会话已变化" }
            val storages = session.link.storageIds().sorted()
            val handles = storages.flatMap { session.link.objectHandles(it).toList() }.toSet()
            CameraObjectSnapshot(handles, "${session.link.catalogRevision}:${storages.joinToString(",")}")
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
            CameraService.start(context)
            bluetoothPairing.connect(camera, initializePairing = false).join()
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
        preferWifi()
        disconnectWifi()
        val generation = connectionGeneration
        CameraService.start(context)
        mutableState.update { it.copy(connecting = true, busy = true, stage = "选择 Wi‑Fi 网络", message = null) }
        connectionJob = scope.launch {
            var attempt: PtpLink? = null
            Log.i("OpenBridgeCamera", "connect mode=${config.mode}; stage=network")
            try {
                automatic.awaitBluetoothIdle()
                val route = if (config.mode == CameraMode.AP && config.ssid.isNotBlank()) {
                    network.requestAp(config.ssid.trim(), config.password)
                } else network.wifi()
                val host = config.host.trim().ifEmpty {
                    check(config.mode == CameraMode.AP) { "STA 模式请填相机 IP，或从发现列表选择相机" }
                    network.gateway(route)
                }
                currentCoroutineContext().ensureActive()
                synchronized(this@CameraController) {
                    check(generation == connectionGeneration) { "连接操作已取消" }
                    mutableState.update { it.copy(stage = "建立 PTP/IP 命令与事件通道") }
                }
                Log.i("OpenBridgeCamera", "connect stage=ptpip")
                val preferences = context.getSharedPreferences("camera-identity", Context.MODE_PRIVATE)
                val guid = preferences.getString("ptpGuid", null)?.let(UUID::fromString) ?: UUID.randomUUID().also {
                    preferences.edit().putString("ptpGuid", it.toString()).apply()
                }
                val session = PtpLink(route, host, config.port) { error -> scope.launch { connectionLost(generation, error) } }
                attempt = session
                synchronized(this@CameraController) {
                    check(generation == connectionGeneration) { "连接操作已取消" }
                    link = session
                }
                requests.withLock { session.connect(guid) }
                currentCoroutineContext().ensureActive()
                check(generation == connectionGeneration) { "连接操作已取消" }
                val device = session.device
                val cameraIdentity = cameraSerial(device.optString("serial"))?.let { "${device.optString("model")}:$it" }
                    ?: "session:${UUID.randomUUID()}"
                browser.resetSession()
                synchronized(this@CameraController) {
                    check(generation == connectionGeneration && session.connected) { "连接已取消或会话已结束" }
                    identity = cameraIdentity
                    mutableState.update { it.copy(connected = true, connecting = false, busy = false,
                        cameraName = device.optString("model"), stage = "已连接", sourceId = identity, generation = generation,
                        indexed = 0, total = 0, hasMore = false, catalogIds = emptySet()) }
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
                            else current.disconnected(generation, error.message ?: "连接失败").copy(stage = "连接失败")
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
        releaseWifiPreference()
        disconnectWifi()
        disconnectControl()
    }

    @Synchronized
    private fun connectionLost(generation: Int, error: Throwable) {
        if (generation != connectionGeneration) return
        Log.w("OpenBridgeCamera", "connection lost; releasing connection and catalog work", error)
        disconnectWifi(error.message ?: "相机连接已结束，请重新连接")
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

    fun scanBle() { CameraService.start(context); ble.scan() }
    fun connectBle(camera: BleCamera) {
        CameraService.start(context)
        automatic.allowStandbyReconnect(true)
        releaseStaleWifiPreference()
        val known = settings.state.value.transferAddress.isNotBlank() && matchesCameraName(camera.name, setOf(settings.state.value.controlName))
        if (!camera.classic) settings.update { it.copy(controlAddress = camera.address, controlName = camera.name) }
        bluetoothPairing.connect(camera, initializePairing = !known || camera.classic)
    }
    fun discoverClassic() {
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
        CameraService.start(context)
        preferWifi()
        wifiWakeJob?.cancel()
        wifiWakeJob = scope.launch {
            val owner = currentCoroutineContext()[Job]
            try {
                automatic.awaitBluetoothIdle()
                if (!ble.state.value.authenticated || !ble.state.value.systemPaired) {
                    val control = settings.state.value.controlAddress
                    check(control.isNotBlank()) { "请先关联相机的 BLE 控制设备" }
                    bluetoothPairing.connect(BleCamera(control, settings.state.value.controlName),
                        rediscover = true, initializePairing = false).join()
                }
                currentCoroutineContext().ensureActive()
                ble.requestWifi()
            } catch (error: Exception) {
                if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                ble.connectionMessage("开启 Wi-Fi 未完成：${error.message ?: "请重试"}")
                synchronized(this@CameraController) {
                    if (wifiWakeJob === owner && !state.value.connected && !state.value.connecting) releaseWifiPreference()
                }
            }
        }
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
        wifiWakeJob?.cancel()
        wifiWakeJob = null
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
    fun disconnectControl() {
        automatic.allowStandbyReconnect(false)
        manualBluetoothJob?.cancel()
        bluetoothPairing.cancel()
        ble.disconnect()
        automatic.cancelActiveBluetooth()
    }

    @Synchronized
    fun checkBluetoothTransfers() {
        if (!automatic.beginManualCheck()) return
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

    fun connectSavedControl() {
        val address = settings.state.value.controlAddress
        check(address.isNotBlank()) { "请先选择 BLE 控制设备" }
        CameraService.start(context)
        automatic.allowStandbyReconnect(true)
        releaseStaleWifiPreference()
        bluetoothPairing.connect(BleCamera(address, settings.state.value.controlName), rediscover = true, initializePairing = false)
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

    fun refresh() = browser.refresh(state.value.catalogBatchSize)
    fun loadMore() = browser.loadMore(state.value.catalogBatchSize)
    fun requestPreview(id: String, detailed: Boolean): () -> Unit = browser.requestPreview(id, detailed)

    fun setCatalogBatchSize(size: Int) {
        require(size in setOf(25, 50, 100, 200))
        browsingPreferences.edit().putInt("batchSize", size).apply()
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
        val session = link?.takeIf { it.connected && state.value.connected } ?: return null
        return CameraBrowseSession(session, identity, state.value.cameraName, connectionGeneration)
    }

    private fun catalogProgress(progress: BrowseProgress) {
        mutableState.update { it.withCatalogProgress(progress) }
        if (!progress.busy && state.value.connected && progress.generation == state.value.generation) wake.trySend(Unit)
    }

    suspend fun enqueue(ids: Set<String>): Unit = withContext(Dispatchers.IO) {
        check(state.value.connected) { "请先连接相机" }
        val photos = ids.mapNotNull { remote[it] }
        check(photos.size == ids.size) { "请连接原相机并刷新目录后再添加下载" }
        enqueuePhotos(photos, settings.state.value.manualVariant, false)
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
    fun cancel(id: String) {
        cancelled += id
        if (state.value.tasks.any { it.id == id && it.channel == DownloadChannel.BLUETOOTH && it.status == DownloadStatus.RUNNING }) {
            automatic.cancelActiveBluetooth()
        }
        scope.launch {
            val task = state.value.tasks.find { it.id == id } ?: return@launch
            if (task.status == DownloadStatus.RUNNING) updateTask(id) { it.copy(message = "正在取消下载") }
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
            val current = remote[task.photo.asset.id] ?: error("请先连接同一相机并刷新目录，以重新确认文件身份")
            check(current.cameraIdentity == identity && task.photo.cameraIdentity == identity) { "相机身份不匹配，不能续传" }
            cancelled -= id
            updateTask(id) { it.copy(photo = current, status = DownloadStatus.QUEUED, message = "等待重试") }
            wake.trySend(Unit)
        }
    }

    private suspend fun download(task: CameraDownload) {
        var current = task
        try {
            if (task.id in cancelled) throw CancellationException("已取消下载")
            val photo = remote[task.photo.asset.id] ?: error("目录尚未重新确认，请刷新相机目录后重试")
            check(photo.cameraIdentity == identity && task.photo.cameraIdentity == identity) { "已连接的相机与任务不一致" }
            val transferSession = active()
            val latest = requests.withLock {
                check(active() === transferSession) { "相机会话已更换" }
                transferSession.objectInfo(photo.handle)
            }
            check(latest.getString("name") == photo.asset.name && latest.getLong("size") == photo.asset.sizeBytes &&
                latest.getString("captured") == photo.captured) { "相机文件已改变，不能继续旧任务" }
            if (task.variant == DownloadVariant.CAMERA_JPEG) {
                updateTask(task.id) { it.copy(status = DownloadStatus.RUNNING, message = "正在通过 Wi-Fi 获取机内便携 JPEG") }
                val bytes = requests.withLock {
                    check(active() === transferSession) { "相机会话已更换" }
                    transferSession.portableJpeg(photo.handle)
                }
                val complete = jpegSaver.save(task, bytes, { change -> updateTask(task.id, change) }, { task.id in cancelled })
                publish(listOf(complete))
                updateTask(task.id) { it.copy(status = DownloadStatus.COMPLETED, message = "便携 JPEG 已保存；原片未下载") }
                return
            }
            check(photo.asset.sizeBytes > 0 && photo.asset.sizeBytes != 0xffff_ffffL) { "当前机身未提供可验证的文件大小，暂不下载此文件" }
            var output = task.outputUri?.let(Uri::parse)
            if (output == null) {
                val raw = photo.asset.format == MediaFormat.RAW
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, photo.asset.name)
                    val mime = if (!raw) "image/jpeg" else if (photo.asset.name.endsWith(".nef", true)) "image/x-nikon-nef" else "image/x-nikon-nrw"
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        if (raw) "${Environment.DIRECTORY_DOWNLOADS}/OpenBridge" else "${Environment.DIRECTORY_PICTURES}/OpenBridge")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (raw) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                output = context.contentResolver.insert(collection, values) ?: error("无法创建待下载文件")
                current = task.copy(outputUri = output.toString())
                try { updateTask(task.id) { current } } catch (error: Exception) {
                    context.contentResolver.delete(output, null, null); throw error
                }
            }
            val uri = output
            updateTask(task.id) { it.copy(status = DownloadStatus.RUNNING, message = "正在接收原片") }
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: error("无法打开待下载文件")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream ->
                val partial = transferSession.supportsPartial(photo.asset.sizeBytes)
                var offset = if (partial && stream.channel.size() >= task.received) task.received else 0L
                stream.channel.truncate(offset)
                stream.channel.position(offset)
                if (!partial) {
                    updateTask(task.id) { it.copy(received = 0, message = "相机不支持分段读取；中断后会重传此文件") }
                    var lastProgress = 0L
                    requests.withLock {
                        check(active() === transferSession) { "相机会话已更换" }
                        transferSession.original(photo.handle, stream, photo.asset.sizeBytes) { received ->
                            if (task.id in cancelled) throw CancellationException("已取消下载")
                            val now = System.nanoTime()
                            if (now - lastProgress > 100_000_000) {
                                mutableState.update { state ->
                                    state.copy(tasks = state.tasks.map { if (it.id == task.id) it.copy(received = received) else it })
                                }
                                lastProgress = now
                            }
                        }
                    }
                    offset = stream.channel.position()
                    stream.fd.sync()
                } else {
                    while (offset < photo.asset.sizeBytes) {
                        currentCoroutineContext().ensureActive()
                        if (task.id in cancelled) throw CancellationException("已取消下载")
                        if (state.value.paused) {
                            mutableState.update { it.copy(transmissionPaused = true) }
                            updateTask(task.id) { it.copy(message = "已暂停，保留断点") }
                            try { state.first { !it.paused || !it.connected || task.id in cancelled } }
                            finally { mutableState.update { it.copy(transmissionPaused = false) } }
                            if (task.id in cancelled) throw CancellationException("已取消下载")
                        }
                        val start = offset
                        val count = minOf(1024 * 1024L, photo.asset.sizeBytes - offset).toInt()
                        try {
                            val received = requests.withLock {
                                check(active() === transferSession) { "相机会话已更换" }
                                transferSession.partial(photo.handle, start, count, stream)
                            }
                            check(received > 0 && received <= count) { "相机返回了无效的分块长度" }
                            offset += received
                            stream.fd.sync()
                        } catch (error: Exception) { stream.channel.truncate(start); stream.channel.position(start); throw error }
                        updateTask(task.id) { it.copy(received = offset, message = "正在接收原片") }
                    }
                }
                check(offset == photo.asset.sizeBytes && stream.channel.size() == offset) { "原图长度与相机报告不一致" }
                updateTask(task.id) { it.copy(received = offset, message = "正在完成保存") }
            }
            if (task.id in cancelled) throw CancellationException("已取消下载")
            check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                "文件已接收，但未能完成媒体库保存"
            }
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
            updateTask(task.id) { it.copy(status = DownloadStatus.COMPLETED, message = "蓝牙便携 JPEG 已保存；原片未下载") }
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
