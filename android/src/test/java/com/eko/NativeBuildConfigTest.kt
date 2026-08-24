package com.eko

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeBuildConfigTest {

    @Test
    fun `missing metadata uses defaults`() {
        assertEquals(NativeBuildConfig(4, false, 1000, 1048576), NativeBuildConfig.fromMetadata(null))
    }

    @Test
    fun `valid metadata is applied`() {
        val metadata = Bundle().apply {
            putInt(NativeBuildConfig.MAX_PARALLEL_DOWNLOADS_KEY, 8)
            putBoolean(NativeBuildConfig.ENABLE_LOGGING_KEY, true)
            putInt(NativeBuildConfig.PROGRESS_INTERVAL_KEY, 500)
            putInt(NativeBuildConfig.PROGRESS_MIN_BYTES_KEY, 0)
        }

        val config = NativeBuildConfig.fromMetadata(metadata)

        assertEquals(8, config.maxParallelDownloads)
        assertTrue(config.enableLogging)
        assertEquals(500, config.progressInterval)
        assertEquals(0, config.progressMinBytes)
    }

    @Test
    fun `invalid numeric metadata falls back safely`() {
        val metadata = Bundle().apply {
            putInt(NativeBuildConfig.MAX_PARALLEL_DOWNLOADS_KEY, 0)
            putBoolean(NativeBuildConfig.ENABLE_LOGGING_KEY, false)
            putInt(NativeBuildConfig.PROGRESS_INTERVAL_KEY, 249)
            putInt(NativeBuildConfig.PROGRESS_MIN_BYTES_KEY, -1)
        }

        val config = NativeBuildConfig.fromMetadata(metadata)

        assertEquals(4, config.maxParallelDownloads)
        assertFalse(config.enableLogging)
        assertEquals(1000, config.progressInterval)
        assertEquals(1048576, config.progressMinBytes)
    }
}
