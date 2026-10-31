package org.openbridge.app.ui

import org.openbridge.app.domain.CatalogResult
import org.openbridge.app.domain.ExportOptions
import org.openbridge.app.domain.ExportTask
import org.openbridge.app.domain.FilterSpec
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PickState
import org.openbridge.app.domain.SavedFilter
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.ColorPreset
import org.openbridge.app.domain.LutAsset
import org.openbridge.app.domain.PhotoBrowserState
import org.openbridge.app.domain.PhotoCollection
import org.openbridge.app.domain.ImportVisibility
import org.openbridge.app.domain.builtInColorPresets
import org.openbridge.app.camera.BleCamera
import org.openbridge.app.camera.BleState
import org.openbridge.app.camera.CameraConnection
import org.openbridge.app.camera.CameraEndpoint
import org.openbridge.app.camera.CameraState
import org.openbridge.app.camera.AutoDownloadState
import org.openbridge.app.camera.LocationSyncState
import org.openbridge.app.data.AppSettings
import org.openbridge.app.data.PowerProfile
import org.openbridge.app.data.BluetoothRole
import org.openbridge.app.domain.AutoDownloadKind
import org.openbridge.app.domain.DownloadVariant

data class LibraryUiState(
    val assets: List<PhotoAsset> = emptyList(),
    val catalog: CatalogResult = CatalogResult(emptyList(), 0, 0),
    val browser: PhotoBrowserState = PhotoBrowserState(),
    val savedFilters: List<SavedFilter> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val collapseBursts: Boolean = true,
    val tasks: List<ExportTask> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val exportPaused: Boolean = false,
    val camera: CameraState = CameraState(),
    val ble: BleState = BleState(),
    val cameraEndpoints: List<CameraEndpoint> = emptyList(),
    val look: ColorLookSettings = ColorLookSettings(),
    val luts: List<LutAsset> = emptyList(),
    val colorPresets: List<ColorPreset> = builtInColorPresets,
    val colorBusy: Boolean = false,
    val settings: AppSettings = AppSettings(), val automatic: AutoDownloadState = AutoDownloadState(),
    val location: LocationSyncState = LocationSyncState(), val pairedDevices: List<BleCamera> = emptyList()
) {
    val filter: FilterSpec get() = browser.filter
}

data class LibraryActions(
    val onImport: () -> Unit,
    val onFilterChange: (FilterSpec) -> Unit,
    val onSaveFilter: (String) -> Unit,
    val onDeleteSavedFilter: (String) -> Unit,
    val onToggleSelection: (String) -> Unit,
    val onSelectVisible: () -> Unit,
    val onClearSelection: () -> Unit,
    val onSetCollapseBursts: (Boolean) -> Unit,
    val onSetRating: (Int) -> Unit,
    val onSetPickState: (PickState) -> Unit,
    val onSetTags: (Set<String>) -> Unit,
    val onExport: (ExportOptions) -> Unit,
    val onCancelTask: (String) -> Unit,
    val onRetryTask: (String) -> Unit,
    val onPauseExports: (Boolean) -> Unit,
    val onOpenOutput: (String) -> Unit,
    val onDismissError: () -> Unit,
    val onConnectCamera: (CameraConnection) -> Unit,
    val onDisconnectCamera: () -> Unit,
    val onRefreshCamera: () -> Unit,
    val onDiscoverCamera: () -> Unit,
    val onOpenWifi: () -> Unit,
    val onScanBle: () -> Unit,
    val onConnectBle: (BleCamera) -> Unit,
    val onDiscoverClassic: () -> Unit,
    val onWakeWifi: () -> Unit,
    val onDownloadSelection: () -> Unit,
    val onPauseDownloads: (Boolean) -> Unit,
    val onCancelDownload: (String) -> Unit,
    val onRetryDownload: (String) -> Unit,
    val onRequestPreview: (String, Boolean) -> (() -> Unit),
    val onSetCollection: (PhotoCollection) -> Unit,
    val onSetImportVisibility: (ImportVisibility) -> Unit,
    val onSetOfflinePreviews: (Boolean) -> Unit,
    val onSetBatchSize: (Int) -> Unit,
    val onLoadMoreCamera: () -> Unit,
    val onClearImportList: () -> Unit,
    val onRemoveFromImport: () -> Unit,
    val onRemoveFromLibrary: (Boolean) -> Unit,
    val onDeletePhotos: (List<PhotoAsset>) -> Unit,
    val onBeginRegistration: () -> Unit,
    val onConfirmRegistration: () -> Unit,
    val onSystemEdit: () -> Unit,
    val onImportColor: () -> Unit,
    val onSetLook: (ColorLookSettings) -> Unit,
    val onSaveColorPreset: (String, ColorLookSettings) -> Unit,
    val onExportColorPreset: (ColorPreset) -> Unit,
    val onOpenColorSource: (String) -> Unit,
    val onSetAutoDownload: (Boolean) -> Unit,
    val onSetAutoKind: (AutoDownloadKind) -> Unit,
    val onSetManualVariant: (DownloadVariant) -> Unit,
    val onSetPowerProfile: (PowerProfile) -> Unit,
    val onSetLocationSync: (Boolean) -> Unit,
    val onRefreshPairedDevices: () -> Unit,
    val onChooseControlDevice: (String) -> Unit,
    val onChooseTransferDevice: (String) -> Unit,
    val onSetBluetoothRole: (BluetoothRole) -> Unit,
    val onConnectSavedControl: () -> Unit,
    val onDisconnectControl: () -> Unit,
    val onCheckBluetoothTransfers: () -> Unit,
    val onOpenBluetoothSettings: () -> Unit,
    val onOpenAppSettings: () -> Unit
)
