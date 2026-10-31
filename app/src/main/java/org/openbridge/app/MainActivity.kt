package org.openbridge.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openbridge.app.ui.LibraryActions
import org.openbridge.app.ui.LibraryScreen
import org.openbridge.app.data.ExternalPhotoEditor
import org.openbridge.app.data.MediaDeletion
import org.openbridge.app.domain.ColorPreset
import org.openbridge.app.color.LookCodec
import org.json.JSONObject
import org.openbridge.app.camera.CameraLocationService
import org.openbridge.app.camera.CameraService
import org.openbridge.app.data.AppSettingsRepository

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        if (!AppSettingsRepository.get(this).state.value.autoDownload) return
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
            var requiredPermissions by remember { mutableStateOf(emptyList<String>()) }
            var editorCopy by rememberSaveable { mutableStateOf<String?>(null) }
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
                else model.showError("位置同步需要精确位置授权；未启动定位。")
            }
            val externalEditor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val copy = editorCopy?.let(Uri::parse)
                editorCopy = null
                if (copy != null) {
                    model.importPhotos(listOf(copy), persistPermission = false)
                    if (result.data?.data != null && result.data?.data != copy) {
                        model.showError("编辑器可能把结果另存到了其他位置。编辑副本已加入图库，也可以从手机导入另存的结果。")
                    }
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
            val cameraPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                val action = pendingCameraAction
                pendingCameraAction = null
                if (requiredPermissions.all { permission -> checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }) action?.invoke()
                else model.showError("连接功能需要附近设备权限；Android 12 及以下的 Wi‑Fi 连接还需要定位权限。")
            }
            fun withCameraPermissions(bluetooth: Boolean, scanning: Boolean = false, action: () -> Unit) {
                val required = buildList {
                    if (bluetooth && Build.VERSION.SDK_INT >= 31) {
                        if (scanning) add(Manifest.permission.BLUETOOTH_SCAN)
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    if (!bluetooth && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    if ((!bluetooth && Build.VERSION.SDK_INT <= 32) || (bluetooth && scanning && Build.VERSION.SDK_INT <= 30)) {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }
                if (required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) action()
                else {
                    requiredPermissions = required
                    pendingCameraAction = action
                    val notifications = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                    cameraPermissions.launch((required + notifications).toTypedArray())
                }
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
                    onSetCollapseBursts = model::setCollapseBursts,
                    onSetRating = model::setRating,
                    onSetPickState = model::setPickState,
                    onSetTags = model::setTags,
                    onExport = { options -> if (model.prepareExport(options)) exportPicker.launch(null) },
                    onCancelTask = model::cancelTask,
                    onRetryTask = model::retryTask,
                    onPauseExports = model::pauseExports,
                    onOpenOutput = { openOutput(Uri.parse(it), model) },
                    onDismissError = model::dismissError,
                    onConnectCamera = { config -> withCameraPermissions(false) { model.camera.connect(config) } },
                    onDisconnectCamera = model::disconnectCamera,
                    onRefreshCamera = model.camera::refresh,
                    onDiscoverCamera = { withCameraPermissions(false) { model.camera.discoverSta() } },
                    onOpenWifi = { startActivity(Intent(Settings.Panel.ACTION_WIFI)) },
                    onScanBle = { withCameraPermissions(true, scanning = true) { model.camera.scanBle() } },
                    onConnectBle = { camera -> withCameraPermissions(true) { model.camera.connectBle(camera) } },
                    onDiscoverClassic = { withCameraPermissions(true, scanning = true) { model.camera.discoverClassic() } },
                    onWakeWifi = model.camera::wakeWifi,
                    onDownloadSelection = model::downloadSelection,
                    onPauseDownloads = model.camera::pause,
                    onCancelDownload = model.camera::cancel,
                    onRetryDownload = model.camera::retry,
                    onRequestPreview = model.camera::requestPreview,
                    onSetCollection = model::setCollection,
                    onSetImportVisibility = model::setImportVisibility,
                    onSetOfflinePreviews = model::setOfflinePreviews,
                    onSetBatchSize = model.camera::setCatalogBatchSize,
                    onLoadMoreCamera = model.camera::loadMore,
                    onClearImportList = model::clearImportList,
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
                    onSetAutoDownload = model::setAutoDownload,
                    onSetAutoKind = model::setAutoKind,
                    onSetManualVariant = model::setManualVariant,
                    onSetPowerProfile = model::setPowerProfile,
                    onSetLocationSync = { enabled ->
                        if (!enabled) { model.setLocationSync(false); CameraLocationService.stop(this@MainActivity) }
                        else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            startLocationSync()
                        } else {
                            locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    },
                    onRefreshPairedDevices = { withCameraPermissions(true) { model.refreshPairedDevices() } },
                    onChooseControlDevice = model::chooseControlDevice,
                    onChooseTransferDevice = model::chooseTransferDevice,
                    onSetBluetoothRole = model::setBluetoothRole,
                    onConnectSavedControl = { withCameraPermissions(true) {
                        runCatching { model.camera.connectSavedControl() }.onFailure { model.showError(it.message ?: "无法连接控制设备") }
                    } },
                    onDisconnectControl = model.camera::disconnectControl,
                    onCheckBluetoothTransfers = { withCameraPermissions(true, scanning = true) { model.camera.checkBluetoothTransfers() } },
                    onOpenBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    onOpenAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    onImportColor = { colorPicker.launch(arrayOf("*/*")) },
                    onSetLook = model::setLook,
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
                                editorCopy = copy.uri.toString()
                                val edit = Intent(Intent.ACTION_EDIT).apply {
                                    setDataAndType(copy.uri, copy.mimeType)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                    clipData = ClipData.newRawUri("OpenBridge 编辑副本", copy.uri)
                                }
                                check(edit.resolveActivity(packageManager) != null) { "没有可处理此格式的照片编辑器" }
                                externalEditor.launch(Intent.createChooser(edit, "选择照片编辑器"))
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                editorCopy?.let { model.importPhotos(listOf(Uri.parse(it)), persistPermission = false) }
                                editorCopy = null
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
                    clipData = ClipData.newRawUri("OpenBridge 导出", uri)
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
