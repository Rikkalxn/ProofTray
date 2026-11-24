package org.openbridge.app.data

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openbridge.app.color.LutRepository
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.LutRange
import org.openbridge.app.domain.LogExportSettings
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset

/** Develop the sensor data for editing and export. Browsing uses RawPreviewExtractor instead. */
internal object RawPhotoDecoder {
    suspend fun exportLog(context: Context, asset: PhotoAsset, maxEdge: Int, settings: LogExportSettings): LogTiffFile {
        settings.validate()
        require(asset.format == MediaFormat.RAW && maxEdge >= 0) { "Log 直出需要 RAW 原片和有效尺寸" }
        return RawRuntime.decode("OpenBridge.raw.logTiff", { it.file.delete() }) { signal ->
            // Create and own the temporary inside the worker. Cancellation must never remove a file still being written.
            val temporary = File.createTempFile("log-export-", ".tif", context.cacheDir)
            try {
                val dimensions = ParcelFileDescriptor.open(temporary,
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { output ->
                    RawRuntime.withFile(context, asset, signal) { input ->
                        writeLogNative(input, output.fd, maxEdge, memoryBudget(context), checkNotNull(settings.encoding).nativeCode,
                            settings.exposure, settings.warmth, settings.gainEv, settings.tint, signal)
                    }
                }
                signal.throwIfCanceled()
                check(dimensions.size == 2 && dimensions.all { it > 0 } && temporary.length() > 0) { "Log 文件写出失败" }
                LogTiffFile(temporary, dimensions[0], dimensions[1])
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
        }
    }

    suspend fun decode(context: Context, asset: PhotoAsset, maxEdge: Int,
        look: ColorLookSettings = ColorLookSettings(), packed: ByteArray? = null): Bitmap {
        look.validateForExport(listOf(asset))
        val lut = packed ?: look.lutId?.let { withContext(Dispatchers.IO) { LutRepository.get(context).canonical(it) } }
        return RawRuntime.decode("OpenBridge.raw.develop", Bitmap::recycle) { signal ->
            RawRuntime.withFile(context, asset, signal) { fd ->
                decodeNative(fd, maxEdge, memoryBudget(context), lut, look.parameters(), look.adjustments.parameters(), look.logGainEv,
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
                lut, look.parameters(), look.adjustments.parameters(), look.logGainEv, checkNotNull(look.usage.input).nativeCode,
                checkNotNull(look.usage.output).nativeCode, look.usage.inputRange == LutRange.VIDEO, signal)).also { it.setHasAlpha(false) }
        }

    private external fun readLinearNative(fd: Int, edge: Int, signal: CancellationSignal): LinearRawPreview?
    private external fun renderPreviewNative(pixels: ByteBuffer, width: Int, height: Int, baselineEv: Float, lut: ByteArray?, params: FloatArray,
        edits: FloatArray, gainEv: Float, encoding: Int, outputEncoding: Int, video: Boolean, signal: CancellationSignal): Bitmap?
    private external fun decodeNative(fd: Int, edge: Int, memoryBudget: Long, lut: ByteArray?, params: FloatArray, edits: FloatArray,
        gainEv: Float, encoding: Int, outputEncoding: Int, video: Boolean, signal: CancellationSignal): Bitmap?
    private external fun writeLogNative(fd: Int, outputFd: Int, edge: Int, memoryBudget: Long, encoding: Int,
        exposure: Float, warmth: Float, gainEv: Float, tint: Float, signal: CancellationSignal): IntArray?
}

internal data class LogTiffFile(val file: File, val width: Int, val height: Int)
