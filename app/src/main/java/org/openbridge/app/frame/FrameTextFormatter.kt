package org.openbridge.app.frame

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.openbridge.app.domain.FrameFieldFormat
import org.openbridge.app.domain.FrameTemplate
import org.openbridge.app.domain.FrameText

internal data class FrameTextRun(val text: String, val color: String?, val weight: Int?)

/** Shared by the canvas and export. Missing values remove the complete part, including its label and punctuation. */
internal object FrameTextFormatter {
    val fields = linkedMapOf("make" to "品牌", "camera" to "机型", "lens" to "镜头", "focal" to "焦距", "focal35" to "等效焦距",
        "aperture" to "光圈", "shutter" to "快门", "iso" to "ISO", "time" to "时间", "gps" to "位置", "custom" to "自定义",
        "author" to "作者", "copyright" to "版权")
    // Android's ICU regex engine requires both literal braces to be escaped (the desktop JVM is more permissive).
    private val placeholder = Regex("\\{([A-Za-z0-9_]+)\\}")

    fun validate(text: String, literal: Boolean = false) {
        require(text.length <= 2048) { "单个文字片段最多 2048 字符" }
        if (!literal) placeholder.findAll(text).forEach { require(it.groupValues[1] in fields) { "不支持的字段：" + it.groupValues[1] } }
        require(!text.contains('\u0000')) { "文字包含无效字符" }
    }

    fun runs(text: FrameText, values: Map<String, String>, template: FrameTemplate): List<FrameTextRun> = text.parts.mapNotNull { part ->
        if (part.literal) return@mapNotNull part.text.takeIf { it.isNotBlank() }?.let { FrameTextRun(it, part.color, part.weight) }
        var missing = false
        val result = placeholder.replace(part.text) { match ->
            val key = match.groupValues[1]
            val raw = when {
                key == "custom" -> template.custom
                key == "time" && !template.showTime -> ""
                key == "gps" && !template.showGps -> ""
                else -> values[key].orEmpty()
            }
            val formatted = format(key, raw, part.format)
            if (formatted.isBlank()) missing = true
            formatted
        }.trim()
        if (missing || result.isBlank()) null else FrameTextRun(result, part.color, part.weight)
    }

    fun format(field: String, raw: String, mode: FrameFieldFormat = FrameFieldFormat.DEFAULT): String {
        if (raw.isBlank()) return ""
        if (field == "gps") return gps(raw, mode == FrameFieldFormat.DMS)
        if (field !in setOf("focal", "focal35", "aperture", "shutter", "iso")) return raw
        val value = raw.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return ""
        if (field == "shutter") return shutter(value, mode)
        val number = decimal(value, if (field == "aperture") 2 else if (field == "iso" || field == "focal35") 0 else 1)
        if (mode == FrameFieldFormat.VALUE) return number
        return when (field) { "focal", "focal35" -> number + "mm"; "aperture" -> "f/" + number; "iso" -> "ISO " + number; else -> number }
    }

    fun shutter(seconds: Double, mode: FrameFieldFormat): String {
        if (!seconds.isFinite() || seconds <= 0) return ""
        val reciprocal = 1.0 / seconds
        val divisor = reciprocal.coerceAtMost(1_000_000.0).roundToInt()
        val fraction = divisor >= 2 && abs(1.0 / divisor - seconds) / seconds <= 0.01
        val value = if (mode != FrameFieldFormat.SECONDS && (fraction || mode == FrameFieldFormat.RECIPROCAL) && divisor >= 2) {
            "1/" + divisor
        } else decimal(seconds, if (seconds < 0.01) 6 else 3)
        return value + if (mode == FrameFieldFormat.VALUE) "" else "s"
    }

    private fun gps(raw: String, dms: Boolean): String {
        val numbers = raw.split(',').map { it.toDoubleOrNull() }
        val lat = numbers.getOrNull(0)?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return ""
        val lon = numbers.getOrNull(1)?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return ""
        fun coordinate(value: Double, positive: String, negative: String): String {
            val suffix = if (value < 0) negative else positive
            if (!dms) return String.format(Locale.ROOT, "%.4f°", abs(value)) + suffix
            val total = (abs(value) * 3600).roundToInt()
            return (total / 3600).toString() + "°" + (total / 60 % 60) + "′" + (total % 60) + "″" + suffix
        }
        return coordinate(lat, "N", "S") + "  " + coordinate(lon, "E", "W")
    }

    private fun decimal(value: Double, places: Int): String =
        BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
