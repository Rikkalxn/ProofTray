package org.openbridge.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.PhotoAsset

/** Shared render path for editing and export. RAW always goes through LibRaw, never through its embedded preview. */
internal object PhotoRenderer {
    suspend fun decode(context: Context, asset: PhotoAsset, maxEdge: Int, mutable: Boolean,
        rawLook: ColorLookSettings = ColorLookSettings()): Bitmap {
        if (asset.format == MediaFormat.RAW) return RawPhotoDecoder.decode(context, asset, maxEdge, rawLook)
        val budget = minOf(48_000_000L, Runtime.getRuntime().maxMemory() / 12).coerceAtLeast(1_000_000L)
        return withContext(Dispatchers.IO) {
            val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(asset.uri))
            // Both ImageDecoder and LibRaw apply orientation; no second EXIF rotation is needed.
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val edge = maxOf(info.size.width, info.size.height)
                val ratio = if (maxEdge > 0) minOf(1.0, maxEdge.toDouble() / edge) else 1.0
                val width = (info.size.width * ratio).roundToInt().coerceAtLeast(1)
                val height = (info.size.height * ratio).roundToInt().coerceAtLeast(1)
                require(width.toLong() * height <= budget) { "此尺寸超过内存上限，请选择更小的输出长边" }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = mutable
                decoder.setTargetSize(width, height)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
            val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else {
                try { decoded.copy(Bitmap.Config.ARGB_8888, mutable) ?: error("无法创建 sRGB 位图") }
                finally { decoded.recycle() }
            }
            if (Build.VERSION.SDK_INT >= 34) bitmap.setGainmap(null)
            bitmap
        }
    }
}
