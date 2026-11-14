package com.lumen.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {

    @Test
    fun `code has nine digits`() {
        repeat(50) {
            val code = Ids.newCode()
            assertEquals(9, code.length)
            assertTrue(code.all { it.isDigit() })
        }
    }

    @Test
    fun `pin has six digits`() {
        repeat(50) {
            val pin = Ids.newPin()
            assertEquals(Ids.PIN_LENGTH, pin.length)
            assertEquals(6, pin.length)
            assertTrue(pin.toInt() in 100_000..999_999)
        }
    }

    @Test
    fun `isPin accepts legacy and new length`() {
        assertTrue(Ids.isPin("1234"))
        assertTrue(Ids.isPin("123456"))
        assertFalse(Ids.isPin("123"))
        assertFalse(Ids.isPin("1234567"))
        assertFalse(Ids.isPin("12a456"))
    }

    @Test
    fun `formatCode groups digits`() {
        assertEquals("284 913 706", Ids.formatCode("284913706"))
    }

    @Test
    fun `formatCode keeps unknown format`() {
        assertEquals("abc", Ids.formatCode("abc"))
    }

    @Test
    fun `detects lan addresses`() {
        assertTrue(Ids.isLanAddress("192.168.1.24"))
        assertTrue(Ids.isLanAddress("10.0.0.5"))
        assertFalse(Ids.isLanAddress("284913706"))
    }
}
