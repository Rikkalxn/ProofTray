package org.openbridge.app.domain

enum class MediaFormat { JPEG, RAW, PNG, HEIF, TIFF, OTHER }
enum class PickState { UNMARKED, PICKED, REJECTED }
enum class DownloadState { REMOTE, QUEUED, DOWNLOADED }
enum class OrientationFilter { ANY, LANDSCAPE, PORTRAIT, SQUARE }
enum class SortOrder { NEWEST, OLDEST, RATING, NAME, SIZE }

data class PhotoAsset(
    val id: String,
    val uri: String,
    val name: String,
    val format: MediaFormat,
    val sizeBytes: Long = 0,
    val capturedAtMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val camera: String = "",
    val lens: String = "",
    val iso: Int? = null,
    val aperture: Double? = null,
    val exposureSeconds: Double? = null,
    val focalLengthMm: Double? = null,
    val rating: Int = 0,
    val pickState: PickState = PickState.UNMARKED,
    val downloadState: DownloadState = DownloadState.DOWNLOADED,
    val tags: Set<String> = emptySet(),
    val sourceId: String = "local",
    val folder: String = "",
    val burstId: String? = null,
    val metadataComplete: Boolean = false,
    val cameraFileKey: String? = null,
    val inImportList: Boolean = false,
    val hiddenFromLibrary: Boolean = false,
    val importedAtMillis: Long? = null,
    val previewUri: String? = null,
    val jpegCopyImportedAtMillis: Long? = null,
    val derivedFromId: String? = null, // Source photo of an app-generated export.
    val companionOfId: String? = null, // Original represented by a camera-generated portable JPEG.
    val cameraCaptureKey: String? = null,
    val previewRevision: String? = null,
    val stackId: String? = null,
    val sourceOrientation: Int? = null
)

data class FilterSpec(
    val text: String = "",
    val formats: Set<MediaFormat> = emptySet(),
    val minimumRating: Int = 0,
    val maximumRating: Int = 5,
    val pickStates: Set<PickState> = emptySet(),
    val downloadStates: Set<DownloadState> = emptySet(),
    val capturedAfterMillis: Long? = null,
    val capturedBeforeMillis: Long? = null,
    val minimumSizeBytes: Long? = null,
    val maximumSizeBytes: Long? = null,
    val minimumIso: Int? = null,
    val maximumIso: Int? = null,
    val minimumAperture: Double? = null,
    val maximumAperture: Double? = null,
    val minimumExposureSeconds: Double? = null,
    val maximumExposureSeconds: Double? = null,
    val minimumFocalLengthMm: Double? = null,
    val maximumFocalLengthMm: Double? = null,
    val camera: String = "",
    val lens: String = "",
    val tags: Set<String> = emptySet(),
    val orientation: OrientationFilter = OrientationFilter.ANY,
    val pairedRawJpegOnly: Boolean = false,
    val includeUnknownMetadata: Boolean = true,
    val sortOrder: SortOrder = SortOrder.NEWEST
)

data class PhotoGroup(val id: String, val members: List<PhotoAsset>, val inferredBurst: Boolean = false)
data class CatalogResult(val groups: List<PhotoGroup>, val matchedCount: Int, val pendingMetadataCount: Int)
data class SavedFilter(val id: String, val name: String, val spec: FilterSpec)

enum class ExportFormat { ORIGINAL, JPEG, PNG }
enum class ExportDestination { GALLERY, DIRECTORY }
enum class ExportStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

data class ExportOptions(
    val format: ExportFormat = ExportFormat.JPEG, val jpegQuality: Int = 95, val maxEdge: Int = 4096,
    val look: ColorLookSettings? = null, val destination: ExportDestination = ExportDestination.GALLERY, val frame: FrameSnapshot? = null
)
data class ExportTask(
    val id: String,
    val asset: PhotoAsset,
    val options: ExportOptions,
    val destinationUri: String,
    val status: ExportStatus = ExportStatus.QUEUED,
    val progress: Float = 0f,
    val outputUri: String? = null,
    val message: String? = null
) {
    fun requiresLut(id: String): Boolean = options.look?.lutId == id && status in setOf(ExportStatus.QUEUED, ExportStatus.RUNNING)

    val canClearHistory: Boolean get() = status == ExportStatus.COMPLETED ||
        (status in setOf(ExportStatus.FAILED, ExportStatus.CANCELLED) && outputUri == null)
}
