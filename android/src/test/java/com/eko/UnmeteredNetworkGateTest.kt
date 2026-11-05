package com.eko

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The unmetered gate binds transfers to a specific network. While a VPN is the
 * app's default network, binding to the WiFi underneath it fails with EPERM, so
 * the gate has to use the VPN itself (or wait when the VPN is metered).
 *
 * The tests drive the gate's network callbacks directly with the networks and
 * capabilities ConnectivityService would report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UnmeteredNetworkGateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val wifi = ShadowNetwork.newInstance(125)
    private val vpn = ShadowNetwork.newInstance(126)

    private lateinit var server: MockWebServer
    private val holdResponses = CountDownLatch(1)
    private lateinit var downloader: ResumableDownloader
    private lateinit var gate: UnmeteredNetworkGate
    private val gateStateChanges = AtomicInteger()

    private val listener = object : ResumableDownloader.DownloadListener {
        override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) = Unit
        override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
        override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
        override fun onError(id: String, error: String, errorCode: Int) = Unit
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Keep transfers in flight: only the gate's bookkeeping is under test
                holdResponses.await(20, TimeUnit.SECONDS)
                return MockResponse().setBody("payload")
            }
        }
        server.start()
        downloader = ResumableDownloader()
        gate = UnmeteredNetworkGate(context, downloader, object : UnmeteredNetworkGate.Host {
            override fun createGateListener(id: String) = listener
            override fun onGatedTransferStarting() = Unit
            override fun onGateStateChanged() {
                gateStateChanges.incrementAndGet()
            }
        })
        shadowOf(connectivityManager).setNetworkCapabilities(wifi, capabilities(unmetered = true))
    }

    @After
    fun tearDown() {
        gate.shutdown()
        downloader.getActiveDownloads().keys.forEach { downloader.cancel(it) }
        holdResponses.countDown()
        server.shutdown()
    }

    private fun capabilities(unmetered: Boolean, vpn: Boolean = false, downstreamKbps: Int = 0): NetworkCapabilities {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        val shadow = shadowOf(capabilities)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (unmetered) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadow.addTransportType(if (vpn) NetworkCapabilities.TRANSPORT_VPN else NetworkCapabilities.TRANSPORT_WIFI)
        // Stands in for the signal-strength updates that change capabilities constantly
        if (downstreamKbps > 0) shadow.setLinkDownstreamBandwidthKbps(downstreamKbps)
        return capabilities
    }

    private fun park(id: String) {
        val parked = gate.parkNewDownload(id) {
            downloader.prepareWaitingDownload(
                id,
                server.url("/$id").toString(),
                tempFolder.root.resolve(id).absolutePath,
                emptyMap()
            )
        }
        assertEquals(true, parked)
    }

    private val unmeteredCallback: ConnectivityManager.NetworkCallback
        get() = checkNotNull(gate.registeredCallbacks().first)

    private val defaultCallback: ConnectivityManager.NetworkCallback
        get() = checkNotNull(gate.registeredCallbacks().second)

    private fun boundNetwork(id: String): Network? = downloader.getState(id)?.network

    /** WiFi with the VPN on top, as delivered to an app the VPN applies to. */
    private fun connectWifiAndVpn(vpnUnmetered: Boolean) {
        // The default network callback is registered first, so its replay comes first.
        // Both networks satisfy the unmetered request (it allows VPNs) when the VPN is
        // unmetered; WiFi is reported before the VPN, which is what the gate used to bind to
        defaultCallback.onCapabilitiesChanged(vpn, capabilities(unmetered = vpnUnmetered, vpn = true))
        unmeteredCallback.onAvailable(wifi)
        if (vpnUnmetered) unmeteredCallback.onAvailable(vpn)
        gate.awaitCallbacks()
    }

    @Test
    fun `moves off the WiFi when the VPN is reported after it`() {
        park("a")
        // The other order: WiFi shows up before the default network callback
        // says the VPN is the default
        unmeteredCallback.onAvailable(wifi)
        unmeteredCallback.onAvailable(vpn)
        defaultCallback.onCapabilitiesChanged(vpn, capabilities(unmetered = true, vpn = true))
        gate.awaitCallbacks()

        assertEquals(vpn, boundNetwork("a"))
    }

    @Test
    fun `binds to an unmetered VPN instead of the WiFi underneath it`() {
        park("a")
        connectWifiAndVpn(vpnUnmetered = true)

        assertEquals(vpn, boundNetwork("a"))
        assertEquals(emptySet<String>(), gate.waitingIds())
    }

    @Test
    fun `waits while the default network is a metered VPN`() {
        park("a")
        connectWifiAndVpn(vpnUnmetered = false)

        assertNull(boundNetwork("a"))
        assertEquals(setOf("a"), gate.waitingIds())
    }

    @Test
    fun `parking with the networks already known binds to the VPN`() {
        park("first")
        connectWifiAndVpn(vpnUnmetered = true)

        park("second")
        gate.awaitCallbacks()

        assertEquals(vpn, boundNetwork("second"))
    }

    @Test
    fun `moves a running transfer onto a VPN that connects mid-download`() {
        park("a")
        unmeteredCallback.onAvailable(wifi)
        defaultCallback.onCapabilitiesChanged(wifi, capabilities(unmetered = true))
        gate.awaitCallbacks()
        assertEquals(wifi, boundNetwork("a"))

        unmeteredCallback.onAvailable(vpn)
        defaultCallback.onCapabilitiesChanged(vpn, capabilities(unmetered = true, vpn = true))
        gate.awaitCallbacks()

        assertEquals(vpn, boundNetwork("a"))
        assertEquals(emptySet<String>(), gate.waitingIds())
    }

    @Test
    fun `parks a running transfer when a metered VPN connects mid-download`() {
        park("a")
        unmeteredCallback.onAvailable(wifi)
        defaultCallback.onCapabilitiesChanged(wifi, capabilities(unmetered = true))
        gate.awaitCallbacks()
        assertEquals(wifi, boundNetwork("a"))

        defaultCallback.onCapabilitiesChanged(vpn, capabilities(unmetered = false, vpn = true))
        gate.awaitCallbacks()

        assertNull(boundNetwork("a"))
        assertEquals(setOf("a"), gate.waitingIds())
    }

    @Test
    fun `a capability update that changes nothing the gate uses doesn't refresh the notification`() {
        park("a")
        unmeteredCallback.onAvailable(wifi)
        defaultCallback.onCapabilitiesChanged(wifi, capabilities(unmetered = true))
        gate.awaitCallbacks()
        val before = gateStateChanges.get()

        repeat(5) { i ->
            defaultCallback.onCapabilitiesChanged(wifi, capabilities(unmetered = true, downstreamKbps = 1000 + i))
        }
        gate.awaitCallbacks()

        assertEquals(before, gateStateChanges.get())
        assertEquals(wifi, boundNetwork("a"))
    }

    @Test
    fun `callbacks delivered after unregistering don't leak into the next registration`() {
        park("a")
        val staleUnmetered = unmeteredCallback
        val staleDefault = defaultCallback
        // Clearing the only download unregisters the callbacks
        gate.clear("a")
        assertNull(gate.registeredCallbacks().first)

        // Events ConnectivityService had already queued for the old callbacks
        staleUnmetered.onAvailable(wifi)
        staleDefault.onCapabilitiesChanged(wifi, capabilities(unmetered = true))
        gate.awaitCallbacks()

        // A new download must wait for the new callbacks to report a network
        // instead of starting on the stale one
        park("b")
        gate.awaitCallbacks()

        assertNull(boundNetwork("b"))
        assertEquals(setOf("b"), gate.waitingIds())
    }
}
