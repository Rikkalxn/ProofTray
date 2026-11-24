package org.prooftray.app.frame

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.prooftray.app.domain.FrameDesign
import org.prooftray.app.domain.allLayers

internal data class FrameOutputSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

internal object FrameMemoryBudget {
    fun output(photoWidth: Int, photoHeight: Int, design: FrameDesign, maxEdge: Int): FrameOutputSize {
        val geometry = FrameGeometry.create(photoWidth, photoHeight, design)
        val sourceScale = min(photoWidth, photoHeight) / FRAME_UNIT
        val fullW = geometry.canvas.width * sourceScale; val fullH = geometry.canvas.height * sourceScale
        val scale = if (maxEdge > 0) min(1f, maxEdge / max(fullW, fullH)) else 1f
        val size = FrameOutputSize(max(1, (fullW * scale).roundToInt()), max(1, (fullH * scale).roundToInt()))
        require(size.width <= 32768 && size.height <= 32768 && size.pixels <= 48_000_000L) { "含边框的尺寸过大，请减小导出长边" }
        return size
    }

    fun check(size: FrameOutputSize, sourcePixels: Long = 0L, design: FrameDesign? = null) {
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val reserve = 32L * 1024 * 1024
        val shadows = design != null && (design.photo.shadow > 0 || design.allLayers().any { (it.decoration?.shadow ?: 0f) > 0 })
        // Two alpha masks for software shadows; bounded images, fonts and background scratch have a separate allowance.
        val required = size.pixels * (if (shadows) 6 else 4) + sourcePixels * 4 + 64L * 1024 * 1024
        require(required <= min(runtime.maxMemory() * 3 / 4, available - reserve)) { "含边框的画布超过内存预算，请选择较小尺寸" }
    }
}
