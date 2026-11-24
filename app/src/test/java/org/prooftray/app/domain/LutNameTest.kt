package org.prooftray.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LutNameTest {
    @Test fun trimsBeforeValidation() {
        assertEquals("电影风格", validateLutName(" \t电影风格\n "))
        assertEquals("名".repeat(80), validateLutName("  " + "名".repeat(80) + "  "))
    }
    @Test fun rejectsBlankAndOverlongNames() {
        assertThrows(IllegalArgumentException::class.java) { validateLutName(" \n\t ") }
        assertThrows(IllegalArgumentException::class.java) { validateLutName("名".repeat(81)) }
    }
}
