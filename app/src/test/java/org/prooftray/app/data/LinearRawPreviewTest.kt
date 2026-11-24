package org.prooftray.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LinearRawPreviewTest {
    @Test fun oddSizedThumbnailIncludesTheEntireLinearFootprint() {
        val frame = LinearRawPreview(5, 3)
        frame.baselineExposureEv = .5f
        frame.sourceWidth = 6000; frame.sourceHeight = 4000
        val input = frame.pixels.asFloatBuffer()
        for (v in 0..14) input.put(v.toFloat()).put(-v.toFloat()).put(1.5f)
        val thumb = frame.thumbnail(2)
        assertEquals(2, thumb.width)
        assertEquals(1, thumb.height)
        assertEquals(.5f, thumb.baselineExposureEv, 0f)
        assertEquals(6000, thumb.sourceWidth)
        val rgb = thumb.pixels.asFloatBuffer()
        for (mean in listOf(5.5f, 8f)) {
            assertEquals(mean, rgb.get(), 0f)
            assertEquals(-mean, rgb.get(), 0f)
            assertEquals(1.5f, rgb.get(), 0f)
        }
    }
}
