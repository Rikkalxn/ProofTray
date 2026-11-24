package org.prooftray.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.ExportDestination
import org.prooftray.app.domain.ExportFormat
import org.prooftray.app.domain.ExportOptions
import org.prooftray.app.domain.ExportPreferences

class ExportPreferencesTest {
    @Test fun missingPreferencesUseThePreviousUiDefaults() {
        assertEquals(ExportPreferences(ExportFormat.JPEG, 95, 4096, ExportDestination.GALLERY),
            ExportPreferences.fromPreferences(emptyMap<String, Any>()))
    }
    @Test fun preferencesRoundTripWithoutSessionEffects() {
        val options = ExportOptions(ExportFormat.PNG, 83, 2048, ColorLookSettings(exposure = 1f), ExportDestination.DIRECTORY)
        val saved = ExportPreferences.fromOptions(options)
        val values = saved.toPreferences()
        assertEquals(saved, ExportPreferences.fromPreferences(values))
        assertEquals(setOf("export.format", "export.jpegQuality", "export.maxEdge", "export.destination", "export.logEncoding"), values.keys)
        assertFalse(values.keys.any { it.contains("look") || it.contains("frame") })
    }
    @Test fun originalSizeAndInvalidStoredValuesAreHandled() {
        val originalSize = ExportPreferences(maxEdge = 0)
        assertEquals(originalSize, ExportPreferences.fromPreferences(originalSize.toPreferences()))
        assertEquals(ExportPreferences(), ExportPreferences.fromPreferences(mapOf(
            "export.format" to "UNKNOWN", "export.jpegQuality" to 101, "export.maxEdge" to -1, "export.destination" to "UNKNOWN")))
    }

    @Test fun originalCopyRemainsTheRememberedFormatForFolderExports() {
        val options = ExportOptions(ExportFormat.ORIGINAL, destination = ExportDestination.DIRECTORY)
        val saved = ExportPreferences.fromOptions(options)
        assertEquals(saved, ExportPreferences.fromPreferences(saved.toPreferences()))
        assertEquals(ExportFormat.ORIGINAL, ExportPreferences.fromPreferences(mapOf("export.format" to "ORIGINAL")).format)
    }
}
