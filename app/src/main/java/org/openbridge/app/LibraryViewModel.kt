package org.openbridge.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openbridge.app.data.BatchExportManager
import org.openbridge.app.data.PhotoRepository
import org.openbridge.app.domain.CatalogResult
import org.openbridge.app.domain.ExportOptions
import org.openbridge.app.domain.ExportPreferences
import org.openbridge.app.domain.validateLogExport
import org.openbridge.app.domain.ExportStatus
import org.openbridge.app.domain.FilterSpec
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PickState
import org.openbridge.app.domain.SavedFilter
import org.openbridge.app.ui.LibraryUiState
import org.openbridge.app.ui.PhotoEditorState
import org.openbridge.app.camera.CameraController
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.color.LutRepository
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.FrameTemplate
import org.openbridge.app.frame.FrameCodec
import org.openbridge.app.frame.withFrameInitialization
import org.openbridge.app.domain.LutUsage
import org.openbridge.app.domain.ColorPreset
import org.openbridge.app.domain.PhotoCollection
import org.openbridge.app.domain.PhotoBrowserState
import org.openbridge.app.domain.LibrarySection
import org.openbridge.app.domain.ImportVisibility
import org.openbridge.app.domain.collectionAssets
import org.openbridge.app.data.AppSettingsRepository
import org.openbridge.app.domain.PowerProfile
import org.openbridge.app.domain.BluetoothRole
import org.openbridge.app.domain.AutoDownloadKind
import org.openbridge.app.domain.DownloadVariant
import org.openbridge.app.domain.selectionAfterQuery
import org.openbridge.app.camera.CameraLocationService
import org.openbridge.app.camera.DownloadStatus
import org.openbridge.app.data.MediaDeletion
import org.openbridge.app.data.PhotoPreviewLoader
import org.openbridge.app.data.withGeometryFrom

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private data class PendingExport(val assets: List<PhotoAsset>, val options: ExportOptions)

    private val repository = PhotoRepository.get(application)
    private val exporter = BatchExportManager(application)
    private val colors = LutRepository.get(application)
    private val preferences = AppSettingsRepository.get(application)
    private val mediaDeletion = MediaDeletion(application)
    val camera = CameraController.get(application)
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()
    private val operations = Mutex()
    private var operationCount = 0
    private var queryRunning = false
    private var pendingExport: PendingExport? = null
    private var viewerRetainedSelection = emptySet<String>()
    private val catalogQuery = CatalogQueryRunner(viewModelScope,
        onRunning = { queryRunning = it; updateLoading() },
        onResult = { catalog ->
            val visibleIds = catalog.groups.flatMap { it.members }.mapTo(hashSetOf()) { it.id }
            mutableState.update { it.copy(catalog = catalog,
                selectedIds = selectionAfterQuery(it.selectedIds, visibleIds, it.assets.mapTo(hashSetOf()) { photo -> photo.id },
                    viewerRetainedSelection)) }
        },
        onFailure = { error ->
            showError(if (error is LinkageError) "C++ 筛选库未能加载，请检查应用的原生库打包：${error.message}"
                else "筛选失败：${error.message ?: "无法读取目录"}")
        })

    init {
        viewModelScope.launch { exporter.tasks.collect { tasks -> mutableState.update { it.copy(tasks = tasks) } } }
        viewModelScope.launch { exporter.paused.collect { paused -> mutableState.update { it.copy(exportPaused = paused) } } }
        viewModelScope.launch { exporter.failure.collect { message -> if (message != null) showError(message) } }
        viewModelScope.launch { exporter.catalogChanged.conflate().collect { reloadAssets() } }
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
            val previous = state.value.settings.effectiveManualVariant
            mutableState.update { it.copy(settings = value) }
            if (previous != value.effectiveManualVariant) requestQuery()
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
        setLibrarySection(LibrarySection.ORIGINALS, null)
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
        viewerRetainedSelection = emptySet()
        val previous = state.value.browser
        mutableState.update { it.copy(browser = it.browser.withFilter(filter), selectedIds = emptySet()) }
        if (previous.collection == PhotoCollection.CAMERA_IMPORT && previous.importFilter.sortOrder != filter.sortOrder) {
            camera.setCatalogSort(filter.sortOrder)
        }
        requestQuery()
    }

    fun setCollection(collection: PhotoCollection) {
        if (state.value.browser.collection == collection) return
        changeBrowser(state.value.browser.copy(collection = collection))
    }

    fun setLibrarySection(section: LibrarySection, sourceId: String?) {
        val current = state.value.browser
        val parent = sourceId.takeIf { section == LibrarySection.EXPORTS }
        val browser = current.copy(collection = PhotoCollection.LIBRARY, librarySection = section, exportSourceId = parent,
            versionsFilter = if (parent == current.exportSourceId) current.versionsFilter else FilterSpec())
        if (browser != current) changeBrowser(browser)
    }

    private fun changeBrowser(browser: PhotoBrowserState) {
        viewerRetainedSelection = emptySet()
        mutableState.update { it.copy(browser = browser, catalog = CatalogResult(emptyList(), 0, 0), selectedIds = emptySet()) }
        if (browser.collection == PhotoCollection.CAMERA_IMPORT) camera.setCatalogSort(browser.importFilter.sortOrder)
        requestQuery()
    }

    fun setImportVisibility(visibility: ImportVisibility) {
        viewerRetainedSelection = emptySet()
        mutableState.update { it.copy(browser = it.browser.copy(importVisibility = visibility), selectedIds = emptySet()) }
        requestQuery()
    }

    fun setOfflinePreviews(visible: Boolean) {
        viewerRetainedSelection = emptySet()
        mutableState.update { it.copy(browser = it.browser.copy(showOfflinePreviews = visible), selectedIds = emptySet()) }
        requestQuery()
    }

    fun clearImportList() = runOperation {
        camera.clearImportList()
        mutableState.update { it.copy(selectedIds = emptySet()) }
        reloadAssets()
    }

    fun clearImportHistory() {
        if (state.value.clearingImportHistory) return
        mutableState.update { it.copy(clearingImportHistory = true) }
        runOperation {
            try { camera.clearImportHistory(); reloadAssets() }
            finally { mutableState.update { it.copy(clearingImportHistory = false) } }
        }
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
        if (current.browser.collection != PhotoCollection.LIBRARY) return
        val ids = collectionAssets(current.assets, current.browser, null)
            .filter { all || it.id in current.selectedIds }.mapTo(hashSetOf()) { it.id }
        runOperation {
            repository.setLibraryVisibility(ids, false)
            mutableState.update { it.copy(selectedIds = it.selectedIds - ids) }
            reloadAssets()
        }
    }

    fun promoteExports(ids: Set<String>) {
        val current = state.value
        if (current.browser.collection != PhotoCollection.LIBRARY || current.browser.librarySection != LibrarySection.EXPORTS || ids.isEmpty()) return
        val selected = collectionAssets(current.assets, current.browser, null).mapNotNullTo(hashSetOf()) { it.id.takeIf(ids::contains) }
        if (selected.isEmpty()) return
        runOperation {
            repository.promoteExports(selected)
            mutableState.update { it.copy(selectedIds = it.selectedIds - selected) }
            reloadAssets()
        }
    }

    fun rememberSystemEdit(sourceId: String, output: Uri) = runOperation {
        repository.rememberExport(sourceId, output)
        reloadAssets()
    }

    fun setCollapseStacks(enabled: Boolean) {
        mutableState.update { it.copy(collapseStacks = enabled) }
        requestQuery()
    }

    fun toggleSelection(id: String) {
        if (id !in state.value.selectedIds && state.value.catalog.groups.none { group -> group.members.any { it.id == id } }) return
        viewerRetainedSelection = viewerRetainedSelection - id
        mutableState.update { current ->
            val selected = current.selectedIds.toMutableSet()
            if (!selected.add(id)) selected.remove(id)
            current.copy(selectedIds = selected)
        }
    }

    fun selectVisible() {
        viewerRetainedSelection = emptySet()
        mutableState.update { current ->
            current.copy(selectedIds = current.catalog.groups.flatMap { it.members }.mapTo(linkedSetOf()) { it.id })
        }
    }

    fun clearSelection() {
        viewerRetainedSelection = emptySet()
        mutableState.update { it.copy(selectedIds = emptySet()) }
    }

    fun groupSelection() = changeStack(true)
    fun ungroupSelection() = changeStack(false)

    private fun changeStack(group: Boolean) {
        val ids = state.value.selectedIds.toSet()
        if (ids.isEmpty()) return
        runOperation {
            repository.setManualStack(ids, group)
            if (group) mutableState.update { it.copy(collapseStacks = true) }
            reloadAssets()
        }
    }

    fun setRating(rating: Int) {
        if (rating in 0..5) updateMarks(rating = rating)
    }

    fun setRating(rating: Int, ids: Set<String>) {
        if (rating !in 0..5) return
        retainViewerSelection(ids)
        updateMarks(ids, rating = rating)
    }

    fun setPickState(pickState: PickState) = updateMarks(pickState = pickState)
    fun setPickState(pickState: PickState, ids: Set<String>) {
        retainViewerSelection(ids)
        updateMarks(ids, pickState = pickState)
    }
    fun setTags(tags: Set<String>) = updateMarks(tags = tags.map(String::trim).filter(String::isNotEmpty).toSet())

    private fun updateMarks(
        ids: Set<String> = state.value.selectedIds.toSet(), rating: Int? = null, pickState: PickState? = null, tags: Set<String>? = null
    ) {
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

    fun prepareExport(options: ExportOptions, ids: Set<String> = state.value.selectedIds): Boolean {
        val current = state.value
        val assets = current.assets.filter { it.id in ids }
        if (assets.isEmpty()) {
            showError("请先选择需要导出的照片。")
            return false
        }
        if (assets.any { it.downloadState != DownloadState.DOWNLOADED }) {
            showError("请先导入所选照片的原片，再进行转换或导出；相机预览不会当作原片导出。")
            return false
        }
        try {
            options.validateLogExport(assets)
            options.look?.validateForExport(assets)
            options.frame?.let {
                require(options.format != org.openbridge.app.domain.ExportFormat.ORIGINAL) { "原件复制不应用边框，请选择 JPEG 或 PNG" }
            }
        }
        catch (error: IllegalArgumentException) { showError(error.message ?: "预设与所选照片不兼容"); return false }
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
            enqueueExport(request, destination.toString())
        }
    }

    fun exportToGallery() {
        val request = pendingExport ?: return
        pendingExport = null
        runOperation { enqueueExport(request, "") }
    }

    private suspend fun enqueueExport(request: PendingExport, destination: String) {
        exporter.enqueue(request.assets, request.options, destination)
        preferences.update { it.copy(exportPreferences = ExportPreferences.fromOptions(request.options, it.exportPreferences)) }
    }

    fun cancelTask(id: String) = exporter.cancel(id)
    fun clearExportHistory() = runOperation { exporter.clearHistory() }
    fun clearDownloadHistory() = runOperation { camera.clearDownloadHistory() }
    fun setAutoDownload(value: Boolean) {
        if (value) camera.resumeAutomaticDownloads()
        preferences.update { it.copy(autoDownload = value) }
    }
    fun setAutoKind(value: AutoDownloadKind) = preferences.update { it.copy(autoKind = value) }
    fun setManualVariant(value: DownloadVariant) = preferences.update { it.copy(manualVariant = value) }
    fun setPowerProfile(value: PowerProfile) = preferences.update { it.copy(powerProfile = value) }
    fun setLocationSync(value: Boolean) = preferences.update { it.copy(locationSync = value) }
    fun setPairingTimeSync(value: Boolean) = preferences.update { it.copy(pairingTimeSync = value) }
    fun setRequestedTimeSync(value: Boolean) = preferences.update { it.copy(requestedTimeSync = value) }
    fun chooseControlDevice(address: String) {
        if (preferences.state.value.sonySelected && address.isEmpty()) {
            camera.disconnect()
            preferences.update { it.copy(sonySelected = false, sonyAddress = "", sonyName = "") }
            return
        }
        if (address != preferences.state.value.controlAddress) camera.disconnect()
        preferences.update {
            it.copy(controlAddress = address, controlName = state.value.pairedDevices.find { device -> device.address == address }?.name.orEmpty())
        }
    }
    fun chooseTransferDevice(address: String) {
        if (address != preferences.state.value.transferAddress) camera.disconnectControl()
        preferences.update { it.copy(transferAddress = address) }
    }
    fun setBluetoothRole(value: BluetoothRole) = preferences.update { it.copy(bluetoothRole = value) }
    fun refreshPairedDevices() {
        runCatching { camera.pairedDevices() }.onSuccess { devices -> mutableState.update { it.copy(pairedDevices = devices) } }
            .onFailure { showError(it.message ?: "无法读取系统配对设备") }
    }

    fun setEditor(editor: PhotoEditorState?) = mutableState.update { it.copy(editor = editor) }
    fun setExportSelection(ids: List<String>?) = mutableState.update { it.copy(exportIds = ids?.toList()) }

    fun setLook(look: ColorLookSettings) {
        runCatching { look.validate(); mutableState.update { it.copy(look = look) } }.onFailure { showError(it.message ?: "预设无效") }
    }

    fun updatePreviewGeometry(source: PhotoAsset) {
        viewModelScope.launch {
            try {
                val updated = repository.updateSourceGeometry(source) ?: return@launch
                mutableState.update { state -> state.copy(assets = state.assets.map { it.withGeometryFrom(updated) }) }
                requestQuery()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w("ProofTray.Preview", "Unable to save refreshed photo geometry", error) }
        }
    }

    /** Like the current look, the current frame belongs to the library session, not to an export dialog. */
    fun setFrame(frame: FrameTemplate?) {
        try {
            withFrameInitialization { frame?.let(FrameCodec::validate) }
            mutableState.update { it.copy(frame = frame) }
        } catch (error: Exception) { showError(error.message ?: "边框模板无效") }
    }

    /** Filenames are display names, not color-space declarations. Explicit .oblook metadata remains authoritative. */
    fun importColor(uris: List<Uri>) {
        if (state.value.colorBusy) return
        if (uris.isEmpty()) {
            mutableState.update { it.finishColorImport(null) }
            return
        }
        mutableState.update { it.copy(colorBusy = true) }
        viewModelScope.launch {
            var firstId: String? = null
            try {
                val notices = mutableListOf<Pair<String, String>>()
                for (uri in uris.distinct()) {
                    var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "LUT" }
                    try {
                        val choice = colors.describeImport(uri)
                        name = choice.name
                        val result = colors.import(choice)
                        if (firstId == null) firstId = result.selectableId
                        result.notice?.let { notices += name to it }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { notices += name to (error.message ?: "导入失败") }
                }
                reloadColorLibrary()
                if (notices.isNotEmpty()) {
                    val reasons = notices.groupBy({ it.second }, { it.first })
                    showError(reasons.entries.joinToString("\n") { (reason, names) ->
                        val files = if (names.size == 1) names.first() else "${names.first()} 等 ${names.size} 个文件"
                        "$files：$reason"
                    })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(error.message ?: "无法读取预设库") }
            finally {
                mutableState.update { it.finishColorImport(firstId) }
            }
        }
    }

    fun setLutDefault(id: String, usage: LutUsage) = runOperation {
        colors.setDefault(id, usage)
        reloadColorLibrary()
    }

    fun renameLut(id: String, name: String) = runOperation {
        colors.rename(id, name)
        reloadColorLibrary()
    }

    fun deleteFrameTemplate(id: String) = runOperation {
        withContext(Dispatchers.IO) { org.openbridge.app.frame.FrameRepository(getApplication()).delete(id) }
        mutableState.update {
            it.copy(frame = it.frame?.takeUnless { template -> template.id == id }, deletedFrameTemplateIds = it.deletedFrameTemplateIds + id)
        }
    }

    fun setLutRemoved(id: String, removed: Boolean) = runOperation {
        colors.setRemoved(id, removed)
        reloadColorLibrary()
    }

    fun deleteLut(id: String, confirmedPresets: Set<String>) = runOperation {
        check(!state.value.colorBusy) { "请等待 LUT 导入完成" }
        check(exporter.tasks.value.none { it.requiresLut(id) }) { "有导出任务正在使用此 LUT，请完成或取消后再删除" }
        check(pendingExport?.options?.look?.lutId != id) { "请先完成或取消当前导出操作" }
        try { colors.deleteLut(id, confirmedPresets) }
        finally {
            reloadColorLibrary()
            mutableState.update { current ->
                if (current.look.lutId == id && (current.luts + current.removedLuts).none { it.id == id }) {
                    current.copy(look = ColorLookSettings())
                } else current
            }
        }
    }

    fun deleteColorPreset(id: String) = runOperation {
        colors.deletePreset(id)
        reloadColorLibrary()
    }

    fun saveColorPreset(name: String, look: ColorLookSettings) = runOperation {
        colors.savePreset(name, look)
        reloadColorLibrary()
    }

    fun exportColorPreset(preset: ColorPreset, destination: Uri) = runOperation { colors.exportPreset(preset, destination) }

    private suspend fun reloadColorLibrary() {
        val luts = colors.luts(includeRemoved = true)
        val presets = colors.presets()
        mutableState.update { it.copy(luts = luts.filterNot { lut -> lut.removed }, removedLuts = luts.filter { lut -> lut.removed },
            colorPresets = presets) }
    }
    fun downloadSelection() {
        val current = state.value
        val visible = current.catalog.groups.flatMap { it.members }.distinctBy { it.id }.filter { it.id in current.selectedIds }
        val selected = current.camera.catalogBatchSize?.let { visible.take(it) } ?: visible
        downloadPhotos(current, selected, clearSelection = true)
    }

    fun downloadPhoto(id: String) {
        val current = state.value
        // A viewer rating can filter this photo out while it remains open; enqueue still checks the active camera's catalogue.
        val selected = current.assets.firstOrNull { it.id == id }
        retainViewerSelection(setOf(id))
        downloadPhotos(current, listOfNotNull(selected), clearSelection = false)
    }

    private fun retainViewerSelection(ids: Set<String>) {
        viewerRetainedSelection = viewerRetainedSelection + ids.intersect(state.value.selectedIds)
    }

    private fun downloadPhotos(current: LibraryUiState, selected: List<PhotoAsset>, clearSelection: Boolean) {
        if (selected.isEmpty()) { showError("请先选择要导入的照片"); return }
        runOperation {
            val portable = current.settings.effectiveManualVariant == DownloadVariant.CAMERA_JPEG
            val local = selected.filter { !portable && it.downloadState == DownloadState.DOWNLOADED }.mapTo(hashSetOf()) { it.id }
            val remote = selected.filter { portable || it.downloadState != DownloadState.DOWNLOADED }.mapTo(linkedSetOf()) { it.id }
            if (remote.isNotEmpty()) {
                check(camera.state.value.connected) { "离线预览可以选片；导入原片需要重新连接相机" }
                camera.enqueue(remote)
            }
            repository.setLibraryVisibility(local, true)
            val submitted = local + remote
            if (clearSelection) mutableState.update { it.copy(selectedIds = it.selectedIds - submitted) }
            reloadAssets()
        }
    }
    fun disconnectCamera() {
        camera.disconnectByUser()
    }
    fun retryTask(id: String) = exporter.retry(id)
    fun pauseExports(paused: Boolean) = exporter.setPaused(paused)
    fun showError(message: String, needsSettings: Boolean = false) = mutableState.update {
        it.copy(errorMessage = message, errorNeedsSettings = needsSettings)
    }
    fun dismissError() = mutableState.update { it.copy(errorMessage = null, errorNeedsSettings = false) }

    private suspend fun reloadAssets() {
        val assets = repository.loadAssets()
        val historyCount = repository.importHistoryCount()
        mutableState.update { it.copy(assets = assets, importHistoryCount = historyCount) }
        requestQuery()
    }

    private suspend fun reloadSavedFilters() {
        val filters = repository.loadSavedFilters()
        mutableState.update { it.copy(savedFilters = filters) }
    }

    private fun requestQuery() {
        val current = state.value
        val source = current.camera.sourceId.takeIf { current.camera.connected }
        val members = collectionAssets(current.assets, current.browser, source, current.camera.catalogIds, current.settings.effectiveManualVariant)
        catalogQuery.submit(members, current.browser.filter, current.collapseStacks)
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
                showError(error.message ?: "操作失败，请检查文件权限和剩余空间。", needsSettings = error is SecurityException)
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
