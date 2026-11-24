package org.prooftray.app.domain

/** Quality is determined by actual pixels, never by a grid/detail filename or the import source. */
internal data class PreviewDimensions(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    val valid: Boolean get() = width > 0 && height > 0
    val longestEdge: Int get() = maxOf(width, height)

    fun sufficient(detailed: Boolean): Boolean = valid && longestEdge >= (if (detailed) 1600 else 640) &&
        minOf(width, height) >= (if (detailed) 900 else 360)

    fun covers(edge: Int): Boolean = valid && longestEdge >= edge

    fun improves(previous: PreviewDimensions?): Boolean = valid && (previous == null || !previous.valid || pixels > previous.pixels)
}
