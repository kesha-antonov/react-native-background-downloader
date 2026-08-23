package com.eko

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeEventBufferTest {
  @Test
  fun `events with the same task key are coalesced`() {
    val buffer = RuntimeEventBuffer<Int>()

    buffer.put("downloadProgress", "download-progress:a", 1)
    buffer.put("downloadProgress", "download-progress:a", 2)

    assertEquals(listOf(2), buffer.drain().map { it.payload })
  }

  @Test
  fun `oldest events are discarded at capacity`() {
    val buffer = RuntimeEventBuffer<Int>(2)

    buffer.put("downloadBegin", "download:a", 1)
    buffer.put("downloadBegin", "download:b", 2)
    buffer.put("downloadBegin", "download:c", 3)

    assertEquals(listOf(2, 3), buffer.drain().map { it.payload })
  }

  @Test
  fun `snapshot retains events until they are acknowledged`() {
    val buffer = RuntimeEventBuffer<Int>()

    buffer.put("downloadComplete", "download:a", 1)

    assertEquals(listOf(1), buffer.snapshot().map { it.payload })
    assertEquals(listOf(1), buffer.snapshot().map { it.payload })
    buffer.remove("download:a")
    assertEquals(emptyList<Int>(), buffer.snapshot().map { it.payload })
  }
}
