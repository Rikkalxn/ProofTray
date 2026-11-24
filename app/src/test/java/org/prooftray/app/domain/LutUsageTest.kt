package org.prooftray.app.domain

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.prooftray.app.color.LookCodec

class LutUsageTest {
    @Test fun serializedLooksKeepTheirUsage() {
        val look = ColorLookSettings("709", "a".repeat(64), .5f, exposure = .5f, usage = LutUsage.rec709)
        val json = JSONObject(LookCodec.encode(look).toString())
        assertEquals(look, LookCodec.decode(json))
        json.remove("lutUsage")
        assertTrue(runCatching { LookCodec.decode(json) }.isFailure)
    }

    @Test fun sameTableCanHaveDistinctUsages() {
        val look = ColorLookSettings(lutId = "f".repeat(64))
        assertEquals(2, LutUsage.photoChoices.map { look.copy(usage = it).lutKey }.distinct().size)
        assertTrue(runCatching { LutUsage(LutEncoding.DIRECT, LutEncoding.REC709_G24).validateForPhoto() }.isFailure)
        LutUsage.nlog.validateForPhoto()
    }

    @Test fun sharedPresetRoundTripsEveryPhotoUsage() {
        for (usage in LutUsage.photoChoices) {
            val look = ColorLookSettings("sample", "b".repeat(64), exposure = .5f, usage = usage)
            val preset = JSONObject(LookCodec.presetEnvelope("saved", look).toString())
            assertEquals(LookCodec.PRESET_VERSION, preset.getInt("version"))
            assertEquals(look, LookCodec.decode(preset.getJSONObject("look")))
        }
    }

    @Test fun rawOnlyInputsAreRejectedOnRgbAndMixedSelections() {
        val raw = PhotoAsset("raw", "sample:raw", "sample.NEF", MediaFormat.RAW)
        val jpeg = raw.copy(id = "jpeg", format = MediaFormat.JPEG)
        for (encoding in LutEncoding.entries.filter { it.isLog }) for (range in LutRange.entries) {
            val usage = LutUsage.forInput(encoding).copy(inputRange = range)
            val log = ColorLookSettings(lutId = "a".repeat(64), usage = usage, logGainEv = 1f)
            assertEquals("Log 输入需要 RAW 原片", runCatching { log.validateForExport(listOf(raw, jpeg)) }.exceptionOrNull()?.message)
            assertThrows(IllegalArgumentException::class.java) { log.validateForExport(listOf(jpeg)) }
            log.validateForExport(listOf(raw))
            assertEquals(log, LookCodec.decode(LookCodec.encode(log)))
            assertFalse(log.copy(strength = 0f).isIdentity)
            assertThrows(IllegalArgumentException::class.java) { log.copy(logGainEv = Float.NaN).validate() }
            assertThrows(IllegalArgumentException::class.java) { usage.copy(outputRange = LutRange.VIDEO).validateForPhoto() }
        }
    }

    @Test fun switchingAwayFromLogClearsItsGainBeforeSaving() {
        val log = ColorLookSettings("test", "a".repeat(64), usage = LutUsage.nlog, logGainEv = 1.5f)
        for (usage in LutUsage.photoChoices) {
            val photo = log.withUsage(usage)
            assertEquals(0f, photo.logGainEv, 0f)
            assertEquals(photo, LookCodec.decode(LookCodec.encode(photo)))
        }
        assertEquals(1.5f, log.withUsage(LutUsage.forInput(LutEncoding.LOGC4)).logGainEv, 0f)
    }

    @Test fun formerGainKeyIsReadableAndNewKeyWins() {
        val look = ColorLookSettings(lutId = "a".repeat(64), usage = LutUsage.nlog, logGainEv = 1.25f)
        val json = LookCodec.encode(look)
        assertFalse(json.has("nlogGainEv"))
        json.put("nlogGainEv", -2.0)
        assertEquals(look, LookCodec.decode(json))
        json.remove("logGainEv")
        assertEquals(-2f, LookCodec.decode(json).logGainEv, 0f)
    }

    @Test fun unknownWireNamesRemainUnavailableAndRoundTrip() {
        val look = ColorLookSettings(lutId = "a".repeat(64), usage = LutUsage.nlog)
        for (field in listOf("input", "output")) {
            val json = LookCodec.encode(look)
            json.getJSONObject("lutUsage").put(field, "FUTURE_CURVE")
            val decoded = LookCodec.decode(json)
            assertTrue(decoded.usage.unavailableReason.orEmpty().contains("FUTURE_CURVE"))
            assertFalse(decoded.isIdentity)
            assertThrows(IllegalArgumentException::class.java) { decoded.validate() }
            assertEquals("FUTURE_CURVE", LookCodec.encode(decoded).getJSONObject("lutUsage").getString(field))
        }
    }

    @Test fun linearWarningRequiresAnImplicitUnitDomain() {
        val table = LutAsset("a".repeat(64), "Linear", 3, 33, "CUBE", LutUsage.forInput(LutEncoding.LINEAR_709), true)
        assertEquals(LINEAR_DOMAIN_WARNING, table.domainWarning())
        assertNull(table.copy(implicitUnitDomain = false).domainWarning())
        assertNull(table.copy(implicitUnitDomain = null).domainWarning())
        assertNull(table.domainWarning(LutUsage.rec709))
    }

    @Test fun nativeCodesRemainStable() {
        assertEquals(listOf(0, 2, 4, 14, 5, 6, 3, 7, 8, 9, 10, 11, 12, 13), LutEncoding.entries.map { it.nativeCode })
    }

    @Test fun sceneOutputsRoundTripWithoutChangingOldDeclarations() {
        for (input in LutEncoding.entries.filter { it.isLog }) for (output in LutUsage.logOutputs) {
            val usage = LutUsage(input, output)
            usage.validateForPhoto()
            val look = ColorLookSettings("output", "a".repeat(64), usage = usage)
            assertEquals(look, LookCodec.decode(LookCodec.encode(look)))
            assertEquals(LutEncoding.REC709_G24, LutUsage.forInput(input).output)
        }
        assertEquals(LutEncoding.REC709_G24, LookCodec.decodeUsage(LookCodec.encodeUsage(LutUsage.nlog)).output)
    }

    @Test fun changingLogInputKeepsExplicitOutputAndRange() {
        val usage = LutUsage(LutEncoding.SLOG2_SGAMUT, LutEncoding.DIRECT, LutRange.VIDEO)
        val changed = usage.withInput(LutEncoding.NLOG)
        assertEquals(LutEncoding.DIRECT, changed.output)
        assertEquals(LutRange.VIDEO, changed.inputRange)
        assertEquals(LutUsage.direct, changed.withInput(LutEncoding.DIRECT))
        assertEquals(LutUsage.nlog, usage.copy(outputName = "FUTURE").withInput(LutEncoding.NLOG))
    }

    @Test fun sceneOutputsRejectUnsupportedCurvesAndRanges() {
        for (output in LutEncoding.entries.filter { it !in LutUsage.logOutputs }) {
            assertThrows(IllegalArgumentException::class.java) { LutUsage(LutEncoding.NLOG, output).validateForPhoto() }
        }
        for (output in LutUsage.logOutputs) {
            assertThrows(IllegalArgumentException::class.java) {
                LutUsage(LutEncoding.NLOG, output, outputRange = LutRange.VIDEO).validateForPhoto()
            }
        }
    }
}
