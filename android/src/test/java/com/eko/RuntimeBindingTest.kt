package com.eko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBindingTest {
  @Test
  fun `new runtime replaces the old runtime`() {
    val binding = RuntimeBinding<String>()

    binding.attach("runtime-a")
    val tokenB = binding.attach("runtime-b")

    assertEquals("runtime-b", binding.current()?.value)
    assertTrue(binding.detach(tokenB))
    assertNull(binding.current())
  }

  @Test
  fun `late detach cannot detach the current runtime`() {
    val binding = RuntimeBinding<String>()

    val tokenA = binding.attach("runtime-a")
    binding.attach("runtime-b")

    assertFalse(binding.detach(tokenA))
    assertFalse(binding.isCurrent(tokenA))
    assertEquals("runtime-b", binding.current()?.value)
  }

  @Test
  fun `only the current runtime receives subsequent events`() {
    val binding = RuntimeBinding<(String) -> Unit>()
    val eventsA = mutableListOf<String>()
    val eventsB = mutableListOf<String>()

    val tokenA = binding.attach(eventsA::add)
    binding.attach(eventsB::add)
    binding.detach(tokenA)
    binding.current()?.value?.invoke("complete")

    assertTrue(eventsA.isEmpty())
    assertEquals(listOf("complete"), eventsB)
  }

  @Test
  fun `detached runtime cannot settle a pending operation`() {
    val binding = RuntimeBinding<String>()
    val token = binding.attach("runtime-a")

    binding.detach(token)

    assertFalse(binding.isCurrent(token))
  }
}
