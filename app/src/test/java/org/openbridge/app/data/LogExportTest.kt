package org.openbridge.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.ExportDestination
import org.openbridge.app.domain.ExportEffects
import org.openbridge.app.domain.ExportFormat
import org.openbridge.app.domain.ExportOptions
import org.openbridge.app.domain.ExportPreferences
import org.openbridge.app.domain.ExportTask
import org.openbridge.app.domain.LogExportSettings
import org.openbridge.app.domain.LutEncoding
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.validateLogExport

class LogExportTest {
    private val raw = PhotoAsset("raw", "content://photos/1", "photo.nef", MediaFormat.RAW)
    private val options = ExportOptions(ExportFormat.TIFF, maxEdge = 0, destination = ExportDestination.DIRECTORY,
        log = LogExportSettings(LutEncoding.NLOG.name, exposure = 1f, warmth = -.2f, gainEv = .5f))

    @Test fun logExportsDoNotNeedALutAndSurviveQueueRestoration() {
        options.validateLogExport(listOf(raw))
        val task = ExportTask("export", raw, options, "content://documents/tree/output")
        val restored = RecordCodec.decodeTask(RecordCodec.encodeTask(task))
        assertEquals(task, restored)
        assertNull(restored.options.look)
        assertFalse(restored.requiresLut("a".repeat(64)))
        restored.options.validateLogExport(listOf(raw))
    }

    @Test fun logOutputRejectsIncompatibleInputsAndEffects() {
        val jpeg = raw.copy(format = MediaFormat.JPEG)
        val invalid = listOf(options.copy(format = ExportFormat.PNG), options.copy(log = null),
            options.copy(destination = ExportDestination.GALLERY), options.copy(look = ColorLookSettings(exposure = 1f)))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { it.validateLogExport(listOf(raw)) } }
        assertThrows(IllegalArgumentException::class.java) { options.validateLogExport(listOf(raw, jpeg)) }
        assertThrows(IllegalArgumentException::class.java) { options.validateLogExport(emptyList()) }
        assertEquals(ExportEffects(false, false), ExportEffects(true, true).forFormat(ExportFormat.TIFF))
    }

    @Test fun unknownOrBrokenLogTasksFailWithoutChangingEncodingAndCanStillBeSaved() {
        val task = ExportTask("export", raw, options, "content://documents/tree/output")
        for (broken in listOf("encoding", "version", "gainEv")) {
            val json = RecordCodec.encodeTask(task)
            val log = json.getJSONObject("options").getJSONObject("log")
            when (broken) {
                "encoding" -> log.put("encoding", "FUTURE_LOG")
                "version" -> log.put("version", 999)
                else -> log.remove("gainEv")
            }
            val restored = RecordCodec.decodeTask(json)
            assertThrows(IllegalArgumentException::class.java) { restored.options.validateLogExport(listOf(raw)) }
            val savedAgain = RecordCodec.decodeTask(RecordCodec.encodeTask(restored))
            assertThrows(IllegalArgumentException::class.java) { savedAgain.options.validateLogExport(listOf(raw)) }
        }
    }

    @Test fun oldImageTasksHaveNoLogIntent() {
        val task = ExportTask("old", raw, ExportOptions(), "")
        val json = RecordCodec.encodeTask(task)
        json.getJSONObject("options").remove("log")
        val restored = RecordCodec.decodeTask(json)
        assertEquals(task, restored)
        assertNull(restored.options.log)
        restored.options.validateLogExport(listOf(raw))
    }

    @Test fun rememberEncodingWithoutPersistingSceneAdjustments() {
        val preferences = ExportPreferences.fromOptions(options)
        assertEquals(preferences, ExportPreferences.fromPreferences(preferences.toPreferences()))
        assertEquals(LutEncoding.NLOG.name, preferences.logEncoding)
        assertFalse(preferences.toPreferences().keys.any { it.contains("gain", true) || it.contains("exposure", true) })
        assertEquals(LutEncoding.NLOG.name, ExportPreferences.fromOptions(ExportOptions(), preferences).logEncoding)
    }

    @Test fun onlyLogEncodingsAndFiniteAdjustmentsAreAccepted() {
        for (encoding in LogExportSettings.encodings) LogExportSettings(encoding.name).validate()
        val invalid = listOf(LogExportSettings(LutEncoding.LINEAR_709.name), LogExportSettings(LutEncoding.DIRECT.name),
            LogExportSettings(gainEv = Float.NaN), LogExportSettings(exposure = 4f), LogExportSettings(warmth = Float.POSITIVE_INFINITY))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { it.validate() } }
    }
}
