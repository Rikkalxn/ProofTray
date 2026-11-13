package org.openbridge.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openbridge.app.ui.LibraryActions
import org.openbridge.app.ui.LibraryScreen
import org.openbridge.app.data.ExternalPhotoEditor
import org.openbridge.app.data.MediaDeletion
import org.openbridge.app.domain.ColorPreset
import org.openbridge.app.domain.ExportDestination
import org.openbridge.app.domain.AutoDownloadKind
import org.openbridge.app.color.LookCodec
import org.json.JSONObject
import org.openbridge.app.camera.CameraController
import org.openbridge.app.camera.CameraLocationService
import org.openbridge.app.camera.CameraService
import org.openbridge.app.data.AppSettingsRepository

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        if (!AppSettingsRepository.get(this).state.value.automaticEnabled) return
        if (CameraController.get(this).state.value.manuallyDisconnected) return
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            runCatching { CameraService.start(this) }.onFailure { Log.w("OpenBridgeBluetooth", "automatic foreground service unavailable", it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: LibraryViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            var pendingWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            var requiredPermissions by rememberSaveable { mutableStateOf(emptyArray<String>()) }
            var pairedWifiTarget by rememberSaveable { mutableStateOf<String?>(null) }
            var pairedWifiGeneration by rememberSaveable { mutableStateOf(-1) }
            var editorCopy by rememberSaveable { mutableStateOf<String?>(null) }
            var editorSourceId by rememberSaveable { mutableStateOf<String?>(null) }
            var preparingEditor by remember { mutableStateOf(false) }
            val mediaDeletion = remember { MediaDeletion(applicationContext) }
            var pendingDeletion by rememberSaveable { mutableStateOf<String?>(null) }
            var preparingDeletion by remember { mutableStateOf(false) }
            val deleteConfirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                val token = pendingDeletion
                pendingDeletion = null
                if (token != null) {
                    if (result.resultCode == Activity.RESULT_OK) model.completeMediaDeletion(token)
                    else lifecycleScope.launch { mediaDeletion.finish(token) }
                }
            }
            fun startLocationSync() {
                try {
                    model.setLocationSync(true)
                    CameraLocationService.startFromForeground(this@MainActivity)
                } catch (error: Exception) {
                    model.setLocationSync(false)
                    model.showError("无法启动位置同步：${error.message}")
                }
            }
            val locationPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startLocationSync()
                else model.showError("位置同步需要精确位置授权；未启动定位。", needsSettings = true)
            }
            fun finishEditorCopy(): Uri? {
                val copy = editorCopy?.let(Uri::parse)
                val source = editorSourceId
                editorCopy = null
                editorSourceId = null
                if (copy != null && source != null) model.rememberSystemEdit(source, copy)
                return copy
            }
            val externalEditor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val copy = finishEditorCopy()
                if (copy != null && result.data?.data != null && result.data?.data != copy) {
                    model.showError("编辑器另存的结果可从手机添加。编辑副本可在「导出」中查看。")
                }
            }
            val colorPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> model.importColor(uris) }
            var exportingPreset by rememberSaveable { mutableStateOf<String?>(null) }
            val presetDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                val record = exportingPreset
                exportingPreset = null
                if (uri != null && record != null) {
                    try {
                        val json = LookCodec.checkedJson(record)
                        model.exportColorPreset(ColorPreset(json.getString("id"), json.getString("name"),
                            LookCodec.decode(json.getJSONObject("look"))), uri)
                    } catch (error: Exception) { model.showError("无法恢复待导出的预设：${error.message}") }
                }
            }
            fun runCameraAction(action: () -> Unit) {
                try { action() } catch (error: Exception) {
                    model.showError(error.message ?: "无法启动相机操作，请在前台重试", needsSettings = error is SecurityException)
                }
            }
            val phoneWifi = remember { applicationContext.getSystemService(WifiManager::class.java) }
            fun connectRequestedCamera() {
                val target = pairedWifiTarget ?: return
                pairedWifiTarget = null
                val camera = model.camera.state.value
                if (AppSettingsRepository.get(this@MainActivity).state.value.selectedAddress == target && camera.generation == pairedWifiGeneration &&
                    !camera.connected && !camera.connecting) model.camera.wakeWifi()
            }
            val wifiPanel = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val action = pendingWifiAction ?: if (pairedWifiTarget != null) ::connectRequestedCamera else null
                pendingWifiAction = null
                if (action != null) lifecycleScope.launch {
                    val enabled = withTimeoutOrNull(4000) { while (phoneWifi?.isWifiEnabled != true) delay(150); true } == true
                    if (enabled) runCameraAction(action) else {
                        pairedWifiTarget = null
                        model.showError("手机 Wi-Fi 尚未开启，可以再次点「切换到 Wi-Fi」")
                    }
                }
            }
            fun openWifiPanel() {
                try { wifiPanel.launch(Intent(Settings.Panel.ACTION_WIFI)) }
                catch (_: ActivityNotFoundException) {
                    try { wifiPanel.launch(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    catch (_: ActivityNotFoundException) {
                        pendingWifiAction = null
                        pairedWifiTarget = null
                        model.showError("手机未提供系统 Wi-Fi 面板")
                    }
                }
            }
            fun withPhoneWifi(action: () -> Unit) {
                if (phoneWifi?.isWifiEnabled == true) action()
                else if (pendingWifiAction == null) { pendingWifiAction = action; openWifiPanel() }
            }
            fun withRequestedCameraWifi() {
                withPhoneWifi(::connectRequestedCamera)
            }
            val cameraPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                val action = pendingCameraAction
                pendingCameraAction = null
                if (requiredPermissions.all { permission -> checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }) {
                    if (action != null) runCameraAction(action)
                    else if (pairedWifiTarget != null) withRequestedCameraWifi()
                }
                else {
                    pairedWifiTarget = null
                    model.showError("连接功能需要附近设备权限；Android 12 及以下的 Wi‑Fi 连接还需要定位权限。", needsSettings = true)
                }
            }
            fun withCameraPermissions(bluetooth: Boolean, scanning: Boolean = false, wifi: Boolean = !bluetooth, action: () -> Unit) {
                if (pendingCameraAction != null) return
                val required = buildList {
                    if (bluetooth && Build.VERSION.SDK_INT >= 31) {
                        if (scanning) add(Manifest.permission.BLUETOOTH_SCAN)
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    if (wifi && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    if ((wifi && Build.VERSION.SDK_INT <= 32) || (bluetooth && scanning && Build.VERSION.SDK_INT <= 30)) {
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }
                if (required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) runCameraAction(action)
                else {
                    requiredPermissions = required.toTypedArray()
                    pendingCameraAction = action
                    val notifications = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                    cameraPermissions.launch((required + notifications).toTypedArray())
                }
            }
            fun switchToCameraWifi() {
                val target = AppSettingsRepository.get(this@MainActivity).state.value.selectedAddress
                if (target.isBlank() || pairedWifiTarget != null || pendingCameraAction != null || pendingWifiAction != null) return
                pairedWifiTarget = target
                pairedWifiGeneration = model.camera.state.value.generation
                withCameraPermissions(true, scanning = true, wifi = true) { withRequestedCameraWifi() }
            }
            val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                model.importPhotos(uris)
            }
            val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                model.exportTo(uri)
            }
            LibraryScreen(
                state,
                LibraryActions(
                    onImport = { importPicker.launch(arrayOf("*/*")) },
                    onFilterChange = model::setFilter,
                    onSaveFilter = model::saveFilter,
                    onDeleteSavedFilter = model::deleteSavedFilter,
                    onToggleSelection = model::toggleSelection,
                    onSelectVisible = model::selectVisible,
                    onClearSelection = model::clearSelection,
                    onGroupSelection = model::groupSelection,
                    onUngroupSelection = model::ungroupSelection,
                    onSetCollapseStacks = model::setCollapseStacks,
                    onSetRating = model::setRating,
                    onSetPickState = model::setPickState,
                    onRatePhoto = { id, rating -> model.setRating(rating, setOf(id)) },
                    onPickPhoto = { id, pick -> model.setPickState(pick, setOf(id)) },
                    onSetTags = model::setTags,
                    onExport = { options -> if (model.prepareExport(options)) {
                        if (options.destination == ExportDestination.GALLERY) model.exportToGallery() else exportPicker.launch(null)
                    } },
                    onExportPhotos = { ids, options -> if (model.prepareExport(options, ids)) {
                        if (options.destination == ExportDestination.GALLERY) model.exportToGallery() else exportPicker.launch(null)
                    } },
                    onCancelTask = model::cancelTask,
                    onRetryTask = model::retryTask,
                    onPauseExports = model::pauseExports,
                    onClearExportHistory = model::clearExportHistory,
                    onClearDownloadHistory = model::clearDownloadHistory,
                    onOpenOutput = { openOutput(Uri.parse(it), model) },
                    onDismissError = model::dismissError,
                    onConnectCamera = { config ->
                        withCameraPermissions(state.settings.sonySelected && !config.sonyCamera, wifi = true) {
                            withPhoneWifi { model.camera.connect(config) }
                        }
                    },
                    onDisconnectCamera = model::disconnectCamera,
                    onDisconnectWifi = model.camera::disconnectWifiSession,
                    onRetryConnection = { withCameraPermissions(state.camera.bluetoothAssisted,
                        scanning = state.camera.bluetoothAssisted, wifi = true) { withPhoneWifi { model.camera.retryConnection() } } },
                    onRefreshCamera = model.camera::refresh,
                    onDiscoverCamera = { withCameraPermissions(false) { model.camera.discoverSta() } },
                    onOpenWifi = ::openWifiPanel,
                    onScanBle = { withCameraPermissions(true, scanning = true) { model.camera.scanBle() } },
                    onConnectBle = { camera -> withCameraPermissions(true) { model.camera.connectBle(camera) } },
                    onDiscoverClassic = { withCameraPermissions(true, scanning = true) { model.camera.discoverClassic() } },
                    onStopBleScan = model.camera::stopBleScan,
                    onWakeWifi = { switchToCameraWifi() },
                    onSwitchToBluetooth = { withCameraPermissions(true, scanning = true) { model.camera.switchToNikonBluetooth() } },
                    onDownloadSelection = model::downloadSelection,
                    onDownloadPhoto = model::downloadPhoto,
                    onPauseDownloads = model.camera::pause,
                    onCancelDownload = model.camera::cancel,
                    onRetryDownload = model.camera::retry,
                    onRequestPreview = model.camera::requestPreview,
                    onPreviewGeometry = model::updatePreviewGeometry,
                    onSetCollection = model::setCollection,
                    onSetLibrarySection = model::setLibrarySection,
                    onPromoteExports = model::promoteExports,
                    onSetImportVisibility = model::setImportVisibility,
                    onSetOfflinePreviews = model::setOfflinePreviews,
                    onSetBatchSize = model.camera::setCatalogBatchSize,
                    onLoadMoreCamera = model.camera::loadMore,
                    onAutoLoadMoreCamera = model.camera::autoLoadMore,
                    onStopCatalogRead = model.camera::stopCatalogRead,
                    onJumpToDay = model.camera::jumpToDay,
                    onClearImportList = model::clearImportList,
                    onClearImportHistory = model::clearImportHistory,
                    onRemoveFromImport = model::removeFromImport,
                    onRemoveFromLibrary = model::removeFromLibrary,
                    onDeletePhotos = { assets ->
                        if (!preparingDeletion && pendingDeletion == null && model.validateDeletion(assets)) lifecycleScope.launch {
                            preparingDeletion = true
                            var token: String? = null
                            try {
                                val plan = mediaDeletion.prepare(assets)
                                val requestToken = mediaDeletion.remember(plan.targets)
                                token = requestToken
                                pendingDeletion = requestToken
                                val request = plan.confirmation
                                if (request != null) deleteConfirmation.launch(IntentSenderRequest.Builder(request.intentSender).build())
                                else { model.deleteMediaOnAndroid10(requestToken); pendingDeletion = null }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                token?.let { mediaDeletion.finish(it) }
                                pendingDeletion = null
                                model.showError("未能发起删除：${error.message}")
                            } finally { preparingDeletion = false }
                        }
                    },
                    onBeginRegistration = model.camera::beginRegistration,
                    onConfirmRegistration = model.camera::confirmRegistration,
                    onSetAutoDownload = { enabled ->
                        if (enabled) withCameraPermissions(state.settings.autoKind == AutoDownloadKind.CAMERA_JPEG,
                            scanning = true) { model.setAutoDownload(true) } else model.setAutoDownload(false)
                    },
                    onSetAutoKind = { kind ->
                        if (kind != state.settings.autoKind && state.settings.autoDownload) {
                            withCameraPermissions(kind == AutoDownloadKind.CAMERA_JPEG, scanning = true) {
                                model.setAutoKind(kind); model.camera.resumeAutomaticDownloads()
                            }
                        } else model.setAutoKind(kind)
                    },
                    onSetManualVariant = model::setManualVariant,
                    onSetPowerProfile = model::setPowerProfile,
                    onSetLocationSync = { enabled ->
                        if (!enabled) { model.setLocationSync(false); CameraLocationService.stop(this@MainActivity) }
                        else withCameraPermissions(state.settings.sonySelected, wifi = false) {
                            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                startLocationSync()
                            } else locationPermissions.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    },
                    onRefreshPairedDevices = { withCameraPermissions(true) { model.refreshPairedDevices() } },
                    onChooseControlDevice = model::chooseControlDevice,
                    onChooseTransferDevice = model::chooseTransferDevice,
                    onSetBluetoothRole = model::setBluetoothRole,
                    onConnectSavedControl = { withCameraPermissions(true, scanning = true) {
                        runCatching { model.camera.connectSavedControl() }.onFailure { model.showError(it.message ?: "无法连接控制设备") }
                    } },
                    onDisconnectControl = model.camera::disconnectControl,
                    onCheckBluetoothTransfers = { withCameraPermissions(true, scanning = true) { model.camera.checkBluetoothTransfers() } },
                    onOpenBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    onOpenAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    onImportColor = { colorPicker.launch(arrayOf("*/*")) },
                    onSetLutDefault = model::setLutDefault,
                    onRenameLut = model::renameLut,
                    onSetLutRemoved = model::setLutRemoved,
                    onDeleteLut = model::deleteLut,
                    onDeleteColorPreset = model::deleteColorPreset,
                    onSetLook = model::setLook,
                    onSetFrame = model::setFrame,
                    onSetEditor = model::setEditor,
                    onSetPairingTimeSync = model::setPairingTimeSync,
                    onSetRequestedTimeSync = model::setRequestedTimeSync,
                    onSetExportSelection = model::setExportSelection,
                    onDeleteFrameTemplate = model::deleteFrameTemplate,
                    onSaveColorPreset = model::saveColorPreset,
                    onExportColorPreset = { preset ->
                        exportingPreset = JSONObject().apply {
                            put("id", preset.id); put("name", preset.name); put("look", LookCodec.encode(preset.look))
                        }.toString()
                        presetDestination.launch(preset.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + ".oblook")
                    },
                    onOpenColorSource = { url ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        catch (_: ActivityNotFoundException) { model.showError("没有找到可以打开网页的浏览器。") }
                    },
                    onSystemEdit = {
                        val selected = state.assets.filter { it.id in state.selectedIds }
                        if (selected.size != 1) model.showError("系统编辑目前一次处理一张照片。")
                        else if (!preparingEditor) lifecycleScope.launch {
                            preparingEditor = true
                            try {
                                val copy = ExternalPhotoEditor(applicationContext).prepare(selected.single())
                                editorSourceId = selected.single().id
                                editorCopy = copy.uri.toString()
                                val edit = Intent(Intent.ACTION_EDIT).apply {
                                    setDataAndType(copy.uri, copy.mimeType)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                    clipData = ClipData.newRawUri("编辑副本", copy.uri)
                                }
                                check(edit.resolveActivity(packageManager) != null) { "没有可处理此格式的照片编辑器" }
                                externalEditor.launch(Intent.createChooser(edit, "选择照片编辑器"))
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                finishEditorCopy()
                                model.showError("无法打开系统编辑器：${error.message}")
                            }
                            finally { preparingEditor = false }
                        }
                    }
                ), showAboutOnStart = intent.getBooleanExtra("open_about", false)
            )
        }
    }

    private fun openOutput(uri: Uri, model: LibraryViewModel) {
        lifecycleScope.launch {
            try {
                val mime = withContext(Dispatchers.IO) { contentResolver.getType(uri) ?: "application/octet-stream" }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("导出文件", uri)
                }
                startActivity(Intent.createChooser(intent, "打开导出文件"))
            } catch (_: ActivityNotFoundException) {
                model.showError("未找到可以打开这种文件格式的应用。")
            } catch (error: SecurityException) {
                model.showError("无法访问导出文件：${error.message}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                model.showError("无法打开导出文件：${error.message ?: "文件提供方暂不可用"}")
            }
        }
    }
}
