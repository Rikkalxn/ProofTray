package org.prooftray.app.color

import org.json.JSONArray
import org.json.JSONObject
import org.prooftray.app.domain.ColorAdjustments
import org.prooftray.app.domain.CurvePoint
import org.prooftray.app.domain.GradeWheel
import org.prooftray.app.domain.HslBand
import org.prooftray.app.domain.ToneCurve

internal object ColorAdjustmentsCodec {
    fun encode(value: ColorAdjustments) = JSONObject().apply {
        value.validate()
        put("highlights", value.highlights.toDouble()); put("shadows", value.shadows.toDouble())
        put("whites", value.whites.toDouble()); put("blacks", value.blacks.toDouble()); put("vibrance", value.vibrance.toDouble())
        put("blending", value.blending.toDouble()); put("balance", value.balance.toDouble())
        put("curves", JSONArray(value.curves.map { curve -> JSONArray(curve.points.map { array(it.x, it.y) }) }))
        put("hsl", JSONArray(value.hsl.map { array(it.hue, it.saturation, it.luminance) }))
        put("grading", JSONArray(value.grading.map { array(it.hue, it.saturation, it.luminance) }))
    }

    fun decode(json: JSONObject): ColorAdjustments {
        fun scalar(key: String): Float = number(json.get(key))
        fun channels(key: String, count: Int): List<JSONArray> {
            val array = json.getJSONArray(key)
            require(array.length() == count) { "调整通道 $key 数量无效" }
            return List(count) { array.getJSONArray(it) }
        }
        return ColorAdjustments(scalar("highlights"), scalar("shadows"), scalar("whites"), scalar("blacks"), scalar("vibrance"),
            curves = channels("curves", 4).map { curve ->
                require(curve.length() in 2..16) { "曲线控制点数量无效" }
                ToneCurve(List(curve.length()) { index ->
                    val point = curve.getJSONArray(index)
                    require(point.length() == 2) { "曲线坐标无效" }
                    CurvePoint(number(point.get(0)), number(point.get(1)))
                })
            }, hsl = channels("hsl", 8).map { row ->
                require(row.length() == 3) { "HSL 参数数量无效" }
                HslBand(number(row.get(0)), number(row.get(1)), number(row.get(2)))
            }, grading = channels("grading", 4).map { row ->
                require(row.length() == 3) { "颜色分级参数数量无效" }
                GradeWheel(number(row.get(0)), number(row.get(1)), number(row.get(2)))
            }, blending = scalar("blending"), balance = scalar("balance")).also(ColorAdjustments::validate)
    }

    private fun array(vararg values: Float) = JSONArray(values.map(Float::toDouble))
    private fun number(value: Any): Float {
        require(value is Number) { "高级调整参数必须是数值" }
        return value.toFloat().also { require(it.isFinite()) { "高级调整参数必须为有限数值" } }
    }
}
