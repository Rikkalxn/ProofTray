package org.openbridge.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothClientNameTest {
    @Test fun controllerNameUsesAFixedZeroPaddedField() {
        val bytes = bluetoothClientName("Test Phone")
        assertEquals(32, bytes.size)
        assertEquals("Android_Test_Phone", bytes.toString(Charsets.UTF_8).substringBefore('\u0000'))
        assertEquals(0, bytes.last().toInt())
    }

    @Test fun longNameKeepsItsTerminator() {
        val bytes = bluetoothClientName("x".repeat(100))
        assertEquals(31, bytes.indexOf(0))
        assertEquals(32, bytes.size)
    }

    @Test fun unsupportedModelCharactersUseTheSamePrintableFormAsReferenceClients() {
        val bytes = bluetoothClientName("测试手机📷".repeat(10))
        val text = bytes.toString(Charsets.UTF_8).substringBefore('\u0000')
        assertFalse(text.contains('\ufffd'))
        assertTrue(text.all { it in '!'..'~' })
        assertEquals("Android_OpenBridge", text)
        assertEquals(0, bytes.last().toInt())
    }

    @Test fun blankOrControlOnlyNamesUseAVisibleFallback() {
        val text = bluetoothClientName("\u0000\n\r").toString(Charsets.UTF_8).substringBefore('\u0000')
        assertEquals("Android_OpenBridge", text)
    }
}
