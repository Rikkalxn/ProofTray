package org.openbridge.app.data

import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class RawToneOrigin { CAMERA_PREVIEW, GENERIC }
internal enum class RawToneFallback { NO_PREVIEW, SMALL_PREVIEW, COLOR_SPACE, ASPECT, MONOCHROME, UNRELIABLE, UNAVAILABLE }

/** Immutable display recipe. Empty samples select the core's generic curve; never pre-scale the linear buffer. */
internal data class RawToneProfile(val samples: List<Float>, val origin: RawToneOrigin, val fallback: RawToneFallback? = null) {
    init {
        require(if (origin == RawToneOrigin.GENERIC) samples.isEmpty() && fallback != null else fallback == null && validSamples(samples))
    }

    companion object {
        const val VERSION = 1
        const val SAMPLE_COUNT = 257
        const val REFERENCE_EDGE = 512
        fun generic(reason: RawToneFallback) = RawToneProfile(emptyList(), RawToneOrigin.GENERIC, reason)
        fun validSamples(values: List<Float>): Boolean = values.size == SAMPLE_COUNT && values.first() == 0f && values.last() == 1f &&
            values.all { it.isFinite() && it in 0f..1f } && values.zipWithNext().all { (a, b) -> b >= a && b - a <= 12f / (SAMPLE_COUNT - 1) }
    }
}

internal data class RawToneKey(val id: String, val uri: String, val size: Long, val revision: String,
    val inputProfile: String = "libraw-camera-wb-linear-srgb-v1", val version: Int = RawToneProfile.VERSION) {
    val identity: String get() = listOf(version.toString(), inputProfile, id, uri, size.toString(), revision)
        .joinToString("") { "${it.length}:$it" }
}

/** Histogram matching estimates a global tone only, not a camera's colour style or local ADL/DRO. */
internal object RawToneMatcher {
    private const val BINS = 1024
    private val linear = FloatArray(256) { decode(it / 255f) }
    private fun encode(value: Float): Float = if (value <= .0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - .055f
    private fun decode(value: Float): Float = if (value <= .04045f) value / 12.92f else ((value + .055f) / 1.055f).pow(2.4f)
    private fun bin(value: Float) = (encode(value.coerceIn(0f, 1f)) * (BINS - 1)).roundToInt().coerceIn(0, BINS - 1)

    fun previewProblem(rawWidth: Int, rawHeight: Int, previewWidth: Int, previewHeight: Int): RawToneFallback? = when {
        rawWidth <= 0 || rawHeight <= 0 || previewWidth <= 0 || previewHeight <= 0 -> RawToneFallback.ASPECT
        previewWidth.toLong() * previewHeight < 19200 ||
            max(previewWidth, previewHeight).toLong() * 33 < max(rawWidth, rawHeight) -> RawToneFallback.SMALL_PREVIEW
        abs(previewWidth.toDouble() / previewHeight / (rawWidth.toDouble() / rawHeight) - 1) > .015 -> RawToneFallback.ASPECT
        else -> null
    }

    fun fit(source: FloatBuffer, sourceWidth: Int, sourceHeight: Int, target: IntArray,
        targetWidth: Int, targetHeight: Int, cancelled: () -> Unit = {}): RawToneProfile {
        fun fallback(reason: RawToneFallback = RawToneFallback.UNRELIABLE) = RawToneProfile.generic(reason)
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0 ||
            source.limit().toLong() != sourceWidth.toLong() * sourceHeight * 3 || target.size.toLong() != targetWidth.toLong() * targetHeight) {
            return fallback()
        }
        if (abs(targetWidth.toDouble() / targetHeight / (sourceWidth.toDouble() / sourceHeight) - 1) > .015) {
            return fallback(RawToneFallback.ASPECT)
        }
        val src = IntArray(BINS)
        val dst = IntArray(BINS)
        var colored = 0
        var usable = 0
        for (row in 0 until sourceHeight) {
            cancelled()
            for (col in 0 until sourceWidth) {
                val index = (row * sourceWidth + col) * 3
                val r = source.get(index); val g = source.get(index + 1); val b = source.get(index + 2)
                if (!r.isFinite() || !g.isFinite() || !b.isFinite()) return fallback()
                src[bin(.2126f * max(r, 0f) + .7152f * max(g, 0f) + .0722f * max(b, 0f))]++
            }
        }
        for (row in 0 until targetHeight) {
            cancelled()
            for (col in 0 until targetWidth) {
                val pixel = target[row * targetWidth + col]
                if ((pixel ushr 24) != 255) return fallback()
                val r = pixel ushr 16 and 255; val g = pixel ushr 8 and 255; val b = pixel and 255
                val high = max(r, max(g, b)); val low = min(r, min(g, b))
                if (high in 12..243) { usable++; if (high - low >= 5) colored++ }
                dst[bin(.2126f * linear[r] + .7152f * linear[g] + .0722f * linear[b])]++
            }
        }
        if (usable == 0 || colored.toDouble() / usable < .005) return fallback(RawToneFallback.MONOCHROME)
        return fromHistograms(src, dst)
    }

