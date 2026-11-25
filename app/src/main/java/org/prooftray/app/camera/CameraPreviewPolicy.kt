package org.prooftray.app.camera

import org.prooftray.app.domain.PreviewDimensions

internal const val GET_THUMB = 0x100a
internal const val GET_LARGE_THUMB = 0x90c4
internal const val GET_FHD_PICTURE = 0x920f
internal const val GET_LSS_IMAGE = 0x9522

internal data class PreviewCommand(val operation: Int, val size: Int? = null) {
    fun parameters(handle: Long): LongArray = if (size == null) longArrayOf(handle) else longArrayOf(handle, size.toLong(), 0)
}

internal data class CameraPreview(val bytes: ByteArray, val dimensions: PreviewDimensions, val operation: Int, val extension: String = "jpg")

/** Probe Nikon's read-only large-thumbnail operation even when DeviceInfo omits it; remember explicit rejection per session. */
internal fun previewCommands(detailed: Boolean, advertised: Set<Int>, nikon: Boolean, rejected: Set<Int>): List<PreviewCommand> {
    if (!nikon) return listOf(PreviewCommand(GET_THUMB)).filterNot { it.operation in rejected }
    val portable = PreviewCommand(GET_LSS_IMAGE, if (detailed) 3 else 1) // FULL_HD / VGA, supplied by the camera.
    val candidates = if (detailed) listOf(portable, PreviewCommand(GET_LARGE_THUMB), PreviewCommand(GET_FHD_PICTURE))
        else listOf(PreviewCommand(GET_LARGE_THUMB), portable, PreviewCommand(GET_FHD_PICTURE))
    return (candidates + PreviewCommand(GET_THUMB)).filter {
        it.operation !in rejected && (it.operation == GET_THUMB || it.operation in advertised || (nikon && it.operation == GET_LARGE_THUMB))
    }
}

internal fun canTryAnotherPreview(response: Int): Boolean = response in setOf(0x2005, 0x200a, 0x2010, 0x201d, 0xa00f)
