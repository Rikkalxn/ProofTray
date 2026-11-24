package org.openbridge.app.domain

/** Only output choices are persisted. Looks, frames and their enable switches belong to the editing session. */
data class ExportPreferences(
    val format: ExportFormat = ExportFormat.JPEG, val jpegQuality: Int = 95, val maxEdge: Int = 4096,
    val destination: ExportDestination = ExportDestination.GALLERY, val logEncoding: String = LutEncoding.SLOG3_SGAMUT3CINE.name
) {
    fun toPreferences(): Map<String, Any> = mapOf(
        "export.format" to format.name, "export.jpegQuality" to jpegQuality, "export.maxEdge" to maxEdge,
        "export.destination" to destination.name, "export.logEncoding" to logEncoding
    )

    companion object {
        fun fromOptions(options: ExportOptions, previous: ExportPreferences = ExportPreferences()) =
            ExportPreferences(options.format, options.jpegQuality, options.maxEdge, options.destination,
                options.log?.encodingName ?: previous.logEncoding)

        fun fromPreferences(values: Map<String, *>): ExportPreferences {
            val defaults = ExportPreferences()
            val format = ExportFormat.entries.find { it.name == values["export.format"] } ?: defaults.format
            val quality = (values["export.jpegQuality"] as? Int)?.takeIf { it in 1..100 } ?: defaults.jpegQuality
            val edge = (values["export.maxEdge"] as? Int)?.takeIf { it in setOf(0, 2048, 4096) } ?: defaults.maxEdge
            val destination = ExportDestination.entries.find { it.name == values["export.destination"] } ?: defaults.destination
            val encoding = LogExportSettings.encodings.find { it.name == values["export.logEncoding"] }?.name ?: defaults.logEncoding
            return ExportPreferences(format, quality, edge, if (format == ExportFormat.TIFF) ExportDestination.DIRECTORY else destination, encoding)
        }
    }
}
