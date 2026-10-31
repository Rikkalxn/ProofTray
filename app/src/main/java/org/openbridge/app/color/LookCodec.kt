package org.openbridge.app.color

import org.json.JSONObject
import org.openbridge.app.domain.ColorLookSettings

object LookCodec {
    fun encode(look: ColorLookSettings): JSONObject = JSONObject().apply {
        put("name", look.name); put("lutId", look.lutId ?: JSONObject.NULL); put("strength", look.strength.toDouble())
        put("exposure", look.exposure.toDouble()); put("contrast", look.contrast.toDouble())
        put("saturation", look.saturation.toDouble()); put("warmth", look.warmth.toDouble())
    }
    fun decode(json: JSONObject): ColorLookSettings {
        fun number(key: String, fallback: Float): Float {
            if (!json.has(key)) return fallback
            val value = json.get(key)
            require(value is Number) { "预设参数 $key 必须是数值" }
            return value.toFloat()
        }
        return ColorLookSettings(
            name = json.optString("name", "自定义").take(128), lutId = if (json.isNull("lutId")) null else json.getString("lutId"),
            strength = number("strength", 1f), exposure = number("exposure", 0f), contrast = number("contrast", 0f),
            saturation = number("saturation", 1f), warmth = number("warmth", 0f)
        ).also { it.validate() }
    }

    fun checkedJson(text: String): JSONObject {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (character == '"' && !escaped) quoted = !quoted
            if (!quoted) {
                if (character == '{' || character == '[') depth++
                if (character == '}' || character == ']') depth--
                require(depth in 0..8) { "预设 JSON 嵌套过深或格式无效" }
            }
            escaped = !escaped && character == '\\'
        }
        return JSONObject(text)
    }
}
