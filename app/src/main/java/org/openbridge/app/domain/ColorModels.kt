package org.openbridge.app.domain

data class ColorLookSettings(
    val name: String = "原图", val lutId: String? = null, val strength: Float = 1f,
    val exposure: Float = 0f, val contrast: Float = 0f, val saturation: Float = 1f, val warmth: Float = 0f
) {
    val isIdentity: Boolean get() = strength == 0f || (lutId == null && exposure == 0f && contrast == 0f && saturation == 1f && warmth == 0f)
    fun parameters(): FloatArray = floatArrayOf(strength, exposure, contrast, saturation, warmth)
    fun validate() {
        require(parameters().all(Float::isFinite)) { "调色参数必须为有限数值" }
        require(strength in 0f..1f && exposure in -3f..3f && contrast in -1f..1f && saturation in 0f..2f && warmth in -1f..1f) {
            "调色参数超出范围"
        }
        require(lutId == null || lutId.matches(Regex("[a-f0-9]{64}"))) { "LUT 标识无效" }
    }
}

data class LutAsset(val id: String, val name: String, val dimension: Int, val size: Int, val sourceFormat: String)
data class ColorPreset(val id: String, val name: String, val look: ColorLookSettings, val builtIn: Boolean = false)
data class LutPreviewData(
    val id: String, val dimension: Int, val size: Int, val domainMin: FloatArray, val domainMax: FloatArray, val values: FloatArray
)

val builtInColorPresets = listOf(
    ColorPreset("original", "原图", ColorLookSettings(), true),
    ColorPreset("clear", "清透", ColorLookSettings("清透", exposure = 0.08f, contrast = 0.06f, saturation = 1.06f), true),
    ColorPreset("warm", "暖光", ColorLookSettings("暖光", exposure = 0.05f, saturation = 0.95f, warmth = 0.35f), true),
    ColorPreset("cool", "冷调", ColorLookSettings("冷调", contrast = 0.07f, saturation = 0.9f, warmth = -0.3f), true),
    ColorPreset("soft", "柔和", ColorLookSettings("柔和", exposure = 0.1f, contrast = -0.12f, saturation = 0.88f), true),
    ColorPreset("mono", "黑白", ColorLookSettings("黑白", contrast = 0.12f, saturation = 0f), true)
)