    internal fun fromHistograms(source: IntArray, target: IntArray): RawToneProfile {
        fun fallback() = RawToneProfile.generic(RawToneFallback.UNRELIABLE)
        if (source.size != BINS || target.size != BINS || source.any { it < 0 } || target.any { it < 0 }) return fallback()
        fun cdf(values: IntArray): DoubleArray {
            val total = values.sumOf(Int::toLong).toDouble()
            var count = 0.0
            return DoubleArray(BINS) { count += values[it]; if (total > 0) count / total else 0.0 }
        }
        val src = cdf(source); val dst = cdf(target)
        if (src.last() == 0.0 || dst.last() == 0.0) return fallback()
        fun quantile(values: DoubleArray, q: Double) = values.indexOfFirst { it >= q }.toFloat() / (BINS - 1)
        if (quantile(src, .98) - quantile(src, .02) < .06 || quantile(dst, .98) - quantile(dst, .02) < .06) return fallback()
        val groups = sortedMapOf<Float, MutableList<Float>>()
        for (q in listOf(.005, .01, .02, .04, .08, .16, .25, .35, .5, .65, .8, .9, .95, .98, .995)) {
            val x = quantile(src, q)
            if (x > 0 && x < 1) groups.getOrPut(x) { mutableListOf() }.add(quantile(dst, q))
        }
        if (groups.size < 5) return fallback()
        val knots = listOf(0f to 0f) + groups.map { (x, values) -> x to values.average().toFloat() } + (1f to 1f)
        if (knots.zipWithNext().any { (a, b) -> b.second < a.second || (b.second - a.second) / (b.first - a.first) > 12 }) return fallback()
        val samples = List(RawToneProfile.SAMPLE_COUNT) { i ->
            val x = i.toFloat() / (RawToneProfile.SAMPLE_COUNT - 1)
            val end = knots.indexOfFirst { it.first >= x }.coerceAtLeast(1)
            val a = knots[end - 1]; val b = knots[end]
            a.second + (x - a.first) / (b.first - a.first) * (b.second - a.second)
        }.toMutableList().also { it[0] = 0f; it[it.lastIndex] = 1f }
        if (!RawToneProfile.validSamples(samples)) return fallback()
        fun mapped(x: Float): Float {
            val position = x * (samples.size - 1)
            val index = position.toInt().coerceIn(0, samples.lastIndex - 1)
            return samples[index] + (samples[index + 1] - samples[index]) * (position - index)
        }
        val error = (1..19).map { q -> abs(mapped(quantile(src, q / 20.0)) - quantile(dst, q / 20.0)) }.average()
        if (error > .035) return fallback()
        return RawToneProfile(samples.toList(), RawToneOrigin.CAMERA_PREVIEW)
    }
}
