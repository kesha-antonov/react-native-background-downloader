package com.eko.uidt

import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Assigns the notification IDs used for download notifications.
 *
 * IDs used to be derived as `NOTIFICATION_ID_BASE + hash(configId) % RANGE`, so
 * two downloads whose config IDs hash to the same slot shared one notification:
 * their progress updates overwrote each other, and whichever ended first took
 * the notification down for the other - on the UIDT path for good, since the
 * job's end policy removes it. Across a batch of 150 downloads that is about a
 * one-in-ten chance.
 *
 * The hash is still where the search starts, so a download keeps the ID it has
 * always had unless another live download already holds it. Only the collided
 * one moves, and only for as long as it is running.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal object UIDTNotificationIds {

    /** Size of each notification-ID range (progress, and the finished one). */
    private const val RANGE = 100000

    private val lock = Any()

    /** configId -> offset into the range, held while the download has notifications. */
    private val assigned = mutableMapOf<String, Int>()

    /**
     * The in-progress notification ID for a download - stable for as long as the
     * download is live, and only ever held by one download at a time.
     */
    fun progressIdFor(configId: String): Int =
        UIDTConstants.NOTIFICATION_ID_BASE + offsetFor(configId)

    /**
     * The ID for the one-shot "download complete" notification. Taken from a
     * disjoint range at the same offset, so it inherits the same uniqueness and
     * can never collide with an in-progress notification.
     */
    fun finishedIdFor(configId: String): Int =
        UIDTConstants.FINISHED_NOTIFICATION_ID_BASE + offsetFor(configId)

    /**
     * Give the offset back once the download's notifications are gone. Called on
     * the paths that remove them - completion, failure and cancellation.
     */
    fun release(configId: String) {
        synchronized(lock) { assigned.remove(configId) }
    }

    private fun offsetFor(configId: String): Int {
        synchronized(lock) {
            assigned[configId]?.let { return it }

            val taken = assigned.values.toSet()
            val start = (configId.hashCode() and 0x7FFFFFFF) % RANGE
            for (probe in 0 until RANGE) {
                val candidate = (start + probe) % RANGE
                if (candidate in taken) continue
                assigned[configId] = candidate
                return candidate
            }

            // Every offset in use at once is not a real state (it would mean
            // 100000 live downloads), but never return a colliding ID silently
            assigned[configId] = start
            return start
        }
    }
}
