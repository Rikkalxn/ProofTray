package org.openbridge.app.color

import android.graphics.Bitmap

internal object NativeColor {
    init { System.loadLibrary("openbridge") }
    external fun load(data: ByteArray, cube: Boolean): Long
    external fun hald(bitmap: Bitmap): Long
    external fun values(handle: Long, metadata: Boolean): FloatArray
    external fun pack(handle: Long): ByteArray
    external fun release(handle: Long)
    external fun apply(handle: Long, bitmap: Bitmap, settings: FloatArray, premultiplied: Boolean, inputEncoding: Int, outputEncoding: Int)
}
