package org.openbridge.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LinearRawPreviewTest {
    @Test fun oddSizedThumbnailIncludesTheEntireLinearFootprint() {
        val frame = LinearRawPreview(5, 3)
        val profile = RawToneProfile(List(257) { it / 256f }, RawToneOrigin.CAMERA_PREVIEW)
        frame.toneProfile = profile
        frame.sourceWidth = 6000; frame.sourceHeight = 4000
        val input = frame.pixels.asFloatBuffer()
        for (v in 0..14) input.put(v.toFloat()).put(-v.toFloat()).put(1.5f)
        val thumb = frame.thumbnail(2)
        assertEquals(2, thumb.width)
        assertEquals(1, thumb.height)
        assertSame(profile, thumb.toneProfile)
        assertEquals(6000, thumb.sourceWidth)
        val rgb = thumb.pixels.asFloatBuffer()
        for (mean in listOf(5.5f, 8f)) {
            assertEquals(mean, rgb.get(), 0f)
            assertEquals(-mean, rgb.get(), 0f)
            assertEquals(1.5f, rgb.get(), 0f)
        }
    }
}
