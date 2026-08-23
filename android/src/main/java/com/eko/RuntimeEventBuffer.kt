package com.eko

internal class RuntimeEventBuffer<T>(private val capacity: Int = 256) {
  data class Event<T>(val name: String, val key: String, val payload: T)

  private val events = LinkedHashMap<String, Event<T>>()

  @Synchronized
  fun put(name: String, key: String, payload: T) {
    events.remove(key)
    events[key] = Event(name, key, payload)
    while (events.size > capacity) {
      events.remove(events.keys.first())
    }
  }

  @Synchronized
  fun remove(key: String) {
    events.remove(key)
  }

  @Synchronized
  fun snapshot(): List<Event<T>> = events.values.toList()

  @Synchronized
  fun drain(): List<Event<T>> = events.values.toList().also { events.clear() }
}
