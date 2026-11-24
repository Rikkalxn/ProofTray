package org.prooftray.app.data

import android.graphics.Bitmap

/** EXIF uncompressed RGB888 has no alpha. Do not sign-extend byte channels or let Bitmap premultiply them by zero. */
internal fun decodeRgbThumbnail(bytes: ByteArray, width: Int, height: Int): Bitmap? {
    val count = width.toLong() * height
    if (width <= 0 || height <= 0 || count !in 1..2_000_000L || count * 3 > bytes.size) return null
    val pixels = IntArray(count.toInt())
    var offset = 0
    for (index in pixels.indices) {
        val red = bytes[offset++].toInt() and 0xff
        val green = bytes[offset++].toInt() and 0xff
        val blue = bytes[offset++].toInt() and 0xff
        pixels[index] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
}
