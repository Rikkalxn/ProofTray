package org.openbridge.app.data

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PreviewDimensions
import org.openbridge.app.io.writeBytesAtomically
import org.openbridge.app.util.sha256Hex

/** Extract camera-rendered JPEGs without unpacking sensor pixels. No file-format parser or demosaicer is duplicated here. */
internal object RawPreviewExtractor {
    private data class EmbeddedJpeg(val bytes: ByteArray, val colorSpace: Int)
    private const val MAX_PREVIEW_BYTES = 16 * 1024 * 1024
    private const val CACHE_BYTES = 128L * 1024 * 1024
    private val mutex = Mutex()

    suspend fun load(context: Context, asset: PhotoAsset, sourceKey: String): PreviewCandidate? = mutex.withLock {
        val directory = File(context.cacheDir, "raw-embedded-previews-v2")
        val digest = sourceKey.toByteArray().sha256Hex()
        val target = File(directory, "$digest.jpg")
        val metadata = File(directory, "$digest.json")
        val colorSpace = if (metadata.isFile && metadata.length() in 1..256) runCatching {
            JSONObject(metadata.readText()).getInt("colorSpace").takeIf { it in 0..255 }
        }.getOrNull() else null
        if (colorSpace != null && target.isFile && target.length() in 1..MAX_PREVIEW_BYTES.toLong()) {
            PreviewCandidate.fromUri(context, Uri.fromFile(target).toString(), PreviewOrigin.RAW_EMBEDDED)?.let {
                target.setLastModified(System.currentTimeMillis())
                return@withLock it.copy(info = it.info.copy(cameraColorSpace = colorSpace))
            }
        }
        try {
            val extracted = RawRuntime.preview { signal ->
                val space = IntArray(1)
                val bytes = RawRuntime.withFile(context, asset, signal) { extractJpegNative(it, space, signal) ?: ByteArray(0) }
                EmbeddedJpeg(bytes, space[0])
            }
            val bytes = extracted.bytes
            currentCoroutineContext().ensureActive()
            if (bytes.size !in 1..MAX_PREVIEW_BYTES) return@withLock null
            val jpegSpace = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1) }.getOrDefault(-1)
            val space = if (jpegSpace in 1..2) jpegSpace else extracted.colorSpace
            val info = PreviewImageInfo.read(bytes)?.copy(cameraColorSpace = space) ?: return@withLock null
            Log.d("OpenBridgePreview", "embedded JPEG size=${info.dimensions.width}x${info.dimensions.height} bytes=${bytes.size}")
            try {
                directory.mkdirs()
                target.writeBytesAtomically(bytes)
                metadata.writeBytesAtomically(JSONObject().put("colorSpace", info.cameraColorSpace).toString().toByteArray())
                trim(directory, target)
                PreviewCandidate(PreviewInput.ImageUri(Uri.fromFile(target)), info, PreviewOrigin.RAW_EMBEDDED)
            } catch (_: Exception) {
                // An unwritable/full cache must not prevent displaying a preview already extracted into memory.
                PreviewCandidate(PreviewInput.Thumbnail(bytes, compressed = true, orientation = null), info, PreviewOrigin.RAW_EMBEDDED)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.d("OpenBridgePreview", "embedded JPEG unavailable: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    /** Small EXIF thumbnails are a last resort, after both file and camera previews have been considered. */
    fun fallback(context: Context, asset: PhotoAsset): PreviewCandidate? {
        return try { context.contentResolver.openInputStream(Uri.parse(asset.uri))?.use { stream ->
            val exif = ExifInterface(stream)
            if (!exif.hasThumbnail()) return null
            val range = exif.thumbnailRange
            if (range != null && (range.size != 2 || range[1] !in 1..(8L * 1024 * 1024))) return null
            val width = exif.getAttributeInt(ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH, 0)
            val height = exif.getAttributeInt(ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, 0)
            val dimensions = PreviewDimensions(width, height)
            if ((!exif.isThumbnailCompressed || range == null) && (!dimensions.valid || dimensions.pixels > 2_000_000)) return null
            val bytes = exif.thumbnailBytes ?: return null
            if (bytes.size !in 1..(8 * 1024 * 1024)) return null
            val info = if (exif.isThumbnailCompressed) PreviewImageInfo.read(bytes) ?: return null
                else PreviewImageInfo(dimensions, "image/rgb")
            PreviewCandidate(PreviewInput.Thumbnail(bytes, exif.isThumbnailCompressed,
                exif.orientationTag(ExifInterface.TAG_THUMBNAIL_ORIENTATION)), info, PreviewOrigin.RAW_EMBEDDED)
        } } catch (_: Exception) { null }
    }

    private fun trim(directory: File, keep: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.jpg")) } ?: return
        var total = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_BYTES) break
            if (file != keep) {
                val size = file.length()
                if (file.delete()) total -= size
                if (!file.exists()) File(directory, "${file.nameWithoutExtension}.json").delete()
            }
        }
    }

    private external fun extractJpegNative(descriptor: Int, colorSpace: IntArray, signal: CancellationSignal): ByteArray?
}
