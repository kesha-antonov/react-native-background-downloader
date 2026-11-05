package com.eko

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

/** The upload response goes to JS as one string, so it is read up to a cap. */
class UploaderResponseTest {

    @Test
    fun `stops reading at the cap`() {
        assertEquals("abcd", readCapped(StringReader("abcdefgh"), 4))
    }

    @Test
    fun `keeps a body under the cap whole, line breaks included`() {
        assertEquals("{\n  \"ok\": true\n}\n", readCapped(StringReader("{\n  \"ok\": true\n}\n"), 1024))
    }

    @Test
    fun `reads past the internal buffer size`() {
        val body = "x".repeat(20_000)
        assertEquals(body, readCapped(StringReader(body), 1_000_000))
        assertEquals(10_000, readCapped(StringReader(body), 10_000).length)
    }
}
