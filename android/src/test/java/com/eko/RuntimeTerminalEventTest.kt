package com.eko

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.WritableMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeTerminalEventTest {

  private lateinit var module: RNBackgroundDownloaderModuleImpl
  private var bindingToken: Long? = null

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    module = RNBackgroundDownloaderModuleImpl.getInstance(context)
    pendingEvents().drain()
  }

  @After
  fun tearDown() {
    bindingToken?.let(module::invalidate)
    pendingEvents().drain()
  }

  @Test
  fun `live terminal event remains buffered until the replacement runtime acknowledges it`() {
    val delivered = CountDownLatch(1)
    bindingToken = module.initialize { name, _ ->
      if (name == "downloadComplete") delivered.countDown()
    }
    readyFamilies().add("download")
    val payload = JavaOnlyMap().apply {
      putString("id", "runtime-handoff")
      putString("location", "/tmp/download")
      putDouble("bytesDownloaded", 7.0)
      putDouble("bytesTotal", 7.0)
      putString("metadata", "{\"assetId\":\"asset-123\"}")
    }

    dispatchTaskEvent("downloadComplete", payload)

    assertTrue(delivered.await(1, TimeUnit.SECONDS))
    val event = pendingEvents().snapshot().single()
    assertEquals("downloadComplete", event.name)
    assertEquals("download:runtime-handoff", event.key)
    assertEquals(
      "{\"assetId\":\"asset-123\"}",
      (event.payload as WritableMap).getString("metadata")
    )

    module.invalidate(bindingToken!!)
    bindingToken = module.initialize { _, _ -> }
    module.acknowledgeRuntimeEvents(
      bindingToken!!,
      JavaOnlyArray().apply { pushString("download:runtime-handoff") }
    )

    assertTrue(pendingEvents().snapshot().isEmpty())
  }

  @Test
  fun `reconciliation gates live delivery until buffered events are collected`() {
    var deliveries = 0
    bindingToken = module.initialize { _, _ -> deliveries++ }
    readyFamilies().add("download")

    module.prepareRuntimeReconciliation(bindingToken!!, "download")
    dispatchTaskEvent("downloadComplete", JavaOnlyMap().apply {
      putString("id", "reconciled-terminal")
      putString("location", "/tmp/download")
      putDouble("bytesDownloaded", 7.0)
      putDouble("bytesTotal", 7.0)
      putString("metadata", "{}")
    })

    assertEquals(0, deliveries)
    assertTrue(!readyFamilies().contains("download"))
    assertEquals("download:reconciled-terminal", pendingEvents().snapshot().single().key)
  }

  private fun dispatchTaskEvent(name: String, payload: WritableMap) {
    RNBackgroundDownloaderModuleImpl::class.java
      .getDeclaredMethod("dispatchTaskEvent", String::class.java, WritableMap::class.java)
      .apply { isAccessible = true }
      .invoke(module, name, payload)
  }

  @Suppress("UNCHECKED_CAST")
  private fun pendingEvents(): RuntimeEventBuffer<Any?> =
    RNBackgroundDownloaderModuleImpl::class.java
      .getDeclaredField("pendingEvents")
      .apply { isAccessible = true }
      .get(module) as RuntimeEventBuffer<Any?>

  @Suppress("UNCHECKED_CAST")
  private fun readyFamilies(): MutableSet<String> =
    RNBackgroundDownloaderModuleImpl::class.java
      .getDeclaredField("readyFamilies")
      .apply { isAccessible = true }
      .get(module) as MutableSet<String>
}
