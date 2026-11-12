package org.openbridge.app.ui

import org.openbridge.app.domain.CatalogResult
import org.openbridge.app.domain.ExportOptions
import org.openbridge.app.domain.ExportTask
import org.openbridge.app.domain.FilterSpec
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PickState
import org.openbridge.app.domain.SavedFilter
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.FrameTemplate
import org.openbridge.app.domain.LutUsage
import org.openbridge.app.domain.ColorPreset
import org.openbridge.app.domain.LutAsset
import org.openbridge.app.domain.PhotoBrowserState
import org.openbridge.app.domain.PhotoCollection
import org.openbridge.app.domain.LibrarySection
import org.openbridge.app.domain.ImportVisibility
import org.openbridge.app.camera.BleCamera
import org.openbridge.app.camera.BleState
import org.openbridge.app.camera.CameraConnection
import org.openbridge.app.camera.CameraEndpoint
import org.openbridge.app.camera.CameraState
import org.openbridge.app.camera.SonyCaptureDay
import org.openbridge.app.camera.AutoDownloadState
import org.openbridge.app.camera.LocationSyncState
import org.openbridge.app.domain.AppSettings
import org.openbridge.app.domain.PowerProfile
import org.openbridge.app.domain.BluetoothRole
import org.openbridge.app.domain.AutoDownloadKind
import org.openbridge.app.domain.DownloadVariant

data class LibraryUiState(
    val assets: List<PhotoAsset> = emptyList(),
    val catalog: CatalogResult = CatalogResult(emptyList(), 0, 0),
    val browser: PhotoBrowserState = PhotoBrowserState(),
    val savedFilters: List<SavedFilter> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val collapseStacks: Boolean = true,
    val tasks: List<ExportTask> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val errorNeedsSettings: Boolean = false,
    val importHistoryCount: Long = 0, val clearingImportHistory: Boolean = false,
    val exportPaused: Boolean = false,
    val camera: CameraState = CameraState(),
    val importWifiRequired: Boolean = false,
    val ble: BleState = BleState(),
    val cameraEndpoints: List<CameraEndpoint> = emptyList(),
    val look: ColorLookSettings = ColorLookSettings(),
    val frame: FrameTemplate? = null,
    val editor: PhotoEditorState? = null,
    val exportIds: List<String>? = null,
    val deletedFrameTemplateIds: Set<String> = emptySet(),
    val luts: List<LutAsset> = emptyList(),
    val removedLuts: List<LutAsset> = emptyList(),
    val colorPresets: List<ColorPreset> = emptyList(),
    val colorBusy: Boolean = false,
    val colorImportRevision: Long = 0, val importedLookKey: String? = null,
    val settings: AppSettings = AppSettings(), val automatic: AutoDownloadState = AutoDownloadState(),
    val location: LocationSyncState = LocationSyncState(), val pairedDevices: List<BleCamera> = emptyList()
) {
    val filter: FilterSpec get() = browser.filter

    fun finishColorImport(firstImportedId: String?): LibraryUiState {
        val key = luts.firstOrNull { it.id == firstImportedId && it.defaultUsage.unavailableReason == null }?.look()?.lutKey
            ?: colorPresets.firstOrNull { it.id == firstImportedId && it.look.usage.unavailableReason == null }?.let { "preset:${it.id}" }
        return copy(colorBusy = false, colorImportRevision = colorImportRevision + 1, importedLookKey = key)
    }
}

