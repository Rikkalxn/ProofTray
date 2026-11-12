package org.openbridge.app.data

import java.nio.FloatBuffer
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RawToneProfileTest {
    @Test fun quantileMatchingRecoversAKnownMonotoneCurve() {
        val source = IntArray(1024) { 20 }
        val target = IntArray(1024)
        for (i in source.indices) target[((i / 1023.0).pow(.8) * 1023).roundToInt()] += source[i]
        val profile = RawToneMatcher.fromHistograms(source, target)
        assertEquals(RawToneOrigin.CAMERA_PREVIEW, profile.origin)
        for (i in listOf(32, 64, 128, 192, 224)) assertEquals((i / 256.0).pow(.8).toFloat(), profile.samples[i], .02f)
        assertTrue(RawToneProfile.validSamples(profile.samples))
    }

    @Test fun histogramCountsDoNotDependOnPreviewOrExportSize() {
        val source = IntArray(1024) { if (it in 40..900) 5 else 0 }
        val target = IntArray(1024) { if (it in 55..980) 7 else 0 }
        assertEquals(RawToneMatcher.fromHistograms(source, target),
            RawToneMatcher.fromHistograms(source.map { it * 3 }.toIntArray(), target.map { it * 4 }.toIntArray()))
    }

    @Test fun tinyAndDifferentCropsUseTheGenericFallback() {
        assertEquals(RawToneFallback.SMALL_PREVIEW, RawToneMatcher.previewProblem(6000, 4000, 128, 96))
        assertEquals(RawToneFallback.ASPECT, RawToneMatcher.previewProblem(6000, 4000, 1920, 1080))
        assertNull(RawToneMatcher.previewProblem(4040, 6064, 4024, 6048))
    }

    @Test fun monochromePreviewsAreNotUsedToFitColourRaw() {
        val source = FloatBuffer.wrap(FloatArray(300) { (it / 3) / 200f })
        val target = IntArray(100) { val v = 20 + it * 2; (255 shl 24) or (v shl 16) or (v shl 8) or v }
        assertEquals(RawToneFallback.MONOCHROME, RawToneMatcher.fit(source, 10, 10, target, 10, 10).fallback)
    }

    @Test fun brokenOrEmptyDistributionsDoNotProduceAMatchedProfile() {
        assertEquals(RawToneOrigin.GENERIC, RawToneMatcher.fromHistograms(IntArray(1024), IntArray(1024)).origin)
        val oneLevel = IntArray(1024).apply { this[100] = 1000 }
        assertEquals(RawToneOrigin.GENERIC, RawToneMatcher.fromHistograms(oneLevel, oneLevel).origin)
        val source = FloatBuffer.wrap(floatArrayOf(Float.NaN, 0f, 0f))
        assertEquals(RawToneOrigin.GENERIC, RawToneMatcher.fit(source, 1, 1, intArrayOf(-1), 1, 1).origin)
    }

    @Test fun cancellationIsNotConvertedIntoACachedFallback() {
        assertThrows(CancellationException::class.java) {
            RawToneMatcher.fit(FloatBuffer.wrap(floatArrayOf(.1f, .2f, .3f)), 1, 1, intArrayOf(-1), 1, 1) {
                throw CancellationException("cancelled")
            }
        }
    }

    @Test fun cacheRecordsValidateTheirRevisionAndCurve() {
        val key = RawToneKey("photo", "content:photo", 123, "mtime:1")
        val curve = RawToneProfile(List(257) { it / 256f }, RawToneOrigin.CAMERA_PREVIEW)
        assertEquals(curve, RawToneCodec.decode(key, RawToneCodec.encode(key, curve)))
        assertNull(RawToneCodec.decode(key.copy(revision = "mtime:2"), RawToneCodec.encode(key, curve)))
        assertNull(RawToneCodec.decode(key.copy(version = 2), RawToneCodec.encode(key, curve)))
        assertNull(RawToneCodec.decode(key, "{}"))
        assertThrows(IllegalArgumentException::class.java) {
            RawToneProfile(curve.samples.toMutableList().apply { this[30] = -1f }, RawToneOrigin.CAMERA_PREVIEW)
        }
    }
}
