package org.openbridge.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openbridge.app.data.BatchExportManager
import org.openbridge.app.data.PhotoRepository
import org.openbridge.app.domain.CatalogEngine
import org.openbridge.app.domain.ExportOptions
import org.openbridge.app.domain.FilterSpec
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PickState
import org.openbridge.app.domain.SavedFilter
import org.openbridge.app.ui.LibraryUiState
import org.openbridge.app.camera.CameraController
import org.openbridge.app.camera.CameraService
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.color.LutRepository
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.ColorPreset
import org.openbridge.app.domain.PhotoCollection
import org.openbridge.app.domain.ImportVisibility
import org.openbridge.app.domain.collectionAssets
import org.openbridge.app.data.AppSettingsRepository
import org.openbridge.app.data.PowerProfile
import org.openbridge.app.data.BluetoothRole
import org.openbridge.app.domain.AutoDownloadKind
import org.openbridge.app.domain.DownloadVariant
import org.openbridge.app.camera.CameraLocationService
import org.openbridge.app.camera.DownloadStatus
import org.openbridge.app.domain.ExportStatus
import org.openbridge.app.data.MediaDeletion
import org.openbridge.app.data.PhotoPreviewLoader

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private data class QueryInput(val assets: List<PhotoAsset>, val filter: FilterSpec, val collapseBursts: Boolean)
    private data class PendingExport(val assets: List<PhotoAsset>, val options: ExportOptions)

    private val repository = PhotoRepository(application)
    private val exporter = BatchExportManager(application)
    private val colors = LutRepository.get(application)
    private val preferences = AppSettingsRepository.get(application)
    private val mediaDeletion = MediaDeletion(application)
    val camera = CameraController.get(application)
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()
    private val queries = MutableStateFlow(QueryInput(emptyList(), FilterSpec(), true))
    private val operations = Mutex()
    private var operationCount = 0
    private var queryRunning = false
    private var pendingExport: PendingExport? = null

    init {
        viewModelScope.launch {
            queries.collectLatest { input ->
                queryRunning = true
                updateLoading()
                try {
                    val catalog = withContext(Dispatchers.Default) {
                        CatalogEngine.query(input.assets, input.filter, input.collapseBursts)
                    }
                    val visibleIds = catalog.groups.flatMap { it.members }.mapTo(hashSetOf()) { it.id }
                    mutableState.update { it.copy(catalog = catalog, selectedIds = it.selectedIds.intersect(visibleIds)) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: LinkageError) {
                    showError("C++ 筛选库未能加载，请检查应用的原生库打包：${error.message}")
                } catch (error: Exception) {
                    showError("筛选失败：${error.message ?: "无法读取目录"}")
                } finally {
                    queryRunning = false
                    updateLoading()
                }
            }
        }
        viewModelScope.launch { exporter.tasks.collect { tasks -> mutableState.update { it.copy(tasks = tasks) } } }
        viewModelScope.launch { exporter.paused.collect { paused -> mutableState.update { it.copy(exportPaused = paused) } } }
        viewModelScope.launch { exporter.failure.collect { message -> if (message != null) showError(message) } }
        viewModelScope.launch {
            camera.state.collect { value ->
                val previous = state.value.camera
                mutableState.update { it.copy(camera = value) }
                if (previous.connected != value.connected || previous.sourceId != value.sourceId ||
                    previous.generation != value.generation || previous.catalogIds != value.catalogIds) {
                    requestQuery()
                }
            }
        }
        viewModelScope.launch { camera.bleState.collect { value -> mutableState.update { it.copy(ble = value) } } }
        viewModelScope.launch { camera.endpoints.collect { value -> mutableState.update { it.copy(cameraEndpoints = value) } } }
        viewModelScope.launch { preferences.state.collect { value ->
            val previous = state.value.settings.manualVariant
            mutableState.update { it.copy(settings = value) }
            if (previous != value.manualVariant) requestQuery()
        } }
        viewModelScope.launch { camera.autoState.collect { value -> mutableState.update { it.copy(automatic = value) } } }
        viewModelScope.launch { CameraLocationService.state.collect { value -> mutableState.update { it.copy(location = value) } } }
        viewModelScope.launch { camera.catalogChanged.conflate().collect { reloadAssets(); delay(120) } }
        runOperation {
            exporter.restore()
            for ((token, targets) in mediaDeletion.completedRequests()) {
                repository.removeDeletedMedia(targets)
                mediaDeletion.finish(token)
            }
            reloadAssets()
            reloadSavedFilters()
            reloadColorLibrary()
        }
    }

    fun importPhotos(uris: List<Uri>, permissionWarnings: List<String> = emptyList(), persistPermission: Boolean = true) {
        if (uris.isEmpty()) return
        setCollection(PhotoCollection.LIBRARY)
        runOperation {
            val messages = permissionWarnings.toMutableList()
            var imported = 0
            var skipped = 0
            for (uriBatch in uris.distinct().chunked(20)) {
                val grantWarnings = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    if (!persistPermission) emptyList() else uriBatch.mapNotNull { uri ->
                        try {
                            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            null
                        } catch (_: SecurityException) {
                            "部分文件提供方不支持长期授权，重启后可能需要重新选择文件"
                        }
                    }.distinct()
                }
                val report = repository.importUris(uriBatch) { batch ->
                    withContext(Dispatchers.Main.immediate) {
                        mutableState.update { current ->
                            val updated = current.assets.associateByTo(linkedMapOf()) { it.id }
                            batch.forEach { updated[it.id] = it }
                            current.copy(assets = updated.values.toList())
                        }
                        requestQuery()
                    }
                }
                imported += report.importedCount
                skipped += report.skippedCount
                messages.addAll(grantWarnings + report.errors)
            }
            reloadAssets()
            if (messages.isNotEmpty()) {
                showError("导入/更新 $imported 张，跳过 $skipped 张。${messages.distinct().take(4).joinToString("；")}")
            }
        }
    }

    fun setFilter(filter: FilterSpec) {
        mutableState.update { it.copy(browser = it.browser.withFilter(filter), selectedIds = emptySet()) }
        requestQuery()
    }

    fun setCollection(collection: PhotoCollection) {
        if (state.value.browser.collection == collection) return
        mutableState.update { it.copy(browser = it.browser.copy(collection = collection), selectedIds = emptySet()) }
        requestQuery()
    }

    fun setImportVisibility(visibility: ImportVisibility) {
        mutableState.update { it.copy(browser = it.browser.copy(importVisibility = visibility), selectedIds = emptySet()) }
        requestQuery()
    }

    fun setOfflinePreviews(visible: Boolean) {
        mutableState.update { it.copy(browser = it.browser.copy(showOfflinePreviews = visible), selectedIds = emptySet()) }
        requestQuery()
    }

    fun clearImportList() = runOperation {
        camera.clearImportList()
        mutableState.update { it.copy(selectedIds = emptySet()) }
        reloadAssets()
    }

    fun removeFromImport() {
        val ids = state.value.selectedIds.toSet()
        if (ids.isEmpty()) return
        runOperation {
            camera.clearImportList(ids)
            mutableState.update { it.copy(selectedIds = it.selectedIds - ids) }
            reloadAssets()
        }
    }

    fun validateDeletion(assets: List<PhotoAsset>): Boolean {
        val current = state.value
        val ids = assets.mapTo(hashSetOf()) { it.id }
        val uris = assets.mapTo(hashSetOf()) { it.uri }
        val exporting = current.tasks.any { it.asset.uri in uris && it.status in setOf(ExportStatus.QUEUED, ExportStatus.RUNNING) }
        val downloading = current.camera.tasks.any {
            it.photo.asset.id in ids && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
        }
        if (exporting || downloading) { showError("所选照片还有传输或导出任务，请先完成或取消任务"); return false }
        if (assets.isEmpty() || assets.any { it.downloadState != DownloadState.DOWNLOADED }) {
            showError("只能删除已经保存在手机上的文件"); return false
        }
        return true
    }

    fun completeMediaDeletion(token: String) = runOperation {
        withContext(NonCancellable) {
            val targets = mediaDeletion.pendingTargets(token)
            // RESULT_OK from createDeleteRequest means the platform has already completed the deletion.
            mediaDeletion.recordDeleted(token, targets)
            repository.removeDeletedMedia(targets)
            mediaDeletion.finish(token)
            PhotoPreviewLoader.clearMemoryCache()
            mutableState.update { it.copy(selectedIds = it.selectedIds - targets.map { target -> target.id }.toSet()) }
            reloadAssets()
        }
    }

    fun deleteMediaOnAndroid10(token: String) = runOperation {
        withContext(NonCancellable) {
            val result = mediaDeletion.deleteOnAndroid10(mediaDeletion.pendingTargets(token))
            mediaDeletion.recordDeleted(token, result.deleted)
            repository.removeDeletedMedia(result.deleted)
            mediaDeletion.finish(token)
            PhotoPreviewLoader.clearMemoryCache()
            mutableState.update { it.copy(selectedIds = it.selectedIds - result.deleted.map { target -> target.id }.toSet()) }
            reloadAssets()
            if (result.errors.isNotEmpty()) showError("已删除 ${result.deleted.size} 项；${result.errors.take(3).joinToString("；")}")
        }
    }

    fun removeFromLibrary(all: Boolean) {
        val current = state.value
        val ids = current.assets.filter {
            it.downloadState == DownloadState.DOWNLOADED && !it.hiddenFromLibrary && (all || it.id in current.selectedIds)
        }.mapTo(hashSetOf()) { it.id }
        runOperation {
            repository.setLibraryVisibility(ids, false)
            mutableState.update { it.copy(selectedIds = it.selectedIds - ids) }
            reloadAssets()
        }
    }

    fun setCollapseBursts(enabled: Boolean) {
        mutableState.update { it.copy(collapseBursts = enabled) }
        requestQuery()
    }

    fun toggleSelection(id: String) {
        if (state.value.catalog.groups.none { group -> group.members.any { it.id == id } }) return
        mutableState.update { current ->
            val selected = current.selectedIds.toMutableSet()
            if (!selected.add(id)) selected.remove(id)
            current.copy(selectedIds = selected)
        }
    }

    fun selectVisible() {
        mutableState.update { current ->
            current.copy(selectedIds = current.catalog.groups.flatMap { it.members }.mapTo(linkedSetOf()) { it.id })
        }
    }

    fun clearSelection() = mutableState.update { it.copy(selectedIds = emptySet()) }

    fun setRating(rating: Int) {
        if (rating !in 0..5) return
        updateMarks(rating = rating)
    }

    fun setPickState(pickState: PickState) = updateMarks(pickState = pickState)
    fun setTags(tags: Set<String>) = updateMarks(tags = tags.map(String::trim).filter(String::isNotEmpty).toSet())

    private fun updateMarks(rating: Int? = null, pickState: PickState? = null, tags: Set<String>? = null) {
        val ids = state.value.selectedIds.toSet()
        if (ids.isEmpty()) return
        runOperation {
            repository.updateMarks(ids, rating, pickState, tags)
            reloadAssets()
        }
    }

    fun saveFilter(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val saved = SavedFilter(UUID.randomUUID().toString(), trimmed.take(80), state.value.filter)
        runOperation {
            repository.saveFilter(saved)
            reloadSavedFilters()
        }
    }

    fun deleteSavedFilter(id: String) = runOperation {
        repository.deleteFilter(id)
        reloadSavedFilters()
    }

    fun prepareExport(options: ExportOptions): Boolean {
        val current = state.value
        val assets = current.assets.filter { it.id in current.selectedIds }
        if (assets.isEmpty()) {
            showError("请先选择需要导出的照片。")
            return false
        }
        if (assets.any { it.downloadState != DownloadState.DOWNLOADED }) {
            showError("请先下载所选照片的原片，再进行转换或导出；相机预览不会当作原片导出。")
            return false
        }
        pendingExport = PendingExport(assets, options)
        return true
    }

    fun exportTo(destination: Uri?) {
        val request = pendingExport
        pendingExport = null
        if (destination == null || request == null) return
        runOperation {
            withContext(Dispatchers.IO) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    getApplication<Application>().contentResolver.takePersistableUriPermission(destination, flags)
                } catch (_: SecurityException) {
                    showError("目标文件夹无法保存长期授权，中断后重试可能需要重新选择。")
                }
            }
            exporter.enqueue(request.assets, request.options, destination.toString())
        }
    }

    fun cancelTask(id: String) = exporter.cancel(id)
    fun setAutoDownload(value: Boolean) = preferences.update { it.copy(autoDownload = value) }
    fun setAutoKind(value: AutoDownloadKind) = preferences.update { it.copy(autoKind = value) }
    fun setManualVariant(value: DownloadVariant) = preferences.update { it.copy(manualVariant = value) }
    fun setPowerProfile(value: PowerProfile) = preferences.update { it.copy(powerProfile = value) }
    fun setLocationSync(value: Boolean) = preferences.update { it.copy(locationSync = value) }
    fun chooseControlDevice(address: String) = preferences.update {
        it.copy(controlAddress = address, controlName = state.value.pairedDevices.find { device -> device.address == address }?.name.orEmpty())
    }
    fun chooseTransferDevice(address: String) = preferences.update { it.copy(transferAddress = address) }
    fun setBluetoothRole(value: BluetoothRole) = preferences.update { it.copy(bluetoothRole = value) }
    fun refreshPairedDevices() {
        runCatching { camera.pairedDevices() }.onSuccess { devices -> mutableState.update { it.copy(pairedDevices = devices) } }
            .onFailure { showError(it.message ?: "无法读取系统配对设备") }
    }

    fun setLook(look: ColorLookSettings) {
        runCatching { look.validate(); mutableState.update { it.copy(look = look) } }.onFailure { showError(it.message ?: "预设无效") }
    }

    fun importColor(uris: List<Uri>) {
        if (uris.isEmpty() || state.value.colorBusy) return
        viewModelScope.launch {
            mutableState.update { it.copy(colorBusy = true) }
            try {
                val errors = mutableListOf<String>()
                for (uri in uris.distinct()) {
                    try { colors.import(uri) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { errors += error.message ?: "导入失败" }
                }
                reloadColorLibrary()
                if (errors.isNotEmpty()) showError(errors.take(3).joinToString("；"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(error.message ?: "无法读取预设库") }
            finally { mutableState.update { it.copy(colorBusy = false) } }
        }
    }

    fun saveColorPreset(name: String, look: ColorLookSettings) = runOperation {
        colors.savePreset(name, look)
        reloadColorLibrary()
    }

    fun exportColorPreset(preset: ColorPreset, destination: Uri) = runOperation { colors.exportPreset(preset, destination) }

    private suspend fun reloadColorLibrary() {
        val luts = colors.luts()
        val presets = colors.presets()
        mutableState.update { it.copy(luts = luts, colorPresets = presets) }
    }
    fun downloadSelection() {
        val current = state.value
        val selected = current.catalog.groups.flatMap { it.members }.distinctBy { it.id }.filter { it.id in current.selectedIds }
            .take(current.camera.catalogBatchSize)
        if (selected.isEmpty()) { showError("请先选择要导入的照片"); return }
        runOperation {
            val portable = current.settings.manualVariant == DownloadVariant.CAMERA_JPEG
            val local = selected.filter { !portable && it.downloadState == DownloadState.DOWNLOADED }.mapTo(hashSetOf()) { it.id }
            val remote = selected.filter { portable || it.downloadState != DownloadState.DOWNLOADED }.mapTo(linkedSetOf()) { it.id }
            if (remote.isNotEmpty()) {
                check(camera.state.value.connected) { "离线预览可以选片；导入原片需要重新连接相机" }
                camera.enqueue(remote)
            }
            repository.setLibraryVisibility(local, true)
            val submitted = local + remote
            mutableState.update { it.copy(selectedIds = it.selectedIds - submitted) }
            reloadAssets()
        }
    }
    fun disconnectCamera() {
        camera.disconnect()
        getApplication<Application>().stopService(Intent(getApplication(), CameraService::class.java))
    }
    fun retryTask(id: String) = exporter.retry(id)
    fun pauseExports(paused: Boolean) = exporter.setPaused(paused)
    fun showError(message: String) = mutableState.update { it.copy(errorMessage = message) }
    fun dismissError() = mutableState.update { it.copy(errorMessage = null) }

    private suspend fun reloadAssets() {
        val assets = repository.loadAssets()
        mutableState.update { it.copy(assets = assets) }
        requestQuery()
    }

    private suspend fun reloadSavedFilters() {
        val filters = repository.loadSavedFilters()
        mutableState.update { it.copy(savedFilters = filters) }
    }

    private fun requestQuery() {
        val current = state.value
        val source = current.camera.sourceId.takeIf { current.camera.connected }
        val members = collectionAssets(current.assets, current.browser, source, current.camera.catalogIds, current.settings.manualVariant)
        queries.value = QueryInput(members, current.filter, current.collapseBursts)
    }

    private fun updateLoading() = mutableState.update { it.copy(isLoading = operationCount > 0 || queryRunning) }

    private fun runOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            operationCount++
            updateLoading()
            try {
                operations.withLock { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: "操作失败，请检查文件权限和剩余空间。")
            } finally {
                operationCount--
                updateLoading()
            }
        }
    }

    override fun onCleared() {
        exporter.close()
        super.onCleared()
    }
}
