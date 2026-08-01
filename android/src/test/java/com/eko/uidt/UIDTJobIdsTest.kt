package com.eko.uidt

import android.app.job.JobInfo
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import com.eko.UIDTDownloadJobService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Job IDs used to be `JOB_ID_BASE + hash(configId) % 10000`, and scheduling an ID
 * that already exists replaces the job holding it - so two downloads hashing
 * together silently killed one of each other. These pin the lookup that replaced
 * it: a job is found by the config ID it already carries in its extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UIDTJobIdsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Two distinct strings with the same hash code - the collision this fixes. */
    private val colliding = Pair("Aa", "BB")

    @Before
    fun clearReservations() {
        for (id in listOf(colliding.first, colliding.second, "a", "b")) {
            UIDTJobIds.endReservation(id)
        }
    }

    private fun downloadJob(jobId: Int, configId: String): JobInfo =
        JobInfo.Builder(jobId, ComponentName(context, UIDTDownloadJobService::class.java))
            .setExtras(PersistableBundle().apply { putString(UIDTConstants.KEY_DOWNLOAD_ID, configId) })
            .setMinimumLatency(1000)
            .build()

    /** A job belonging to something else in the app - WorkManager, say. */
    private fun foreignJob(jobId: Int): JobInfo =
        JobInfo.Builder(jobId, ComponentName(context, "com.example.SomeOtherService"))
            .setMinimumLatency(1000)
            .build()

    @Test
    fun `a download's job is found by the config ID in its extras`() {
        val jobs = listOf(downloadJob(12345, "other"), downloadJob(23456, "a"))

        assertEquals(23456, UIDTJobIds.jobIdFor("a", jobs))
    }

    @Test
    fun `a download with no job has no ID`() {
        assertNull(UIDTJobIds.jobIdFor("a", listOf(downloadJob(12345, "other"))))
    }

    @Test
    fun `another component's job is never mistaken for a download`() {
        val jobs = listOf(foreignJob(10001))

        assertNull(UIDTJobIds.jobIdFor("a", jobs))
        assertNull(UIDTJobIds.configIdOf(jobs.first()))
    }

    @Test
    fun `colliding downloads reserve different job IDs`() {
        val first = UIDTJobIds.reserveJobId(colliding.first, emptyList())
        val second = UIDTJobIds.reserveJobId(colliding.second, emptyList())

        assertNotEquals(first, second)
    }

    @Test
    fun `a reservation is not handed out twice`() {
        val reserved = UIDTJobIds.reserveJobId(colliding.first, emptyList())

        // The colliding download must not be offered the same ID while the first
        // one is still on its way to the JobScheduler
        assertNotEquals(reserved, UIDTJobIds.reserveJobId(colliding.second, emptyList()))

        UIDTJobIds.endReservation(colliding.first)

        // Once the first is scheduled (or was rejected), the ID is free again
        UIDTJobIds.endReservation(colliding.second)
        assertEquals(reserved, UIDTJobIds.reserveJobId(colliding.second, emptyList()))
    }

    @Test
    fun `a download that already has a job keeps that job's ID`() {
        val jobs = listOf(downloadJob(19999, "a"))

        // Re-scheduling replaces the existing job instead of adding a second one
        assertEquals(19999, UIDTJobIds.reserveJobId("a", jobs))
    }

    @Test
    fun `an ID held by a live job is never reserved`() {
        val legacy = UIDTConstants.JOB_ID_BASE + (colliding.first.hashCode() and 0x7FFFFFFF) % 10000
        val jobs = listOf(downloadJob(legacy, "somebody-else"))

        assertNotEquals(legacy, UIDTJobIds.reserveJobId(colliding.first, jobs))
    }

    @Test
    fun `an ID held by another component's job is never reserved`() {
        val legacy = UIDTConstants.JOB_ID_BASE + ("a".hashCode() and 0x7FFFFFFF) % 10000
        val jobs = listOf(foreignJob(legacy))

        // The old hash scheme would have stomped the host app's job here
        assertNotEquals(legacy, UIDTJobIds.reserveJobId("a", jobs))
    }

    @Test
    fun `a download with no live job reserves its legacy ID`() {
        val legacy = UIDTConstants.JOB_ID_BASE + ("a".hashCode() and 0x7FFFFFFF) % 10000

        // Nothing else holds it, so the download keeps the ID it always had -
        // including a job scheduled by a version that derived IDs from the hash
        assertEquals(legacy, UIDTJobIds.reserveJobId("a", emptyList()))
    }

    @Test
    fun `cancelling falls back to the legacy ID when the pending jobs are unreadable`() {
        val legacy = UIDTConstants.JOB_ID_BASE + ("a".hashCode() and 0x7FFFFFFF) % 10000

        // Cancelling an ID that holds no job is a no-op, so the legacy guess is
        // strictly better than not cancelling at all
        assertEquals(legacy, UIDTJobIds.jobIdToCancel("a", null))
    }

    @Test
    fun `cancelling uses the live job's ID when it is known`() {
        val jobs = listOf(downloadJob(17777, "a"))

        assertEquals(17777, UIDTJobIds.jobIdToCancel("a", jobs))
    }

    @Test
    fun `ourJobs keeps only the library's download jobs`() {
        val jobs = listOf(downloadJob(11111, "a"), foreignJob(11112), downloadJob(11113, "b"))

        assertEquals(listOf(11111, 11113), UIDTJobIds.ourJobs(jobs).map { it.id })
    }

    @Test
    fun `IDs stay unique across a full batch of downloads`() {
        val ids = (1..150).map { UIDTJobIds.reserveJobId("download-$it", emptyList()) }

        assertEquals(150, ids.toSet().size)
        for (i in 1..150) UIDTJobIds.endReservation("download-$i")
    }
}
