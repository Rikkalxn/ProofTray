package org.openbridge.app.data

import org.junit.Assert.*
import org.junit.Test

class RawPreviewColorTest {
    @Test fun cameraAdobeRgbIsUsedOnlyForAnUntaggedPreview() {
        assertEquals(RawPreviewColor.ADOBE_RGB, rawPreviewColor(2, false, true, true))
        assertEquals(RawPreviewColor.DECODER, rawPreviewColor(2, true, true, true))
        assertEquals(RawPreviewColor.DECODER, rawPreviewColor(2, false, false, true))
        assertEquals(RawPreviewColor.DECODER, rawPreviewColor(1, false, true, true))
    }

    @Test fun unknownAndHdrPreviewsDoNotBecomeSrgbByAssumption() {
        assertEquals(RawPreviewColor.UNSUPPORTED, rawPreviewColor(0, false, true, true))
        assertEquals(RawPreviewColor.UNSUPPORTED, rawPreviewColor(1, true, false, false))
        assertEquals(RawPreviewColor.DECODER, rawPreviewColor(0, true, true, true))
    }

    @Test fun jpegHeaderCheckDistinguishesIccAbsentAndMalformed() {
        val soi = byteArrayOf(0xff.toByte(), 0xd8.toByte())
        val sos = byteArrayOf(0xff.toByte(), 0xda.toByte())
        assertEquals(false, jpegHasIccProfile((soi + sos).inputStream()))
        val profile = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 1)
        val app2 = byteArrayOf(0xff.toByte(), 0xe2.toByte(), 0, 16) + profile
        assertEquals(true, jpegHasIccProfile((soi + app2 + sos).inputStream()))
        assertNull(jpegHasIccProfile((soi + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 1)).inputStream()))
        assertNull(jpegHasIccProfile((soi + app2.copyOf(6)).inputStream()))
    }
}
