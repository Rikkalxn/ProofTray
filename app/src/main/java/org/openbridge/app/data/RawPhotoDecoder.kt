package org.openbridge.app.data

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openbridge.app.color.LutRepository
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.LutRange
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset

/** Develop the sensor data for editing and export. Browsing uses RawPreviewExtractor instead. */
internal object RawPhotoDecoder {
    suspend fun decode(context: Context, asset: PhotoAsset, maxEdge: Int, pixelBudget: Long,
        look: ColorLookSettings = ColorLookSettings(), packed: ByteArray? = null): Bitmap {
        look.validateForExport(listOf(asset))
        val profile = RawToneProfiles.forAsset(context, asset)
        val lut = packed ?: look.lutId?.let { withContext(Dispatchers.IO) { LutRepository.get(context).canonical(it) } }
        return RawRuntime.decode("OpenBridge.raw.develop", Bitmap::recycle) { signal ->
            RawRuntime.withFile(context, asset, signal) { fd ->
                decodeNative(fd, maxEdge, pixelBudget, profile.samples.toFloatArray(), lut, look.parameters(), look.logGainEv,
                    checkNotNull(look.usage.input).nativeCode, checkNotNull(look.usage.output).nativeCode,
                    look.usage.inputRange == LutRange.VIDEO, signal)
            }.also { it.setHasAlpha(false) }
        }
    }

    suspend fun loadLinear(context: Context, asset: PhotoAsset, edge: Int): LinearRawPreview {
        val profile = RawToneProfiles.forAsset(context, asset)
        return readLinear(context, asset, edge).also { it.toneProfile = profile }
    }

    internal suspend fun readLinear(context: Context, asset: PhotoAsset, edge: Int): LinearRawPreview =
        RawRuntime.decode("OpenBridge.raw.linearPreview", {}) { signal ->
            require(asset.format == MediaFormat.RAW)
            RawRuntime.withFile(context, asset, signal) { readLinearNative(it, edge, signal) }
        }

    suspend fun preview(frame: LinearRawPreview, lut: ByteArray?, look: ColorLookSettings): Bitmap =
        RawRuntime.decode("OpenBridge.raw.renderPreview", Bitmap::recycle) { signal ->
            look.validate()
            checkNotNull(renderPreviewNative(frame.pixels, frame.width, frame.height, frame.toneProfile.samples.toFloatArray(),
                lut, look.parameters(), look.logGainEv, checkNotNull(look.usage.input).nativeCode,
                checkNotNull(look.usage.output).nativeCode, look.usage.inputRange == LutRange.VIDEO, signal)).also { it.setHasAlpha(false) }
        }

    private external fun readLinearNative(fd: Int, edge: Int, signal: CancellationSignal): LinearRawPreview?
    private external fun renderPreviewNative(pixels: ByteBuffer, width: Int, height: Int, curve: FloatArray, lut: ByteArray?, params: FloatArray,
        gainEv: Float, encoding: Int, outputEncoding: Int, video: Boolean, signal: CancellationSignal): Bitmap?
    private external fun decodeNative(fd: Int, edge: Int, budget: Long, curve: FloatArray, lut: ByteArray?, params: FloatArray,
        gainEv: Float, encoding: Int, outputEncoding: Int, video: Boolean, signal: CancellationSignal): Bitmap?
}