data class LibraryActions(
    val onImport: () -> Unit,
    val onFilterChange: (FilterSpec) -> Unit,
    val onSaveFilter: (String) -> Unit,
    val onDeleteSavedFilter: (String) -> Unit,
    val onToggleSelection: (String) -> Unit,
    val onSelectVisible: () -> Unit,
    val onClearSelection: () -> Unit,
    val onGroupSelection: () -> Unit,
    val onUngroupSelection: () -> Unit,
    val onSetCollapseStacks: (Boolean) -> Unit,
    val onSetRating: (Int) -> Unit,
    val onSetPickState: (PickState) -> Unit,
    val onRatePhoto: (String, Int) -> Unit,
    val onPickPhoto: (String, PickState) -> Unit,
    val onSetTags: (Set<String>) -> Unit,
    val onExport: (ExportOptions) -> Unit,
    val onExportPhotos: (Set<String>, ExportOptions) -> Unit,
    val onCancelTask: (String) -> Unit,
    val onRetryTask: (String) -> Unit,
    val onPauseExports: (Boolean) -> Unit,
    val onClearExportHistory: () -> Unit,
    val onClearDownloadHistory: () -> Unit,
    val onOpenOutput: (String) -> Unit,
    val onDismissError: () -> Unit,
    val onConnectCamera: (CameraConnection) -> Unit,
    val onDisconnectCamera: () -> Unit,
    val onDisconnectWifi: () -> Unit,
    val onRetryConnection: () -> Unit,
    val onRefreshCamera: () -> Unit,
    val onDiscoverCamera: () -> Unit,
    val onOpenWifi: () -> Unit,
    val onScanBle: () -> Unit,
    val onStopBleScan: () -> Unit,
    val onConnectBle: (BleCamera) -> Unit,
    val onDiscoverClassic: () -> Unit,
    val onWakeWifi: () -> Unit,
    val onSwitchToBluetooth: () -> Unit,
    val onDownloadSelection: () -> Unit,
    val onDownloadPhoto: (String) -> Unit,
    val onPauseDownloads: (Boolean) -> Unit,
    val onCancelDownload: (String) -> Unit,
    val onRetryDownload: (String) -> Unit,
    val onRequestPreview: (String, Boolean) -> (() -> Unit),
    val onPreviewGeometry: (PhotoAsset) -> Unit,
    val onSetCollection: (PhotoCollection) -> Unit,
    val onSetLibrarySection: (LibrarySection, String?) -> Unit,
    val onPromoteExports: (Set<String>) -> Unit,
    val onSetImportVisibility: (ImportVisibility) -> Unit,
    val onSetOfflinePreviews: (Boolean) -> Unit,
    val onSetBatchSize: (Int?) -> Unit,
    val onLoadMoreCamera: () -> Unit,
    val onAutoLoadMoreCamera: (Long) -> Unit,
    val onStopCatalogRead: () -> Unit,
    val onJumpToDay: (SonyCaptureDay) -> Unit,
    val onClearImportList: () -> Unit,
    val onClearImportHistory: () -> Unit,
    val onRemoveFromImport: () -> Unit,
    val onRemoveFromLibrary: (Boolean) -> Unit,
    val onDeletePhotos: (List<PhotoAsset>) -> Unit,
    val onBeginRegistration: () -> Unit,
    val onConfirmRegistration: () -> Unit,
    val onSystemEdit: () -> Unit,
    val onImportColor: () -> Unit,
    val onSetLutDefault: (String, LutUsage) -> Unit,
    val onRenameLut: (String, String) -> Unit,
    val onSetLutRemoved: (String, Boolean) -> Unit,
    val onDeleteLut: (String, Set<String>) -> Unit,
    val onDeleteColorPreset: (String) -> Unit,
    val onSetLook: (ColorLookSettings) -> Unit,
    val onSetFrame: (FrameTemplate?) -> Unit,
    val onSetEditor: (PhotoEditorState?) -> Unit,
    val onSetExportSelection: (List<String>?) -> Unit,
    val onDeleteFrameTemplate: (String) -> Unit,
    val onSaveColorPreset: (String, ColorLookSettings) -> Unit,
    val onExportColorPreset: (ColorPreset) -> Unit,
    val onOpenColorSource: (String) -> Unit,
    val onSetAutoDownload: (Boolean) -> Unit,
    val onSetAutoKind: (AutoDownloadKind) -> Unit,
    val onSetManualVariant: (DownloadVariant) -> Unit,
    val onSetPowerProfile: (PowerProfile) -> Unit,
    val onSetLocationSync: (Boolean) -> Unit,
    val onSetPairingTimeSync: (Boolean) -> Unit,
    val onSetRequestedTimeSync: (Boolean) -> Unit,
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
