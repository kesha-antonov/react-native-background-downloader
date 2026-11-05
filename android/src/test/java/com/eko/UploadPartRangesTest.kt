package com.eko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadPartRangesTest {

    @Test
    fun `defaults to ceil of size over count, the last part taking what's left`() {
        assertEquals(listOf(0L until 4L, 4L until 8L, 8L until 10L), uploadPartRanges(10, 3, 0))
    }

    @Test
    fun `one part is the whole file`() {
        assertEquals(listOf(0L until 10L), uploadPartRanges(10, 1, 0))
    }

    @Test
    fun `an explicit size must cover the file exactly`() {
        assertEquals(listOf(0L until 5L, 5L until 7L), uploadPartRanges(7, 2, 5))
        // Too small: the last part would be bigger than the part size
        assertNull(uploadPartRanges(11, 2, 5))
        // Too big: the last part would be empty
        assertNull(uploadPartRanges(5, 2, 5))
    }

    @Test
    fun `no parts or an empty file`() {
        assertNull(uploadPartRanges(10, 0, 0))
        assertNull(uploadPartRanges(0, 2, 0))
    }
}
