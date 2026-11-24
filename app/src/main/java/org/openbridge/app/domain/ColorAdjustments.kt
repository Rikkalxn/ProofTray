package org.openbridge.app.domain

data class CurvePoint(val x: Float, val y: Float)

data class ToneCurve(val points: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))) {
    val isIdentity: Boolean get() = points.all { it.x == it.y }
    fun insert(x: Float, y: Float): ToneCurve {
        if (points.size == 16 || points.any { kotlin.math.abs(it.x - x) < .002f }) return this
        return copy(points = (points + CurvePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))).sortedBy { it.x })
    }
    fun move(index: Int, x: Float, y: Float): ToneCurve {
        if (index !in points.indices) return this
        val nextX = if (index == 0 || index == points.lastIndex) points[index].x else {
            val low = points[index - 1].x + .00101f
            val high = points[index + 1].x - .00101f
            if (low <= high) x.coerceIn(low, high) else points[index].x
        }
        return copy(points = points.mapIndexed { i, old -> if (i == index) CurvePoint(nextX, y.coerceIn(0f, 1f)) else old })
    }
    fun remove(index: Int): ToneCurve =
        if (index <= 0 || index >= points.lastIndex) this else copy(points = points.filterIndexed { i, _ -> i != index })

    /** Curve graph only. Image processing uses the native counterpart and its shared display table. */
    fun evaluate(position: Float): Float {
        val x = position.coerceIn(0f, 1f)
        val next = points.indexOfFirst { it.x > x }.let { if (it < 0) points.lastIndex else it.coerceAtLeast(1) }
        val a = points[next - 1]; val b = points[next]
        val h = b.x - a.x; val t = (x - a.x) / h; val t2 = t * t; val t3 = t2 * t
        fun tangent(i: Int): Float {
            if (i == 0) return (points[1].y - points[0].y) / (points[1].x - points[0].x)
            if (i == points.lastIndex) return (points[i].y - points[i - 1].y) / (points[i].x - points[i - 1].x)
            val left = points[i].x - points[i - 1].x; val right = points[i + 1].x - points[i].x
            val d1 = (points[i].y - points[i - 1].y) / left; val d2 = (points[i + 1].y - points[i].y) / right
            if (d1 * d2 <= 0f) return 0f
            val w1 = 2f * right + left; val w2 = right + 2f * left
            return (w1 + w2) / (w1 / d1 + w2 / d2)
        }
        return ((2f * t3 - 3f * t2 + 1f) * a.y + (t3 - 2f * t2 + t) * h * tangent(next - 1) +
            (-2f * t3 + 3f * t2) * b.y + (t3 - t2) * h * tangent(next)).coerceIn(minOf(a.y, b.y), maxOf(a.y, b.y))
    }
    fun validate() {
        require(points.size in 2..16 && points.first().x == 0f && points.last().x == 1f) { "曲线需要 2–16 个点及两个端点" }
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }) { "曲线坐标无效" }
        require(points.zipWithNext().all { (a, b) -> b.x - a.x >= .001f }) { "曲线控制点不能重叠" }
    }
}

data class HslBand(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f)
data class GradeWheel(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f)

/** Display-referred edits. These controls do not reconstruct clipped sensor channels. */
data class ColorAdjustments(
    val highlights: Float = 0f, val shadows: Float = 0f, val whites: Float = 0f, val blacks: Float = 0f, val vibrance: Float = 0f,
    val curves: List<ToneCurve> = List(4) { ToneCurve() },
    val hsl: List<HslBand> = List(8) { HslBand() },
    val grading: List<GradeWheel> = List(4) { GradeWheel() },
    val blending: Float = .5f, val balance: Float = 0f
) {
    fun withGradingWheel(index: Int, value: GradeWheel): ColorAdjustments {
        require(index in 0..3 && grading.size == 4) { "颜色分级范围无效" }
        return copy(grading = grading.mapIndexed { i, old -> if (i == index) value else old })
    }

    fun resetGrading(): ColorAdjustments = copy(grading = List(4) { GradeWheel() }, blending = .5f, balance = 0f)

    val isIdentity: Boolean get() = highlights == 0f && shadows == 0f && whites == 0f && blacks == 0f && vibrance == 0f &&
        curves.all { it.isIdentity } && hsl.all { it == HslBand() } && grading.all { it.saturation == 0f && it.luminance == 0f }

    fun validate() {
        val signed = listOf(highlights, shadows, whites, blacks, vibrance, balance) +
            hsl.flatMap { listOf(it.hue, it.saturation, it.luminance) } + grading.map { it.luminance }
        require(signed.all { it.isFinite() && it in -1f..1f }) { "高级调整参数超出范围" }
        require(blending.isFinite() && blending in 0f..1f) { "颜色分级混合超出范围" }
        require(curves.size == 4 && hsl.size == 8 && grading.size == 4) { "高级调整通道数量无效" }
        require(grading.all { it.hue.isFinite() && it.hue in 0f..360f && it.saturation.isFinite() && it.saturation in 0f..1f }) {
            "颜色分级参数无效"
        }
        curves.forEach(ToneCurve::validate)
    }

    /** Bounded native wire format: 43 scalar controls, then four point counts and (x,y) pairs. */
    fun parameters(): FloatArray {
        validate()
        return buildList {
            addAll(listOf(highlights, shadows, whites, blacks, vibrance, blending, balance))
            hsl.forEach { addAll(listOf(it.hue, it.saturation, it.luminance)) }
            grading.forEach { addAll(listOf(it.hue, it.saturation, it.luminance)) }
            curves.forEach { curve ->
                add(curve.points.size.toFloat())
                curve.points.forEach { add(it.x); add(it.y) }
            }
        }.toFloatArray()
    }
}
