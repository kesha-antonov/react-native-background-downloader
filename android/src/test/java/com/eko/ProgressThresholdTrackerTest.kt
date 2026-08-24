package com.eko

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressThresholdTrackerTest {

    @Test
    fun `reports when the byte threshold is reached`() {
        val tracker = ProgressThresholdTracker(minBytes = 10)
        tracker.initialize("task")

        assertFalse(tracker.shouldReport("task", 5, 1000))
        assertTrue(tracker.shouldReport("task", 10, 1000))
        assertFalse(tracker.shouldReport("task", 15, 1000))
        assertTrue(tracker.shouldReport("task", 20, 1000))
    }

    @Test
    fun `percentage threshold is shared when byte reporting is disabled`() {
        val tracker = ProgressThresholdTracker(minBytes = 0)
        tracker.initialize("task")

        assertFalse(tracker.shouldReport("task", 10, 1000))
        assertTrue(tracker.shouldReport("task", 11, 1000))
    }

    @Test
    fun `unknown totals report every update`() {
        val tracker = ProgressThresholdTracker(minBytes = 0)
        tracker.initialize("task")

        assertTrue(tracker.shouldReport("task", 1, -1))
        assertTrue(tracker.shouldReport("task", 2, -1))
    }
}
