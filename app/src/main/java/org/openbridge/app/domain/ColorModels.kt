package org.openbridge.app.domain

data class ColorLookSettings(
    val name: String = "原图", val lutId: String? = null, val strength: Float = 1f,
    val exposure: Float = 0f, val contrast: Float = 0f, val saturation: Float = 1f, val warmth: Float = 0f, val usage: LutUsage = LutUsage.direct,
    val logGainEv: Float = 0f // Additional gain above the renderer's RAW exposure reference, not a camera calibration.
) {
    val lutKey: String? get() = lutId?.let { "lut:$it:${usage.key}" }
    val usesLog get() = usage.input?.isLog == true
    fun withUsage(value: LutUsage) = copy(usage = value, logGainEv = if (value.input?.isLog == true) logGainEv else 0f)
    // RAW Log at 0% uses the same default display recipe as ordinary RAW, without user K or grading adjustments.
    val isIdentity: Boolean get() = usage.unavailableReason == null && !usesLog &&
        (strength == 0f || (lutId == null && exposure == 0f && contrast == 0f && saturation == 1f && warmth == 0f))
    fun parameters(): FloatArray = floatArrayOf(strength, exposure, contrast, saturation, warmth)
    fun validateForExport(assets: List<PhotoAsset>) {
        validate()
        if (usesLog) require(assets.all { it.format == MediaFormat.RAW }) { "Log 输入需要 RAW 原片" }
    }
    fun validate() {
        usage.validateForPhoto()
        validateParameters()
    }

    internal fun validateParameters() {
        require(logGainEv.isFinite() && logGainEv in -3f..3f) { "Log 输入增益超出范围" }
        require(!usesLog || lutId != null) { "请选择 Log LUT" }
        require(parameters().all(Float::isFinite)) { "调色参数必须为有限数值" }
        require(strength in 0f..1f && exposure in -3f..3f && contrast in -1f..1f && saturation in 0f..2f && warmth in -1f..1f) {
            "调色参数超出范围"
        }
        require(lutId == null || lutId.matches(Regex("[a-f0-9]{64}"))) { "LUT 标识无效" }
    }
}

const val LINEAR_DOMAIN_WARNING = "线性 LUT 未声明输入范围，高光会被截断"

data class LutAsset(val id: String, val name: String, val dimension: Int, val size: Int, val sourceFormat: String, val defaultUsage: LutUsage,
    val implicitUnitDomain: Boolean? = null, val removed: Boolean = false) {
    fun look() = ColorLookSettings(name, id, usage = defaultUsage)
    fun domainWarning(usage: LutUsage = defaultUsage) =
        LINEAR_DOMAIN_WARNING.takeIf { implicitUnitDomain == true && usage.input == LutEncoding.LINEAR_709 }
}
/** A look the user saved. There are no built-in presets; the editor offers "原图" (no look) on its own. */
data class ColorPreset(val id: String, val name: String, val look: ColorLookSettings)
data class LutPreviewData(
    val id: String, val dimension: Int, val size: Int, val domainMin: FloatArray, val domainMax: FloatArray, val values: FloatArray
)
