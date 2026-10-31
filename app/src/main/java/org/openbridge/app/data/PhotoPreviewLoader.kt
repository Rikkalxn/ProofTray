package org.openbridge.app.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import java.io.File
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset

/** Decoding and orientation live outside Compose. Cache keys change when the source preview is upgraded. */
internal object PhotoPreviewLoader {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun clearMemoryCache() = cache.evictAll()

    fun load(context: Context, asset: PhotoAsset, edge: Int): Bitmap? {
        val resolver = context.contentResolver
        if (asset.downloadState != DownloadState.DOWNLOADED || asset.format == MediaFormat.RAW) {
            val store = PhotoPreviewStore(context)
            val recovered = store.recover(asset)
            val candidates = listOfNotNull(recovered.previewUri, asset.previewUri, asset.uri.takeIf { it.startsWith("file:") }) +
                store.candidates(asset.id).map { Uri.fromFile(it).toString() }
            for (preview in candidates.distinct()) {
                val cached = try { decode(resolver, preview, edge, raw = false) } catch (_: Exception) { null }
                if (cached != null) return cached
            }
        }
        if (asset.uri.startsWith("openbridge-remote:")) return null
        return decode(resolver, asset.uri, edge, raw = asset.format == MediaFormat.RAW)
    }

    private fun decode(resolver: ContentResolver, source: String, edge: Int, raw: Boolean): Bitmap? {
        val uri = Uri.parse(source)
        val revision = if (uri.scheme == "file") uri.path?.let { File(it) }?.let { "${it.length()}:${it.lastModified()}" } else null
        val key = "$source:$revision:$edge:$raw"
        cache.get(key)?.let { return it }
        var orientation = ExifInterface.ORIENTATION_NORMAL
        var uncompressed: Bitmap? = null
        val embedded = if (raw) {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                if (!exif.hasThumbnail()) return null
                val range = exif.thumbnailRange
                if (range != null && (range.size != 2 || range[1] !in 1..(8L * 1024 * 1024))) return null
                val width = exif.getAttributeInt(ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH, 0)
                val height = exif.getAttributeInt(ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, 0)
                if (range == null && (width <= 0 || height <= 0 || width.toLong() * height > 2_000_000)) return null
                val fallback = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                orientation = exif.getAttributeInt(ExifInterface.TAG_THUMBNAIL_ORIENTATION, fallback)
                val bytes = exif.thumbnailBytes ?: return null
                if (bytes.size !in 1..(8 * 1024 * 1024)) return null
                if (!exif.isThumbnailCompressed) {
                    if (width <= 0 || height <= 0 || width.toLong() * height > 2_000_000 || width.toLong() * height * 3 > bytes.size) return null
                    uncompressed = exif.thumbnailBitmap ?: return null
                }
                bytes
            } ?: return null
        } else null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (uncompressed != null) { bounds.outWidth = uncompressed!!.width; bounds.outHeight = uncompressed!!.height }
        else if (embedded != null) BitmapFactory.decodeByteArray(embedded, 0, embedded.size, bounds)
        else resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val budget = minOf(8_000_000L, Runtime.getRuntime().maxMemory() / 24)
        var sample = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), edge)
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > budget && sample < 65536) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = uncompressed ?: if (embedded != null) BitmapFactory.decodeByteArray(embedded, 0, embedded.size, options)
            else resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val bitmap = decoded ?: return null
        if (embedded == null) orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        }.getOrDefault(1)
        val matrix = orientationMatrix(orientation)
        val oriented = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        val ratio = minOf(1f, edge.toFloat() / maxOf(oriented.width, oriented.height))
        val resized = if (ratio == 1f) oriented else Bitmap.createScaledBitmap(oriented,
            (oriented.width * ratio).toInt().coerceAtLeast(1), (oriented.height * ratio).toInt().coerceAtLeast(1), true)
        if (resized !== oriented) oriented.recycle()
        cache.put(key, resized)
        return resized
    }

    private fun sampleSize(longestEdge: Int, target: Int): Int {
        var sample = 1
        // Keep enough pixels for the requested size, instead of dropping below it by a whole power of two.
        while (sample < 65536 && longestEdge / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
}
