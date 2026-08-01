package com.eko.uidt

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scheduling contract of the UIDT path: every download gets its own job, a
 * batch big enough to exhaust the app's job quota degrades instead of crashing,
 * and a job that hasn't started yet is still visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UIDTJobManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val jobScheduler: JobScheduler
        get() = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    /** Two distinct strings with the same hash code. */
    private val colliding = Pair("Aa", "BB")

    private var foreignJobId = 500000

    @Before
    fun clearScheduler() {
        foreignJobId = 500000
        jobScheduler.cancelAll()
        UIDTJobRegistry.activeJobs.clear()
    }

    private fun schedule(configId: String, startByte: Long = 0, totalBytes: Long = -1): Boolean =
        UIDTJobManager.scheduleDownload(
            context = context,
            configId = configId,
            url = "https://example.com/$configId",
            destination = "/tmp/$configId",
            headers = mapOf("Authorization" to "Bearer $configId"),
            startByte = startByte,
            totalBytes = totalBytes,
            metadata = """{"groupId":"group-1"}""",
            isAllowedOverMetered = true
        )

    /** Fill the scheduler with jobs belonging to the rest of the app. */
    private fun fillSchedulerWith(count: Int) {
        repeat(count) {
            jobScheduler.schedule(
                JobInfo.Builder(foreignJobId++, ComponentName(context, "com.example.SomeOtherService"))
                    .setMinimumLatency(1000)
                    .build()
            )
        }
    }

    private fun scheduledConfigIds(): List<String> =
        jobScheduler.allPendingJobs.mapNotNull { UIDTJobIds.configIdOf(it) }

    @Test
    fun `scheduling a download creates a job carrying its config ID`() {
        assertTrue(schedule("a"))

        assertEquals(listOf("a"), scheduledConfigIds())
        val job = jobScheduler.allPendingJobs.single()
        assertEquals("https://example.com/a", job.extras.getString(UIDTConstants.KEY_URL))
        assertTrue(job.isUserInitiated)
    }

    @Test
    fun `two downloads whose IDs collide each get their own job`() {
        assertTrue(schedule(colliding.first))
        assertTrue(schedule(colliding.second))

        // The old hash-derived ID would have made the second replace the first
        assertEquals(2, jobScheduler.allPendingJobs.size)
        assertEquals(setOf(colliding.first, colliding.second), scheduledConfigIds().toSet())
        assertNotEquals(
            UIDTJobIds.jobIdFor(colliding.first, jobScheduler.allPendingJobs),
            UIDTJobIds.jobIdFor(colliding.second, jobScheduler.allPendingJobs)
        )
    }

    @Test
    fun `re-scheduling a download replaces its own job instead of adding one`() {
        assertTrue(schedule("a"))
        val firstJobId = UIDTJobIds.jobIdFor("a", jobScheduler.allPendingJobs)

        assertTrue(schedule("a", startByte = 4096))

        assertEquals(1, jobScheduler.allPendingJobs.size)
        assertEquals(firstJobId, UIDTJobIds.jobIdFor("a", jobScheduler.allPendingJobs))
        assertEquals(4096L, jobScheduler.allPendingJobs.single().extras.getLong(UIDTConstants.KEY_START_BYTE))
    }

    @Test
    fun `a batch of downloads gets a job each`() {
        val ids = (1..40).map { "download-$it" }
        ids.forEach { assertTrue(schedule(it)) }

        assertEquals(ids.toSet(), scheduledConfigIds().toSet())
    }

    @Test
    fun `scheduling is refused once the app is out of job slots`() {
        // The quota is app-wide, so somebody else's jobs can exhaust it
        fillSchedulerWith(130)

        assertFalse("the caller must fall back instead of crashing", schedule("a"))
        assertTrue("no job may be left behind", scheduledConfigIds().isEmpty())
    }

    @Test
    fun `headroom is kept for the rest of the app`() {
        fillSchedulerWith(100)

        // Well under the 150-job limit, but past the point where the library
        // stops claiming slots
        assertTrue(schedule("a"))
        fillSchedulerWith(25)
        assertFalse(schedule("b"))
    }

    @Test
    fun `a download that already has a job can still be rescheduled at the quota`() {
        assertTrue(schedule("a"))
        fillSchedulerWith(130)

        // Replacing its own job needs no free slot - this is how a resume works
        assertTrue(schedule("a", startByte = 128))
        assertEquals(128L, jobScheduler.allPendingJobs.single { UIDTJobIds.configIdOf(it) == "a" }
            .extras.getLong(UIDTConstants.KEY_START_BYTE))
    }

    @Test
    fun `a scheduled download is reported as pending with its byte counts`() {
        assertTrue(schedule("a", startByte = 512, totalBytes = 2048))

        val reported = UIDTJobManager.getScheduledJobs(context).single()
        assertEquals("a", reported.id)
        assertEquals(DownloadManager.STATUS_PENDING, reported.status)
        assertEquals(512L, reported.bytesDownloaded)
        assertEquals(2048L, reported.bytesTotal)
        assertEquals("https://example.com/a", reported.url)
        assertTrue(UIDTJobManager.isScheduledJob(context, "a"))
    }

    @Test
    fun `only the library's own jobs are reported`() {
        fillSchedulerWith(3)
        assertTrue(schedule("a"))

        assertEquals(listOf("a"), UIDTJobManager.getScheduledJobs(context).map { it.id })
        assertFalse(UIDTJobManager.isScheduledJob(context, "not-a-download"))
    }

    @Test
    fun `cancelling a download removes its job and leaves the others alone`() {
        assertTrue(schedule(colliding.first))
        assertTrue(schedule(colliding.second))

        UIDTJobManager.cancelJob(context, colliding.first)

        assertEquals(listOf(colliding.second), scheduledConfigIds())
        assertNull(UIDTJobIds.jobIdFor(colliding.first, jobScheduler.allPendingJobs))
    }

    @Test
    fun `pausing a scheduled download returns what it needs to resume`() {
        assertTrue(schedule("a", startByte = 1024, totalBytes = 8192))

        val paused = UIDTJobManager.cancelPendingJob(context, "a")

        assertEquals("https://example.com/a", paused?.url)
        assertEquals(1024L, paused?.startByte)
        assertEquals(8192L, paused?.totalBytes)
        assertEquals("Bearer a", paused?.headers?.get("Authorization"))
        assertTrue("the job must be gone", scheduledConfigIds().isEmpty())
    }
}
