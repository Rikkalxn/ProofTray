package org.openbridge.app.domain

import org.openbridge.app.util.sha256Hex
import java.util.Locale

enum class PhotoCollection { LIBRARY, CAMERA_IMPORT }
enum class LibrarySection(val label: String) { ORIGINALS("照片"), EXPORTS("导出") }
enum class ImportVisibility { NEW, ALL, IMPORTED }

data class PhotoBrowserState(
    val collection: PhotoCollection = PhotoCollection.LIBRARY,
    val libraryFilter: FilterSpec = FilterSpec(),
    val importFilter: FilterSpec = FilterSpec(),
    val importVisibility: ImportVisibility = ImportVisibility.NEW,
    val showOfflinePreviews: Boolean = false,
    val librarySection: LibrarySection = LibrarySection.ORIGINALS, val exportSourceId: String? = null,
    val exportFilter: FilterSpec = FilterSpec(), val versionsFilter: FilterSpec = FilterSpec()
) {
    val filter: FilterSpec get() = when {
        collection == PhotoCollection.CAMERA_IMPORT -> importFilter
        librarySection == LibrarySection.ORIGINALS -> libraryFilter
        exportSourceId != null -> versionsFilter
        else -> exportFilter
    }

    fun withFilter(value: FilterSpec): PhotoBrowserState = when {
        collection == PhotoCollection.CAMERA_IMPORT -> copy(importFilter = value)
        librarySection == LibrarySection.ORIGINALS -> copy(libraryFilter = value)
        exportSourceId != null -> copy(versionsFilter = value)
        else -> copy(exportFilter = value)
    }
}

private val PhotoAsset.visibleInLibrary: Boolean get() = downloadState == DownloadState.DOWNLOADED && !hiddenFromLibrary

/** Only app exports enter this tree. Camera companions have their own exports; promoting a version starts a new family there. */
internal class ExportVersions(assets: List<PhotoAsset>) {
    private val children = assets.filter { it.derivedFromId != null }.groupBy { it.derivedFromId }

    fun forSource(sourceId: String): List<PhotoAsset> {
        if (sourceId !in children) return emptyList()
        val seen = hashSetOf(sourceId)
        val parents = ArrayDeque<String>()
        parents.add(sourceId)
        return buildList {
            while (parents.isNotEmpty()) {
                for (photo in children[parents.removeFirst()].orEmpty()) {
                    if (!seen.add(photo.id)) continue
                    if (photo.visibleInLibrary) add(photo)
                    if (photo.id in children) parents.addLast(photo.id)
                }
            }
        }
    }
}

/** Import history is separate from visible catalog entries and never relies on a PTP handle. */
fun cameraFileKey(source: String, storage: String, name: String, bytes: Long, capturedAt: Long?): String? {
    if (source.isBlank() || source == "local" || source.startsWith("document:") || bytes <= 0) return null
    val fields = listOf(source, storage, name.uppercase(Locale.ROOT), bytes.toString(), capturedAt?.toString().orEmpty())
    val input = fields.joinToString("") { "${it.length}:$it" }
    return input.toByteArray().sha256Hex()
}

fun cameraCaptureKey(source: String, name: String, capturedAt: Long?): String? {
    if (capturedAt == null || source.isBlank() || source == "local" || source.startsWith("document:")) return null
    val fields = listOf(source, name.substringBeforeLast('.').uppercase(Locale.ROOT), capturedAt.toString(), "capture-v1")
    val input = fields.joinToString("") { "${it.length}:$it" }
    return input.toByteArray().sha256Hex()
}

fun PhotoAsset.importedAs(variant: DownloadVariant): Boolean = when (variant) {
    DownloadVariant.ORIGINAL -> importedAtMillis != null || downloadState == DownloadState.DOWNLOADED
    DownloadVariant.CAMERA_JPEG -> jpegCopyImportedAtMillis != null
}

fun collectionAssets(
    assets: List<PhotoAsset>, browser: PhotoBrowserState, connectedSource: String?, loadedIds: Set<String> = emptySet(),
    variant: DownloadVariant = DownloadVariant.ORIGINAL
): List<PhotoAsset> {
    if (browser.collection == PhotoCollection.LIBRARY) {
        val photos = browser.librarySection == LibrarySection.ORIGINALS
        if (!photos && browser.exportSourceId != null) return ExportVersions(assets).forSource(browser.exportSourceId)
        return assets.filter { it.visibleInLibrary && (it.derivedFromId == null) == photos }
    }
    if (connectedSource == null && !browser.showOfflinePreviews) return emptyList()
    return assets.filter { asset ->
        asset.inImportList &&
            (connectedSource == null || (asset.sourceId == connectedSource && asset.id in loadedIds)) &&
            when (browser.importVisibility) {
                ImportVisibility.NEW -> !asset.importedAs(variant)
                ImportVisibility.ALL -> true
                ImportVisibility.IMPORTED -> asset.importedAs(variant)
            }
    }
}
