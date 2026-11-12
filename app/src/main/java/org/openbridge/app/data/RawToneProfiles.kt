package org.openbridge.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.io.writeBytesAtomically
import org.openbridge.app.util.sha256Hex

internal object RawToneCodec {
    fun encode(key: RawToneKey, profile: RawToneProfile): String = JSONObject().apply {
        put("key", key.identity); put("origin", profile.origin.name)
        put("fallback", profile.fallback?.name ?: JSONObject.NULL)
        put("samples", JSONArray(profile.samples))
    }.toString()

    fun decode(key: RawToneKey, text: String): RawToneProfile? = runCatching {
        require(text.length <= 16384)
        val json = JSONObject(text)
        require(json.getString("key") == key.identity)
        val array = json.getJSONArray("samples")
        require(array.length() <= RawToneProfile.SAMPLE_COUNT)
        RawToneProfile(List(array.length()) { array.getDouble(it).toFloat() }, RawToneOrigin.valueOf(json.getString("origin")),
            if (json.isNull("fallback")) null else RawToneFallback.valueOf(json.getString("fallback")))
    }.getOrNull()
}

internal object RawToneProfiles {
    private val cache = RawToneCache()

    suspend fun forAsset(context: Context, asset: PhotoAsset): RawToneProfile = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val key = sourceKey(app, asset)
        fun file(key: RawToneKey) = File(app.cacheDir, "raw-tone-v${RawToneProfile.VERSION}/${key.identity.toByteArray().sha256Hex()}.json")
        cache.resolve(key, { identity ->
            val saved = file(identity)
            if (saved.isFile && saved.length() in 1..16384) runCatching { RawToneCodec.decode(identity, saved.readText()) }.getOrNull() else null
        }, { identity, profile ->
            runCatching {
                val saved = file(identity)
                saved.parentFile?.mkdirs()
                saved.writeBytesAtomically(RawToneCodec.encode(identity, profile).toByteArray())
            }.onFailure { Log.d("OpenBridgeRaw", "Tone cache unavailable: ${it.javaClass.simpleName}") }
        }) {
            val profile = create(app, asset, key?.identity ?: asset.uri)
            currentCoroutineContext().ensureActive()
            if (key != null && sourceKey(app, asset) != key) throw IOException("照片在显影时已变化，请重试")
            Log.i("OpenBridgeRaw", "Default RAW tone origin=${profile.origin} fallback=${profile.fallback}")
            profile
        }
    }

    private fun sourceKey(context: Context, asset: PhotoAsset): RawToneKey? = try {
        context.contentResolver.openFileDescriptor(Uri.parse(asset.uri), "r")?.use {
            val stat = Os.fstat(it.fileDescriptor)
            if (OsConstants.S_ISREG(stat.st_mode) && stat.st_size > 0) {
                RawToneKey(asset.id, asset.uri, stat.st_size, "${stat.st_mtime}:${stat.st_ctime}:${asset.sizeBytes}")
            } else null // Providers without stable revisions are recomputed, not cached under a guessed revision.
        }
    } catch (_: Exception) { null }

    private suspend fun create(context: Context, asset: PhotoAsset, sourceKey: String): RawToneProfile {
        fun fallback(reason: RawToneFallback) = RawToneProfile.generic(reason)
        val candidate = RawPreviewExtractor.load(context, asset, sourceKey) ?: return fallback(RawToneFallback.NO_PREVIEW)
        if (candidate.info.mimeType != "image/jpeg") return fallback(RawToneFallback.COLOR_SPACE)
        if (candidate.info.dimensions.pixels < 19200) return fallback(RawToneFallback.SMALL_PREVIEW)
        var bitmap: Bitmap? = null
        try {
            val input = candidate.input
            val hasIcc = when (input) {
                is PreviewInput.Thumbnail -> jpegHasIccProfile(input.bytes.inputStream())
                is PreviewInput.ImageUri -> context.contentResolver.openInputStream(input.uri)?.use(::jpegHasIccProfile)
            } ?: return fallback(RawToneFallback.COLOR_SPACE)
            val jpegSpace = when (input) {
                is PreviewInput.Thumbnail -> ExifInterface(input.bytes.inputStream()).getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1)
                is PreviewInput.ImageUri -> context.contentResolver.openInputStream(input.uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1)
                } ?: -1
            }
            val raw = RawPhotoDecoder.readLinear(context, asset, RawToneProfile.REFERENCE_EDGE)
            val cameraSpace = if (jpegSpace in 1..2) jpegSpace else candidate.info.cameraColorSpace.takeIf { it > 0 } ?: raw.cameraColorSpace
            val source = when (input) {
                is PreviewInput.Thumbnail -> ImageDecoder.createSource(ByteBuffer.wrap(input.bytes))
                is PreviewInput.ImageUri -> ImageDecoder.createSource(context.contentResolver, input.uri)
            }
            var colorKnown = false
            var originalWidth = 0
            var originalHeight = 0
            var untaggedAdobe = false
            bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val space = info.colorSpace as? ColorSpace.Rgb
                val color = rawPreviewColor(cameraSpace, hasIcc, space?.isSrgb == true, space?.transferParameters != null)
                colorKnown = color != RawPreviewColor.UNSUPPORTED
                untaggedAdobe = color == RawPreviewColor.ADOBE_RGB
                originalWidth = info.size.width; originalHeight = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val ratio = minOf(1.0, RawToneProfile.REFERENCE_EDGE.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
            }
            if (!colorKnown || (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap())) return fallback(RawToneFallback.COLOR_SPACE)
            currentCoroutineContext().ensureActive()
            // ImageDecoder applies the JPEG's orientation; absent/wrong orientation fails the aspect check safely.
            val previewWidth = if ((bitmap.width > bitmap.height) == (originalWidth > originalHeight)) originalWidth else originalHeight
            val previewHeight = if (previewWidth == originalWidth) originalHeight else originalWidth
            RawToneMatcher.previewProblem(raw.sourceWidth, raw.sourceHeight, previewWidth, previewHeight)?.let { return fallback(it) }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val coroutine = currentCoroutineContext()
            if (untaggedAdobe) {
                // Some embedded JPEGs carry only orientation; LibRaw exposes the camera's recorded JPEG colour space.
                val connector = ColorSpace.connect(ColorSpace.get(ColorSpace.Named.ADOBE_RGB), ColorSpace.get(ColorSpace.Named.SRGB))
                val rgb = FloatArray(3)
                for (i in pixels.indices) {
                    if (i % bitmap.width == 0) coroutine.ensureActive()
                    val pixel = pixels[i]
                    rgb[0] = (pixel ushr 16 and 255) / 255f
                    rgb[1] = (pixel ushr 8 and 255) / 255f
                    rgb[2] = (pixel and 255) / 255f
                    connector.transform(rgb)
                    pixels[i] = (255 shl 24) or ((rgb[0].coerceIn(0f, 1f) * 255).roundToInt() shl 16) or
                        ((rgb[1].coerceIn(0f, 1f) * 255).roundToInt() shl 8) or (rgb[2].coerceIn(0f, 1f) * 255).roundToInt()
                }
            }
            return RawToneMatcher.fit(raw.pixels.asFloatBuffer(), raw.width, raw.height, pixels, bitmap.width, bitmap.height) {
                coroutine.ensureActive()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.d("OpenBridgeRaw", "Default tone fallback: ${error.javaClass.simpleName}")
            return fallback(RawToneFallback.UNAVAILABLE)
        } finally { bitmap?.recycle() }
    }
}
