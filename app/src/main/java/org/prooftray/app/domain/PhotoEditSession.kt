package org.prooftray.app.domain

enum class PhotoEditTab(val label: String) { COLOR("调色"), FRAME("边框") }

data class PhotoEditSession(val look: ColorLookSettings?, val frame: FrameTemplate?)

/** Identity looks have no session effect. A null frame intentionally clears the previous frame. */
fun photoEditSession(look: ColorLookSettings, frame: FrameTemplate?) = PhotoEditSession(look.takeUnless { it.isIdentity }, frame)

data class ExportEffects(val color: Boolean, val frame: Boolean) {
    fun forFormat(format: ExportFormat): ExportEffects =
        if (format == ExportFormat.ORIGINAL || format == ExportFormat.TIFF) ExportEffects(false, false) else this
}

/** Unconfirmed output choices survive an editor detour, but never enter SharedPreferences. */
data class ExportDialogDraft(
    val preferences: ExportPreferences, val color: Boolean? = null, val frame: Boolean? = null, val logGainEv: Float? = null
)

fun defaultExportEffects(look: ColorLookSettings, frame: FrameTemplate?, assets: List<PhotoAsset>) = ExportEffects(
    color = !look.isIdentity && look.usage.unavailableReason == null && (!look.usesLog || assets.all { it.format == MediaFormat.RAW }),
    frame = frame != null
)

fun validateLutName(value: String): String = value.trim().also {
    require(it.isNotEmpty()) { "名称不能为空" }
    require(it.length <= 80) { "名称不能超过 80 字" }
}
