package org.prooftray.app.frame

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Small, cached backgrounds only. Three separable box passes approximate a Gaussian on every supported API level. */
internal object FrameBackgroundEffects {
    fun blur(source: Bitmap, width: Float, height: Float, radius: Float, cancelled: () -> Unit): Bitmap {
        val scale = min(1f, 640f / max(width, height))
        val w = max(1, (width * scale).roundToInt()); val h = max(1, (height * scale).roundToInt())
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(small)
            canvas.drawColor(Color.WHITE)
            val fit = max(w.toFloat() / source.width, h.toFloat() / source.height)
            val dx = (w - source.width * fit) / 2; val dy = (h - source.height * fit) / 2
            canvas.drawBitmap(source, null, RectF(dx, dy, dx + source.width * fit, dy + source.height * fit), Paint(Paint.FILTER_BITMAP_FLAG))
            if (radius <= 0f) return small
            val pixels = IntArray(w * h)
            val scratch = IntArray(pixels.size)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            val r = (radius * FRAME_UNIT * scale).roundToInt().coerceIn(1, 64)
            repeat(3) {
                pass(pixels, scratch, w, h, r, true, cancelled)
                pass(scratch, pixels, w, h, r, false, cancelled)
            }
            small.setPixels(pixels, 0, w, 0, 0, w, h)
            return small
        } catch (error: Throwable) { small.recycle(); throw error }
    }

    private fun pass(input: IntArray, output: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean, cancelled: () -> Unit) {
        val length = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val divisor = radius * 2 + 1
        for (line in 0 until lines) {
            cancelled()
            fun index(position: Int): Int = if (horizontal) line * w + position.coerceIn(0, w - 1)
                else position.coerceIn(0, h - 1) * w + line
            var red = 0; var green = 0; var blue = 0
            for (position in -radius..radius) {
                val c = input[index(position)]
                red += c shr 16 and 255; green += c shr 8 and 255; blue += c and 255
            }
            for (position in 0 until length) {
                output[index(position)] = Color.rgb(red / divisor, green / divisor, blue / divisor)
                val add = input[index(position + radius + 1)]; val remove = input[index(position - radius)]
                red += (add shr 16 and 255) - (remove shr 16 and 255)
                green += (add shr 8 and 255) - (remove shr 8 and 255)
                blue += (add and 255) - (remove and 255)
            }
        }
    }

    /** Original procedural paper grain; no texture or logo is extracted from another application's assets. */
    fun texture(): Bitmap {
        val random = Random(0x50524F4F)
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(192 * 192) {
            val light = random.nextInt(75, 210)
            Color.argb(random.nextInt(20, 85), light, light, light)
        }
        bitmap.setPixels(pixels, 0, 192, 0, 0, 192, 192)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(32, 230, 230, 230); strokeWidth = 0.6f }
        repeat(180) {
            val x = random.nextFloat() * 192; val y = random.nextFloat() * 192
            canvas.drawLine(x, y, x + random.nextFloat() * 6, y + random.nextFloat() * 3, paint)
        }
        return bitmap
    }
}
