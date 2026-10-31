package org.openbridge.app.camera

internal const val GET_THUMB = 0x100a
internal const val GET_LARGE_THUMB = 0x90c4
internal const val GET_FHD_PICTURE = 0x920f
internal const val GET_LSS_IMAGE = 0x9522

internal data class PreviewDimensions(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    val valid: Boolean get() = width > 0 && height > 0

    fun sufficient(detailed: Boolean): Boolean = valid && maxOf(width, height) >= (if (detailed) 1600 else 640) &&
        minOf(width, height) >= (if (detailed) 900 else 360)

    fun improves(previous: PreviewDimensions?): Boolean = valid && (previous == null || !previous.valid || pixels > previous.pixels)
}

internal data class PreviewCommand(val operation: Int, val size: Int? = null) {
    fun parameters(handle: Long): LongArray = if (size == null) longArrayOf(handle) else longArrayOf(handle, size.toLong(), 0)
}

internal data class CameraPreview(val bytes: ByteArray, val dimensions: PreviewDimensions, val operation: Int)

/** Probe Nikon's read-only large-thumbnail operation even when DeviceInfo omits it; remember explicit rejection per session. */
internal fun previewCommands(detailed: Boolean, advertised: Set<Int>, nikon: Boolean, rejected: Set<Int>): List<PreviewCommand> {
    val portable = PreviewCommand(GET_LSS_IMAGE, if (detailed) 3 else 1) // FULL_HD / VGA, supplied by the camera.
    val candidates = if (detailed) listOf(PreviewCommand(GET_LARGE_THUMB), PreviewCommand(GET_FHD_PICTURE), portable)
        else listOf(PreviewCommand(GET_LARGE_THUMB), portable, PreviewCommand(GET_FHD_PICTURE))
    return (candidates + PreviewCommand(GET_THUMB)).filter {
        it.operation !in rejected && (it.operation == GET_THUMB || it.operation in advertised || (nikon && it.operation == GET_LARGE_THUMB))
    }
}

internal fun canTryAnotherPreview(response: Int): Boolean = response in setOf(0x2005, 0x200a, 0x2010, 0x201d, 0xa00f)
