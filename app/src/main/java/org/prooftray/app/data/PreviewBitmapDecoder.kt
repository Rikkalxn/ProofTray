package org.prooftray.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.PreviewDimensions

internal enum class PreviewOrigin { CAMERA, RAW_EMBEDDED, ORIGINAL }
internal data class LoadedPhotoPreview(val bitmap: Bitmap, val origin: PreviewOrigin, val dimensions: PreviewDimensions)

internal data class PreviewImageInfo(val dimensions: PreviewDimensions, val mimeType: String, val cameraColorSpace: Int = 0) {
    companion object {
        fun read(bytes: ByteArray): PreviewImageInfo? = probe { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
        fun read(file: File): PreviewImageInfo? = probe { BitmapFactory.decodeFile(file.absolutePath, it) }
        fun read(stream: InputStream): PreviewImageInfo? = probe { BitmapFactory.decodeStream(stream, null, it) }

        private fun probe(read: (BitmapFactory.Options) -> Unit): PreviewImageInfo? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            read(bounds)
            val size = PreviewDimensions(bounds.outWidth, bounds.outHeight)
            return if (size.valid) PreviewImageInfo(size, bounds.outMimeType.orEmpty()) else null
        }
    }
}

internal fun bestPreviewFile(files: List<File>): Pair<File, PreviewImageInfo>? = files.mapNotNull { file ->
    if (!file.isFile || file.length() !in 1..(16L * 1024 * 1024)) return@mapNotNull null
    val info = try { PreviewImageInfo.read(file) } catch (_: Exception) { null }
    info?.let { file to it }
}.maxWithOrNull(compareBy<Pair<File, PreviewImageInfo>> { it.second.dimensions.pixels }.thenBy { it.first.lastModified() })

internal sealed interface PreviewInput {
    data class ImageUri(val uri: Uri) : PreviewInput
    data class Thumbnail(val bytes: ByteArray, val compressed: Boolean, val orientation: Int?) : PreviewInput
}

internal data class PreviewCandidate(val input: PreviewInput, val info: PreviewImageInfo, val origin: PreviewOrigin) {
    companion object {
        fun fromUri(context: Context, uri: String, origin: PreviewOrigin): PreviewCandidate? {
            val source = Uri.parse(uri)
            val info = try { context.contentResolver.openInputStream(source)?.use { PreviewImageInfo.read(it) } }
                catch (_: Exception) { null }
            return info?.let { PreviewCandidate(PreviewInput.ImageUri(source), it, origin) }
        }
    }
}

/** Source acquisition is separate: this decoder applies one orientation and one bounded resize to every preview. */
internal object PreviewBitmapDecoder {
    fun decode(context: Context, candidate: PreviewCandidate, asset: PhotoAsset, original: SourceOrientation?, edge: Int): LoadedPhotoPreview? {
        val input = candidate.input
        val info = candidate.info
        val dimensions = info.dimensions
        val budget = minOf(8_000_000L, Runtime.getRuntime().maxMemory() / 24)
        var sample = 1
        while (sample < 65536 && dimensions.longestEdge / (sample * 2) >= edge) sample *= 2
        while (dimensions.width.toLong() / sample * (dimensions.height / sample) > budget && sample < 65536) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val heif = input is PreviewInput.ImageUri && info.mimeType.startsWith("image/hei")
        val resolver = context.contentResolver
        val decoded = when (input) {
            is PreviewInput.Thumbnail -> if (input.compressed) BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size, options)
                else decodeRgbThumbnail(input.bytes, dimensions.width, dimensions.height)
            is PreviewInput.ImageUri -> if (heif) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, input.uri)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(sample)
                }
            } else resolver.openInputStream(input.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } ?: return null
        var bitmap = decoded
        try {
            if (candidate.origin == PreviewOrigin.RAW_EMBEDDED && info.cameraColorSpace == 2 && bitmap.colorSpace?.isSrgb == true) {
                val hasIcc = when (input) {
                    is PreviewInput.Thumbnail -> if (input.compressed) jpegHasIccProfile(input.bytes.inputStream()) else null
                    is PreviewInput.ImageUri -> resolver.openInputStream(input.uri)?.use(::jpegHasIccProfile)
                }
                if (hasIcc == false) bitmap.setColorSpace(ColorSpace.get(ColorSpace.Named.ADOBE_RGB))
            }
            val ownTag = when (input) {
                is PreviewInput.Thumbnail -> input.orientation ?: if (input.compressed) {
                    runCatching { ExifInterface(input.bytes.inputStream()).orientationTag() }.getOrNull()
                } else null
                is PreviewInput.ImageUri -> if (heif) ExifInterface.ORIENTATION_NORMAL else runCatching {
                    resolver.openInputStream(input.uri)?.use { ExifInterface(it).orientationTag() }
                }.getOrNull()
            }
            val orientation = if (candidate.origin == PreviewOrigin.ORIGINAL) ownTag ?: asset.sourceOrientation ?: 1 else {
                previewOrientation(ownTag, original?.tag ?: asset.sourceOrientation, bitmap.width, bitmap.height,
                    if (original != null) original.displayWidth else asset.width, if (original != null) original.displayHeight else asset.height)
            }
            val matrix = orientationMatrix(orientation)
            if (!matrix.isIdentity) {
                val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (oriented !== bitmap) bitmap.recycle()
                bitmap = oriented
            }
            val ratio = minOf(1f, edge.toFloat() / maxOf(bitmap.width, bitmap.height))
            if (ratio < 1f) {
                val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
                if (resized !== bitmap) bitmap.recycle()
                bitmap = resized
            }
            val displayed = if (orientation >= 5) PreviewDimensions(dimensions.height, dimensions.width) else dimensions
            return LoadedPhotoPreview(bitmap, candidate.origin, displayed)
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
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
