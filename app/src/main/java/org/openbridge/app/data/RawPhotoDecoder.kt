package org.openbridge.app.data

import android.app.ActivityManager
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
    suspend fun decode(context: Context, asset: PhotoAsset, maxEdge: Int,
        look: ColorLookSettings = ColorLookSettings(), packed: ByteArray? = null): Bitmap {
        look.validateForExport(listOf(asset))
        val lut = packed ?: look.lutId?.let { withContext(Dispatchers.IO) { LutRepository.get(context).canonical(it) } }
        return RawRuntime.decode("OpenBridge.raw.develop", Bitmap::recycle) { signal ->
            RawRuntime.withFile(context, asset, signal) { fd ->
                decodeNative(fd, maxEdge, memoryBudget(context), lut, look.parameters(), look.logGainEv,
                    checkNotNull(look.usage.input).nativeCode, checkNotNull(look.usage.output).nativeCode,
                    look.usage.inputRange == LutRange.VIDEO, signal)
            }.also { it.setHasAlpha(false) }
        }
    }

    private fun memoryBudget(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        // LibRaw buffers and Bitmap pixels use native memory on our supported Android versions, not the Java heap limit.
        // Sample inside the decode queue, leaving the low-memory threshold and half the remaining RAM for other work.
        val budget = minOf(2L * 1024 * 1024 * 1024, (info.availMem - info.threshold).coerceAtLeast(0L) / 2)
        check(!info.lowMemory && budget > 0) { "可用内存不足，无法显影 RAW，请关闭其他应用后重试" }
        return budget
    }

    suspend fun loadLinear(context: Context, asset: PhotoAsset, edge: Int): LinearRawPreview =
        RawRuntime.decode("OpenBridge.raw.linearPreview", {}) { signal ->
            require(asset.format == MediaFormat.RAW)
            RawRuntime.withFile(context, asset, signal) { readLinearNative(it, edge, signal) }
        }

    suspend fun preview(frame: LinearRawPreview, lut: ByteArray?, look: ColorLookSettings): Bitmap =
        RawRuntime.decode("OpenBridge.raw.renderPreview", Bitmap::recycle) { signal ->
            look.validate()
            checkNotNull(renderPreviewNative(frame.pixels, frame.width, frame.height, frame.baselineExposureEv,
                lut, look.parameters(), look.logGainEv, checkNotNull(look.usage.input).nativeCode,
                checkNotNull(look.usage.output).nativeCode, look.usage.inputRange == LutRange.VIDEO, signal)).also { it.setHasAlpha(false) }
        }

    private external fun readLinearNative(fd: Int, edge: Int, signal: CancellationSignal): LinearRawPreview?
    private external fun renderPreviewNative(pixels: ByteBuffer, width: Int, height: Int, baselineEv: Float, lut: ByteArray?, params: FloatArray,
        gainEv: Float, encoding: Int, outputEncoding: Int, video: Boolean, signal: CancellationSignal): Bitmap?
    private external fun decodeNative(fd: Int, edge: Int, memoryBudget: Long, lut: ByteArray?, params: FloatArray,
        gainEv: Float, encoding: Int, outputEncoding: Int, video: Boolean, signal: CancellationSignal): Bitmap?
}
