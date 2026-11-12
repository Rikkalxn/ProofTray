package org.openbridge.app.data

import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.PhotoAsset

/** Camera-normalized linear sRGB. Display curves and the technical LUT's scene reference are applied only while rendering. */
internal class LinearRawPreview(val width: Int, val height: Int) {
    @JvmField val pixels: ByteBuffer = ByteBuffer.allocateDirect(width * height * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    @JvmField var sourceWidth: Int = width
    @JvmField var sourceHeight: Int = height
    @JvmField var cameraColorSpace: Int = 0
    @JvmField var baselineExposureEv: Float = 0f

    fun thumbnail(edge: Int): LinearRawPreview {
        require(edge > 0)
        val ratio = minOf(1f, edge.toFloat() / maxOf(width, height))
        if (ratio == 1f) return this
        val small = LinearRawPreview(maxOf(1, (width * ratio).roundToInt()), maxOf(1, (height * ratio).roundToInt()))
        small.baselineExposureEv = baselineExposureEv
        small.sourceWidth = sourceWidth; small.sourceHeight = sourceHeight
        small.cameraColorSpace = cameraColorSpace
        val src = pixels.asFloatBuffer()
        val dst = small.pixels.asFloatBuffer()
        for (y in 0 until small.height) {
            val top = y * height / small.height
            val bottom = (y + 1) * height / small.height
            for (x in 0 until small.width) {
                val left = x * width / small.width
                val right = (x + 1) * width / small.width
                var r = 0f; var g = 0f; var b = 0f
                for (row in top until bottom) {
                    var index = 3 * (row * width + left)
                    for (col in left until right) {
                        r += src.get(index++); g += src.get(index++); b += src.get(index++)
                    }
                }
                val count = ((right - left) * (bottom - top)).toFloat()
                dst.put(r / count).put(g / count).put(b / count)
            }
        }
        return small
    }
}

internal object LogRenderer {
    suspend fun load(context: Context, asset: PhotoAsset, edge: Int): LinearRawPreview =
        RawPhotoDecoder.loadLinear(context, asset, edge)

    suspend fun preview(frame: LinearRawPreview, lut: ByteArray, look: ColorLookSettings): Bitmap {
        require(look.usesLog)
        return RawPhotoDecoder.preview(frame, lut, look)
    }

    suspend fun export(context: Context, asset: PhotoAsset, edge: Int, lut: ByteArray, look: ColorLookSettings): Bitmap {
        require(look.usesLog)
        return RawPhotoDecoder.decode(context, asset, edge, look, lut)
    }
}
