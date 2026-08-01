package com.eko.uidt

import android.app.job.JobInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.eko.UIDTDownloadJobService

/**
 * Assigns the JobScheduler job IDs used for UIDT downloads.
 *
 * Job IDs used to be derived as `JOB_ID_BASE + hash(configId) % JOB_ID_RANGE`.
 * Two config IDs landing on the same ID is not a rare edge case - with the 150
 * concurrent downloads the app-wide job quota allows, the birthday bound puts a
 * collision at roughly two chances in three - and `JobScheduler.schedule()` on
 * an ID that already exists *replaces* that job, silently killing the download
 * it belonged to.
 *
 * There is no mapping to maintain, though: a scheduled job already carries its
 * download's config ID in its extras, so the JobScheduler itself is the registry.
 * That also makes every lookup correct across process death by construction. The
 * only state kept here is an in-memory reservation covering the window between
 * picking a free ID and `schedule()` accepting it.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal object UIDTJobIds {

    /** Size of the job-ID range owned by the library: `[JOB_ID_BASE, JOB_ID_BASE + JOB_ID_RANGE)`. */
    private const val JOB_ID_RANGE = 10000

    private val lock = Any()

    /** configId -> ID picked but not yet handed to the JobScheduler. */
    private val reservations = mutableMapOf<String, Int>()

    private val serviceName = UIDTDownloadJobService::class.java.name

    /**
     * The ID a download would have had before IDs were assigned. Still the
     * starting point of the allocation scan, so IDs stay spread over the range
     * instead of clustering at its start, and the fallback for a cancel when the
     * pending jobs can't be read.
     */
    fun legacyJobIdFor(configId: String): Int =
        UIDTConstants.JOB_ID_BASE + (configId.hashCode() and 0x7FFFFFFF) % JOB_ID_RANGE

    /** The download a scheduled job belongs to, or null if it isn't one of ours. */
    fun configIdOf(job: JobInfo): String? {
        if (job.service.className != serviceName) return null
        return job.extras.getString(UIDTConstants.KEY_DOWNLOAD_ID)
    }

    /** Every download job the app currently has scheduled, running ones included. */
    fun ourJobs(pendingJobs: List<JobInfo>?): List<JobInfo> =
        pendingJobs.orEmpty().filter { configIdOf(it) != null }

    /**
     * The job ID a download currently holds, or null when it has none - i.e.
     * there is no job to cancel, pause or look up.
     *
     * @param pendingJobs snapshot of the app's pending jobs, or null when it
     *        could not be read.
     */
    fun jobIdFor(configId: String, pendingJobs: List<JobInfo>?): Int? {
        pendingJobs?.firstOrNull { configIdOf(it) == configId }?.let { return it.id }
        return synchronized(lock) { reservations[configId] }
    }

    /**
     * The ID to cancel for a download. With the pending jobs unreadable we fall
     * back to the legacy ID: cancelling an ID that holds no job is a no-op, and
     * for a download that never collided it is the right one.
     */
    fun jobIdToCancel(configId: String, pendingJobs: List<JobInfo>?): Int =
        jobIdFor(configId, pendingJobs) ?: legacyJobIdFor(configId)

    /**
     * Reserve the ID to schedule a download under, or null when every ID in the
     * range is taken - which leaves the caller to fall back to the foreground
     * service rather than evict somebody else's job.
     *
     * A download that already holds a job keeps that ID, so re-scheduling
     * replaces its job instead of adding a second one for the same download.
     * Otherwise the first ID not held by a live job (ours or the host app's) or
     * by another reservation is taken. Release it with [endReservation] once
     * `schedule()` has returned, whatever the outcome.
     */
    fun reserveJobId(configId: String, pendingJobs: List<JobInfo>?): Int? {
        synchronized(lock) {
            jobIdFor(configId, pendingJobs)?.let {
                reservations[configId] = it
                return it
            }

            val used = pendingJobs.orEmpty().mapTo(mutableSetOf()) { it.id }
            used.addAll(reservations.values)

            val start = legacyJobIdFor(configId) - UIDTConstants.JOB_ID_BASE
            for (offset in 0 until JOB_ID_RANGE) {
                val candidate = UIDTConstants.JOB_ID_BASE + (start + offset) % JOB_ID_RANGE
                if (candidate in used) continue
                reservations[configId] = candidate
                return candidate
            }
            return null
        }
    }

    /** Drop a reservation: once `schedule()` has returned, the system knows about the job. */
    fun endReservation(configId: String) {
        synchronized(lock) { reservations.remove(configId) }
    }
}
