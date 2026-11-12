package org.openbridge.app.domain

/** Native IDs are persistent. The declaration order is the input menu order. */
enum class LutEncoding(val nativeCode: Int, val label: String, val isLog: Boolean = true) {
    DIRECT(0, "sRGB · 直接套用", false),
    REC709_G24(2, "Rec.709 γ2.4", false),
    LINEAR_709(4, "Linear · Rec.709"),
    SLOG2_SGAMUT(14, "S-Log2 · S-Gamut"),
    SLOG3_SGAMUT3CINE(5, "S-Log3 · S-Gamut3.Cine"),
    SLOG3_SGAMUT3(6, "S-Log3 · S-Gamut3"),
    NLOG(3, "N-Log · BT.2020"),
    LOG3G10_RWG(7, "RED Log3G10 · REDWideGamutRGB"),
    VLOG(8, "V-Log · V-Gamut"),
    CLOG2(9, "Canon Log 2 · Cinema Gamut"),
    CLOG3(10, "Canon Log 3 · Cinema Gamut"),
    DLOG(11, "D-Log · D-Gamut"),
    LOGC3(12, "ARRI LogC3 (EI 800) · ARRI Wide Gamut 3"),
    LOGC4(13, "ARRI LogC4 · ARRI Wide Gamut 4")
}

enum class LutRange { FULL, VIDEO }

/** Retain unrecognized wire names so the library can show an unavailable row without substituting a display encoding. */
data class LutUsage(val inputName: String, val outputName: String, val inputRange: LutRange, val outputRange: LutRange) {
    constructor(input: LutEncoding = LutEncoding.DIRECT, output: LutEncoding = LutEncoding.DIRECT,
        inputRange: LutRange = LutRange.FULL, outputRange: LutRange = LutRange.FULL) : this(input.name, output.name, inputRange, outputRange)

    val input = LutEncoding.entries.find { it.name == inputName }
    val output = LutEncoding.entries.find { it.name == outputName }
    val unavailableReason = when {
        input == null -> "不支持的 LUT 输入编码：$inputName"
        output == null -> "不支持的 LUT 输出编码：$outputName"
        else -> null
    }
    val key get() = "$inputName:$outputName:${inputRange.name}:${outputRange.name}"
    val label get() = unavailableReason ?: if (input == output) input?.label.orEmpty() else "${input?.label} → ${output?.label}"

    fun validateForPhoto() {
        require(unavailableReason == null) { unavailableReason.orEmpty() }
        require(this in photoChoices || (input?.isLog == true && output in logOutputs && outputRange == LutRange.FULL)) {
            "不支持此 LUT 的输入或输出编码"
        }
    }

    companion object {
        val direct = LutUsage()
        val rec709 = LutUsage(LutEncoding.REC709_G24, LutEncoding.REC709_G24)
        val nlog = LutUsage(LutEncoding.NLOG, LutEncoding.REC709_G24)
        val photoChoices = listOf(direct, rec709)
        val logOutputs = listOf(LutEncoding.DIRECT, LutEncoding.REC709_G24)
        fun forInput(input: LutEncoding) = if (input.isLog) LutUsage(input, LutEncoding.REC709_G24) else LutUsage(input, input)
    }

    /** Keep explicit output declarations when changing between scene input encodings. Unknown declarations need a fresh choice. */
    fun withInput(encoding: LutEncoding): LutUsage {
        val next = forInput(encoding)
        return if (encoding.isLog && input?.isLog == true && unavailableReason == null) {
            next.copy(inputRange = inputRange, outputName = outputName, outputRange = outputRange)
        } else next
    }
}

/** A plain LUT carries no reliable color-space declaration. The user chooses its usage independently of its filename. */
data class LutImportChoice(val uri: String, val name: String, val usage: LutUsage = LutUsage.direct)
