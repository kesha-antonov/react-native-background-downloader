package com.eko

import java.util.concurrent.atomic.AtomicLong

internal class RuntimeBinding<T> {
  data class Bound<T>(val token: Long, val value: T)

  private val sequence = AtomicLong(0)
  private val lock = Any()
  private var bound: Bound<T>? = null

  fun attach(value: T): Long = synchronized(lock) {
    val token = sequence.incrementAndGet()
    bound = Bound(token, value)
    token
  }

  fun detach(token: Long): Boolean = synchronized(lock) {
    if (bound?.token != token) return false
    bound = null
    true
  }

  fun current(): Bound<T>? = synchronized(lock) { bound }

  fun isCurrent(token: Long): Boolean = synchronized(lock) { bound?.token == token }
}
