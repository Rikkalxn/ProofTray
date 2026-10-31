package org.openbridge.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PickState
import org.openbridge.app.domain.SavedFilter

data class ImportReport(val importedCount: Int, val skippedCount: Int, val errors: List<String>, val refreshedCount: Int = 0)
data class ImportListCleanup(val removedIds: Set<String>, val unusedPreviewIds: Set<String>)

class PhotoRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val database = CatalogDatabase(appContext)
    private val previews = PhotoPreviewStore(appContext)
    private val mutex = sharedMutex

    suspend fun loadAssets(): List<PhotoAsset> = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Resolve old cache addresses cheaply. Archiving is deferred until display, so startup doesn't copy every RAW preview.
            database.loadAssets().map(previews::attachCachedPreview)
        }
    }
    suspend fun importedKeys(): Set<String> = withContext(Dispatchers.IO) { mutex.withLock { database.importHistory().keys } }

    suspend fun readCameraDownload(asset: PhotoAsset, uri: Uri): PhotoAsset = withContext(Dispatchers.IO) {
        val parsed = readAsset(uri)
        previews.recover(parsed.copy(id = asset.id, sourceId = asset.sourceId, folder = asset.folder,
            capturedAtMillis = asset.capturedAtMillis ?: parsed.capturedAtMillis, downloadState = DownloadState.DOWNLOADED,
            cameraFileKey = asset.cameraFileKey, inImportList = asset.inImportList, previewUri = asset.previewUri,
            previewRevision = asset.previewRevision,
            importedAtMillis = System.currentTimeMillis(), cameraCaptureKey = asset.cameraCaptureKey))
    }

    suspend fun mergeCameraAssets(assets: List<PhotoAsset>, resetQueued: Boolean = false): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val history = database.importHistory()
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                assets.forEach { incoming ->
                    val previous = database.assetForId(incoming.id)
                    val merged = mergeCameraAsset(previous, incoming, resetQueued).let { asset ->
                        asset.copy(importedAtMillis = asset.importedAtMillis ?: asset.cameraFileKey?.let(history::get))
                    }
                    if (merged.downloadState == DownloadState.DOWNLOADED) {
                        merged.cameraFileKey?.let { database.rememberImport(it, merged.importedAtMillis ?: System.currentTimeMillis(), db) }
                    }
                    database.putAsset(merged, db)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    private fun mergeCameraAsset(previous: PhotoAsset?, incoming: PhotoAsset, resetQueued: Boolean): PhotoAsset {
        if (previous == null) return incoming
        if (previous.downloadState == DownloadState.DOWNLOADED && incoming.downloadState != DownloadState.DOWNLOADED) {
            return previous.copy(inImportList = incoming.inImportList, previewUri = incoming.previewUri ?: previous.previewUri,
                previewRevision = incoming.previewRevision ?: previous.previewRevision)
        }
        val queued = previous.downloadState == DownloadState.QUEUED && incoming.downloadState == DownloadState.REMOTE && !resetQueued
        return incoming.copy(rating = previous.rating, pickState = previous.pickState, tags = previous.tags,
            downloadState = if (queued) DownloadState.QUEUED else incoming.downloadState,
            hiddenFromLibrary = previous.hiddenFromLibrary && incoming.downloadState != DownloadState.DOWNLOADED,
            previewUri = incoming.previewUri ?: previous.previewUri, previewRevision = incoming.previewRevision ?: previous.previewRevision)
    }

    suspend fun setLibraryVisibility(ids: Set<String>, visible: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                database.loadAssets().filter { it.id in ids && it.downloadState == DownloadState.DOWNLOADED }.forEach {
                    database.putAsset(it.copy(hiddenFromLibrary = !visible), db)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    /** Removes import candidates only. The MediaStore files and independent import ledger remain intact. */
    suspend fun clearImportList(source: String?, selectedIds: Set<String>? = null): ImportListCleanup = withContext(Dispatchers.IO) {
        mutex.withLock {
            val affected = database.loadAssets().filter {
                it.inImportList && (source == null || it.sourceId == source) && (selectedIds == null || it.id in selectedIds)
            }
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                affected.forEach { asset ->
                    if (asset.downloadState == DownloadState.DOWNLOADED) {
                        database.putAsset(asset.copy(inImportList = false), db)
                    } else database.deleteAsset(asset.id, db)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            ImportListCleanup(affected.mapTo(hashSetOf()) { it.id },
                affected.filter { it.downloadState != DownloadState.DOWNLOADED }.mapTo(hashSetOf()) { it.id })
        }
    }

    suspend fun removeDeletedMedia(targets: List<MediaDeleteTarget>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val removed = mutableListOf<String>()
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                targets.forEach { target ->
                    val current = database.assetForId(target.id)
                    if (current?.uri == target.originalUri) {
                        database.deleteAsset(target.id, db)
                        removed += target.id
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            removed.forEach(previews::remove)
        }
    }

    suspend fun importUris(uris: List<Uri>, onBatch: suspend (List<PhotoAsset>) -> Unit = {}): ImportReport = withContext(Dispatchers.IO) {
        var imported = 0
        val distinctUris = uris.distinct()
        val skipped = uris.size - distinctUris.size
        var refreshed = 0
        val errors = mutableListOf<String>()
        val batch = mutableListOf<PhotoAsset>()
        for (uri in distinctUris) {
            currentCoroutineContext().ensureActive()
            try {
                val asset = readAsset(uri)
                val (stored, wasRefresh) = mutex.withLock {
                    val previous = database.assetForUri(uri.toString())
                    val updated = if (previous == null) asset else asset.copy(
                        id = previous.id, rating = previous.rating, pickState = previous.pickState, tags = previous.tags,
                        sourceId = previous.sourceId, folder = previous.folder, cameraFileKey = previous.cameraFileKey,
                        inImportList = previous.inImportList, previewUri = previous.previewUri, importedAtMillis = previous.importedAtMillis,
                        jpegCopyImportedAtMillis = previous.jpegCopyImportedAtMillis, derivedFromId = previous.derivedFromId,
                        cameraCaptureKey = previous.cameraCaptureKey
                    )
                    database.putAsset(updated)
                    updated to (previous != null)
                }
                imported++
                if (wasRefresh) refreshed++
                batch += stored
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errors += "${uri.lastPathSegment ?: "文件"}：${error.message ?: "读取失败"}"
            }
            if (batch.size >= 20) {
                onBatch(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
        ImportReport(imported, skipped, errors, refreshed)
    }

    suspend fun updateMarks(ids: Set<String>, rating: Int? = null, pickState: PickState? = null, tags: Set<String>? = null) {
        require(rating == null || rating in 0..5) { "评分必须在 0 到 5 之间" }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val replacements = database.loadAssets().filter { it.id in ids }.map { asset ->
                    asset.copy(
                        rating = rating ?: asset.rating, pickState = pickState ?: asset.pickState,
                        tags = tags?.map(String::trim)?.filter(String::isNotEmpty)?.toSet() ?: asset.tags
                    )
                }
                val db = database.writableDatabase
                db.beginTransaction()
                try {
                    replacements.forEach { database.putAsset(it, db) }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    suspend fun loadSavedFilters(): List<SavedFilter> = withContext(Dispatchers.IO) { mutex.withLock { database.loadFilters() } }

    suspend fun saveFilter(filter: SavedFilter) {
        require(filter.id.isNotBlank() && filter.name.isNotBlank()) { "筛选名称不能为空" }
        withContext(Dispatchers.IO) { mutex.withLock { database.putFilter(filter) } }
    }

    suspend fun deleteFilter(id: String) = withContext(Dispatchers.IO) { mutex.withLock { database.deleteFilter(id) } }

    private fun readAsset(uri: Uri): PhotoAsset {
        require(uri.scheme == "content") { "只支持系统文件选择器提供的文件" }
        var name = ""
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).coerceAtLeast(0)
            }
        }
        require(name.isNotBlank()) { "文件提供方没有返回文件名" }
        val format = formatFor(name, resolver.getType(uri))
        require(format != MediaFormat.OTHER) { "尚不支持此文件格式：$name" }
        // Verify access even when the metadata decoder does not recognize this RAW format.
        resolver.openInputStream(uri)?.use { check(it.read() != -1) { "文件为空" } } ?: error("无法打开文件")
        val exif = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it) }
        } catch (_: Exception) {
            null
        }
        var width = exif?.positiveInt(ExifInterface.TAG_PIXEL_X_DIMENSION) ?: exif?.positiveInt(ExifInterface.TAG_IMAGE_WIDTH)
        var height = exif?.positiveInt(ExifInterface.TAG_PIXEL_Y_DIMENSION) ?: exif?.positiveInt(ExifInterface.TAG_IMAGE_LENGTH)
        if ((width == null || height == null) && format != MediaFormat.RAW) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            width = bounds.outWidth.takeIf { it > 0 }
            height = bounds.outHeight.takeIf { it > 0 }
        }
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        if (orientation in setOf(5, 6, 7, 8)) {
            val originalWidth = width
            width = height
            height = originalWidth
        }
        val make = exif?.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        val camera = when {
            model.startsWith(make, ignoreCase = true) -> model
            else -> listOf(make, model).filter(String::isNotBlank).joinToString(" ")
        }
        val sourceId = "document:${uri.authority.orEmpty()}"
        val folder = documentFolder(uri)
        return PhotoAsset(
            id = UUID.randomUUID().toString(), uri = uri.toString(), name = name, format = format, sizeBytes = size,
            capturedAtMillis = exif?.captureTime(), width = width, height = height, camera = camera,
            lens = exif?.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim().orEmpty(),
            iso = exif?.positiveInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY), aperture = exif?.positiveDouble(ExifInterface.TAG_F_NUMBER),
            exposureSeconds = exif?.positiveDouble(ExifInterface.TAG_EXPOSURE_TIME),
            focalLengthMm = exif?.positiveDouble(ExifInterface.TAG_FOCAL_LENGTH),
            sourceId = sourceId, folder = folder, metadataComplete = exif != null
        )
    }

    private fun documentFolder(uri: Uri): String {
        if (!DocumentsContract.isDocumentUri(appContext, uri)) return ""
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return ""
        // Opaque IDs such as "image:123" cannot establish a common parent directory.
        val slash = documentId.lastIndexOf('/')
        return if (slash > 0) documentId.substring(0, slash) else ""
    }

    private fun formatFor(name: String, mime: String?): MediaFormat {
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "jpe" -> MediaFormat.JPEG
            "nef", "nrw", "dng", "cr2", "cr3", "arw", "raf", "rw2", "orf", "pef", "srw" -> MediaFormat.RAW
            "png" -> MediaFormat.PNG
            "heic", "heif", "hif" -> MediaFormat.HEIF
            "tif", "tiff" -> MediaFormat.TIFF
            else -> when (mime?.lowercase(Locale.ROOT)) {
                "image/jpeg" -> MediaFormat.JPEG
                "image/png" -> MediaFormat.PNG
                "image/heic", "image/heif" -> MediaFormat.HEIF
                "image/tiff" -> MediaFormat.TIFF
                else -> MediaFormat.OTHER
            }
        }
    }

    private fun ExifInterface.positiveInt(tag: String): Int? = getAttributeInt(tag, -1).takeIf { it > 0 }
    private fun ExifInterface.positiveDouble(tag: String): Double? = getAttributeDouble(tag, Double.NaN).takeIf { it.isFinite() && it > 0 }

    private fun ExifInterface.captureTime(): Long? {
        val original = getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.trim() ?: return null
        return runCatching {
            var date = LocalDateTime.parse(original, DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT))
            val subseconds = getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)?.trim()?.takeWhile(Char::isDigit).orEmpty()
            if (subseconds.isNotEmpty()) date = date.withNano(subseconds.take(9).padEnd(9, '0').toInt())
            val offset = getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.trim()?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
            // EXIF without an offset is a floating camera time; interpret it in the phone's current zone, never use file mtime.
            if (offset != null) date.toInstant(offset).toEpochMilli() else date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private companion object {
        val sharedMutex = Mutex()
    }
}
