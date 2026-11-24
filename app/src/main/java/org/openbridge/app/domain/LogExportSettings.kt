package org.openbridge.app.domain

/** A scene export has no LUT dependency. Keep unknown wire names so restored tasks fail instead of changing color space. */
data class LogExportSettings(
    val encodingName: String = LutEncoding.SLOG3_SGAMUT3CINE.name,
    val exposure: Float = 0f, val warmth: Float = 0f, val gainEv: Float = 0f, val tint: Float = 0f
) {
    val encoding: LutEncoding? get() = encodings.find { it.name == encodingName }

    fun validate() {
        require(encoding != null) { "不支持的 Log 导出编码：$encodingName" }
        require(exposure.isFinite() && exposure in -3f..3f && warmth.isFinite() && warmth in -1f..1f &&
            gainEv.isFinite() && gainEv in -3f..3f && tint.isFinite() && tint in -1f..1f) { "Log 导出参数超出范围" }
    }

    companion object {
        // Linear unsigned TIFF would clip scene values above one; it is not offered as a Log intermediate.
        val encodings = LutEncoding.entries.filter { it.isLog && it != LutEncoding.LINEAR_709 }
    }
}

/** Called before queueing and again before writing, including restored tasks. */
fun ExportOptions.validateLogExport(assets: List<PhotoAsset>) {
    if (format != ExportFormat.TIFF && log == null) return
    require(format == ExportFormat.TIFF && log != null) { "Log 中间文件需要 16 位 TIFF 和有效的编码设置" }
    log.validate()
    require(assets.isNotEmpty() && assets.all { it.format == MediaFormat.RAW }) { "Log 直出需要 RAW 原片" }
    require(destination == ExportDestination.DIRECTORY) { "Log 中间文件请保存到文件夹" }
    require(look == null && frame == null) { "Log 中间文件不应用 LUT、显示调色或边框" }
}
