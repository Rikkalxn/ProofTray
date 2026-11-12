package org.openbridge.app.data

import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset

internal fun ExifInterface.orientationTag(tag: String = ExifInterface.TAG_ORIENTATION): Int? = getAttributeInt(tag, 0).takeIf { it in 1..8 }

internal data class SourceOrientation(val tag: Int, val encodedWidth: Int?, val encodedHeight: Int?) {
    fun applyTo(asset: PhotoAsset): PhotoAsset {
        val storedWidth = asset.width?.takeIf { it > 0 }
        val storedHeight = asset.height?.takeIf { it > 0 }
        val storedPair = storedWidth != null && storedHeight != null
        val encodedPair = encodedWidth != null && encodedWidth > 0 && encodedHeight != null && encodedHeight > 0
        val width = if (storedPair || !encodedPair) storedWidth else encodedWidth
        val height = if (storedPair || !encodedPair) storedHeight else encodedHeight
        val encoded = aspect(encodedWidth, encodedHeight)
        val current = aspect(width, height)
        // A matching tag does not prove that an older catalog row already has oriented dimensions.
        val swap = if (encoded != null && current != null) current != (if (tag >= 5) -encoded else encoded)
            else asset.sourceOrientation != null && (asset.sourceOrientation >= 5) != (tag >= 5)
        return asset.copy(sourceOrientation = tag, width = if (swap) height else width, height = if (swap) width else height)
    }

    val displayWidth: Int? get() = if (tag >= 5) encodedHeight else encodedWidth
    val displayHeight: Int? get() = if (tag >= 5) encodedWidth else encodedHeight
}

/** A catalog row may still point to a camera JPEG even when import history says DOWNLOADED. */
internal fun localRawUri(context: Context, asset: PhotoAsset): Uri? {
    if (asset.format != MediaFormat.RAW || asset.downloadState != DownloadState.DOWNLOADED || asset.uri == asset.previewUri) return null
    val uri = Uri.parse(asset.uri)
    if (uri.scheme !in setOf("file", "content")) return null
    return try {
        if (uri.scheme == "file") {
            val file = File(checkNotNull(uri.path)).canonicalFile
            val previews = listOf(File(context.cacheDir, "camera-previews-v2"), File(context.filesDir, "photo-previews"))
            if (previews.any { file.toPath().startsWith(it.canonicalFile.toPath()) }) return null
        }
        uri
    } catch (_: Exception) { null }
}

/** Read fresh original geometry before consulting decoded preview caches. */
internal fun localRawGeometry(context: Context, uri: Uri): SourceOrientation? = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor).sourceGeometry() }
} catch (_: Exception) { null }

/** Geometry updates must not replay stale ratings, selection, import history or a replaced source URI. */
internal fun PhotoAsset.withGeometryFrom(source: PhotoAsset): PhotoAsset {
    if (id != source.id || uri != source.uri || format != source.format || downloadState != source.downloadState ||
        source.sourceOrientation !in 1..8) return this
    return copy(sourceOrientation = source.sourceOrientation, width = source.width?.takeIf { it > 0 } ?: width,
        height = source.height?.takeIf { it > 0 } ?: height)
}

internal fun ExifInterface.sourceGeometry(): SourceOrientation? {
    val tag = orientationTag() ?: return null
    fun positive(name: String) = getAttributeInt(name, 0).takeIf { it > 0 }
    val x = positive(ExifInterface.TAG_PIXEL_X_DIMENSION)
    val y = positive(ExifInterface.TAG_PIXEL_Y_DIMENSION)
    return if (x != null && y != null) SourceOrientation(tag, x, y)
        else SourceOrientation(tag, positive(ExifInterface.TAG_IMAGE_WIDTH), positive(ExifInterface.TAG_IMAGE_LENGTH))
}

internal fun ByteArray.sourceGeometry(format: MediaFormat): SourceOrientation? {
    val geometry = runCatching { ExifInterface(inputStream()).sourceGeometry() }.getOrNull() ?: return null
    if (format != MediaFormat.JPEG) return geometry
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(this, 0, size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return geometry
    return geometry.copy(encodedWidth = bounds.outWidth, encodedHeight = bounds.outHeight)
}

private fun aspect(width: Int?, height: Int?): Int? =
    if (width != null && height != null && width > 0 && height > 0 && width != height) width.compareTo(height) else null

/** A camera can bake the rotation into a screennail or strip its EXIF while leaving the sensor pixels unrotated. */
internal fun previewOrientation(ownTag: Int?, sourceTag: Int?, pixelWidth: Int, pixelHeight: Int, displayWidth: Int?, displayHeight: Int?): Int {
    val pixels = aspect(pixelWidth, pixelHeight)
    val display = aspect(displayWidth, displayHeight)
    if (ownTag != null && ownTag != ExifInterface.ORIENTATION_NORMAL) {
        if (ownTag >= 5 && sourceTag != null && sourceTag >= 5 && pixels != null && pixels == display) return ExifInterface.ORIENTATION_NORMAL
        return ownTag
    }
    if (sourceTag == null || sourceTag == ExifInterface.ORIENTATION_NORMAL) return ExifInterface.ORIENTATION_NORMAL
    if (sourceTag >= 5) {
        if (pixels != null && display != null) return if (pixels == display) ExifInterface.ORIENTATION_NORMAL else sourceTag
    }
    return ownTag ?: sourceTag
}
