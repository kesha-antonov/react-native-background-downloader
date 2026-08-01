package com.eko.uidt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Notification IDs used to be `BASE + hash(configId) % 100000`, so two downloads
 * whose config IDs hashed together shared one notification. These pin the
 * behaviour that replaced it.
 */
class UIDTNotificationIdsTest {

    /** Two distinct strings with the same hash code - the collision this fixes. */
    private val colliding = Pair("Aa", "BB")

    @Before
    fun releaseEverything() {
        for (id in listOf(colliding.first, colliding.second, "a", "b", "c")) {
            UIDTNotificationIds.release(id)
        }
    }

    @Test
    fun `the two colliding config IDs really do collide`() {
        assertEquals(colliding.first.hashCode(), colliding.second.hashCode())
    }

    @Test
    fun `colliding downloads get different notification IDs`() {
        val first = UIDTNotificationIds.progressIdFor(colliding.first)
        val second = UIDTNotificationIds.progressIdFor(colliding.second)

        assertNotEquals(first, second)
    }

    @Test
    fun `a download keeps the same ID while it is live`() {
        val first = UIDTNotificationIds.progressIdFor("a")

        assertEquals(first, UIDTNotificationIds.progressIdFor("a"))
        assertEquals(first, UIDTNotificationIds.progressIdFor("a"))
    }

    @Test
    fun `the first download of a colliding pair keeps the legacy ID`() {
        val legacy = UIDTConstants.NOTIFICATION_ID_BASE + (colliding.first.hashCode() and 0x7FFFFFFF) % 100000

        assertEquals(legacy, UIDTNotificationIds.progressIdFor(colliding.first))
        // Only the download that arrived second has to move
        assertNotEquals(legacy, UIDTNotificationIds.progressIdFor(colliding.second))
    }

    @Test
    fun `a released ID goes back to the pool`() {
        val legacy = UIDTNotificationIds.progressIdFor(colliding.first)
        val moved = UIDTNotificationIds.progressIdFor(colliding.second)
        assertNotEquals(legacy, moved)

        // Both downloads end, so both slots are free again
        UIDTNotificationIds.release(colliding.first)
        UIDTNotificationIds.release(colliding.second)

        // The one that had to move gets the slot its hash points at this time
        assertEquals(legacy, UIDTNotificationIds.progressIdFor(colliding.second))
    }

    @Test
    fun `the finished notification ID is in its own range at the same offset`() {
        val progress = UIDTNotificationIds.progressIdFor(colliding.second)
        val finished = UIDTNotificationIds.finishedIdFor(colliding.second)

        assertEquals(
            progress - UIDTConstants.NOTIFICATION_ID_BASE,
            finished - UIDTConstants.FINISHED_NOTIFICATION_ID_BASE
        )
        // The ranges are disjoint, so a completion notification can never land on
        // another download's in-progress one
        assertTrue(finished > UIDTConstants.NOTIFICATION_ID_BASE + 100000)
    }

    @Test
    fun `colliding downloads get different finished IDs too`() {
        assertNotEquals(
            UIDTNotificationIds.finishedIdFor(colliding.first),
            UIDTNotificationIds.finishedIdFor(colliding.second)
        )
    }

    @Test
    fun `IDs stay unique across many downloads`() {
        val ids = (1..500).map { UIDTNotificationIds.progressIdFor("download-$it") }

        assertEquals(500, ids.toSet().size)
        for (i in 1..500) UIDTNotificationIds.release("download-$i")
    }
}
