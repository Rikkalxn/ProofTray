package org.openbridge.app.domain

import java.security.MessageDigest
import java.util.Locale

enum class PhotoCollection { LIBRARY, CAMERA_IMPORT }
enum class ImportVisibility { NEW, ALL, IMPORTED }

data class PhotoBrowserState(
    val collection: PhotoCollection = PhotoCollection.LIBRARY,
    val libraryFilter: FilterSpec = FilterSpec(),
    val importFilter: FilterSpec = FilterSpec(),
    val importVisibility: ImportVisibility = ImportVisibility.NEW,
    val showOfflinePreviews: Boolean = false
) {
    val filter: FilterSpec get() = if (collection == PhotoCollection.LIBRARY) libraryFilter else importFilter

    fun withFilter(value: FilterSpec): PhotoBrowserState = if (collection == PhotoCollection.LIBRARY) {
        copy(libraryFilter = value)
    } else copy(importFilter = value)
}

/** Import history is separate from visible catalog entries and never relies on a PTP handle. */
fun cameraFileKey(source: String, storage: String, name: String, bytes: Long, capturedAt: Long?): String? {
    if (source.isBlank() || source == "local" || source.startsWith("document:") || bytes <= 0) return null
    val fields = listOf(source, storage, name.uppercase(Locale.ROOT), bytes.toString(), capturedAt?.toString().orEmpty())
    val input = fields.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun cameraCaptureKey(source: String, name: String, capturedAt: Long?): String? {
    if (capturedAt == null || source.isBlank() || source == "local" || source.startsWith("document:")) return null
    val fields = listOf(source, name.substringBeforeLast('.').uppercase(Locale.ROOT), capturedAt.toString(), "capture-v1")
    val input = fields.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun collectionAssets(
    assets: List<PhotoAsset>, browser: PhotoBrowserState, connectedSource: String?, loadedIds: Set<String> = emptySet(),
    variant: DownloadVariant = DownloadVariant.ORIGINAL
): List<PhotoAsset> {
    if (browser.collection == PhotoCollection.LIBRARY) {
        return assets.filter { it.downloadState == DownloadState.DOWNLOADED && !it.hiddenFromLibrary }
    }
    if (connectedSource == null && !browser.showOfflinePreviews) return emptyList()
    return assets.filter { asset ->
        val imported = if (variant == DownloadVariant.CAMERA_JPEG) asset.jpegCopyImportedAtMillis != null
            else asset.importedAtMillis != null || asset.downloadState == DownloadState.DOWNLOADED
        asset.inImportList &&
            (connectedSource == null || (asset.sourceId == connectedSource && asset.id in loadedIds)) &&
            when (browser.importVisibility) {
                ImportVisibility.NEW -> !imported
                ImportVisibility.ALL -> true
                ImportVisibility.IMPORTED -> imported
            }
    }
}
