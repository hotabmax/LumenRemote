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
    fun `pin has four digits`() {
        repeat(50) {
            val pin = Ids.newPin()
            assertEquals(4, pin.length)
            assertTrue(pin.toInt() in 1000..9999)
        }
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
