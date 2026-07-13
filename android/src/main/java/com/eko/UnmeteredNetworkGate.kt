package com.eko

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.eko.utils.NetworkRequestUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Enforces isAllowedOverMetered=false for downloads that run outside a scheduler
 * (the foreground-service path on Android < 14, and the UIDT fallback).
 *
 * Unmetered-only downloads are parked in a waiting set and started when a
 * ConnectivityManager callback reports an unmetered network; the transfer is
 * bound to that specific network, auto-paused back into the waiting set when
 * the network is lost or becomes metered, and auto-resumed from its byte offset
 * when an unmetered network returns - DownloadManager's "queued for WiFi"
 * semantics.
 *
 * All state is guarded by a single lock. The lock serializes transitions
 * between "waiting" and "running" against user pause/cancel and network
 * callbacks, so a download can never be started and paused concurrently, and
 * the callback can never be unregistered while a transition is in flight.
 * Callback bodies hop to a dedicated single thread: network callbacks share
 * the process-wide ConnectivityThread, which must never be blocked by socket
 * teardown or notification IPC; a single thread also preserves the
 * onAvailable/onLost ordering.
 */
class UnmeteredNetworkGate(
  private val context: Context,
  private val resumableDownloader: ResumableDownloader,
  private val host: Host
) {

  companion object {
    private const val TAG = "UnmeteredNetworkGate"
  }

  /** The service-side pieces the gate needs to start a transfer. */
  interface Host {
    /**
     * Build an event listener for the download, or null when its job is gone
     * (cancelled concurrently) and the waiting entry should be dropped.
     */
    fun createGateListener(id: String): ResumableDownloader.DownloadListener?

    /** A gated transfer is about to start (e.g. acquire the wake lock). */
    fun onGatedTransferStarting()

    /** Gate state changed in a way the user-facing notification should reflect. */
    fun onGateStateChanged()
  }

  private val lock = Any()
  // Downloads waiting for an unmetered network before (re)starting
  private val waiting = mutableSetOf<String>()
  // Gated downloads currently transferring, keyed to the network they are bound to
  private val running = mutableMapOf<String, Network>()
  // Unmetered networks currently known to be available
  private val unmeteredNetworks = mutableSetOf<Network>()
  @Volatile
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private val executor: ExecutorService = Executors.newSingleThreadExecutor()

  private val connectivityManager by lazy {
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  }

  /**
   * Park a NEW unmetered-only download. `prepare` runs under the gate lock after
   * stale bookkeeping for the id is cleared and must register the download's
   * waiting state (see ResumableDownloader.prepareWaitingDownload). The download
   * starts immediately when an unmetered network is already connected.
   *
   * Returns false when the network callback can't be registered - the gate can't
   * work then and the caller must fail the download instead of stranding it.
   */
  fun parkNewDownload(id: String, prepare: () -> Unit): Boolean {
    synchronized(lock) {
      clearLocked(id)
      prepare()
      if (!ensureCallbackLocked()) {
        return false
      }
      waiting.add(id)
      // The onAvailable replay only fires when the callback is first
      // registered - drain explicitly for networks we already know about
      unmeteredNetworks.firstOrNull()?.let { startWaitingLocked(it) }
      return true
    }
  }

  /**
   * Park a paused unmetered-only download for resume. Returns false when the
   * download is not paused (already transferring - re-adding it would corrupt
   * the gate state) or the network callback can't be registered.
   */
  fun parkForResume(id: String): Boolean {
    synchronized(lock) {
      val state = resumableDownloader.getState(id) ?: return false
      if (!state.isPaused.get()) {
        RNBackgroundDownloaderModuleImpl.logW(TAG, "Download $id is not paused")
        return false
      }
      if (!ensureCallbackLocked()) {
        RNBackgroundDownloaderModuleImpl.logE(TAG, "Cannot register unmetered network callback for $id")
        return false
      }
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Resume of $id waits for an unmetered network")
      waiting.add(id)
      // See parkNewDownload: drain explicitly for already-known networks
      unmeteredNetworks.firstOrNull()?.let { startWaitingLocked(it) }
      return true
    }
  }

  /**
   * Remove the download from the gate and run `block` while still holding the
   * gate lock. Used for user pause/cancel so an in-flight onAvailable drain
   * can't restart the download between the bookkeeping removal and the
   * downloader call (which would silently undo the pause).
   */
  fun <T> withGateCleared(id: String, block: () -> T): T {
    synchronized(lock) {
      clearLocked(id)
      return block()
    }
  }

  /** Remove the download from the gate bookkeeping (terminal state or restart). */
  fun clear(id: String) {
    synchronized(lock) {
      clearLocked(id)
    }
  }

  /** IDs currently parked waiting for an unmetered network (for the notification). */
  fun waitingIds(): Set<String> {
    synchronized(lock) {
      return waiting.toSet()
    }
  }

  /**
   * If a gated download errored because its bound network died or became metered,
   * move it back into the waiting set (silently - no error is surfaced) and return
   * true. Returns false for genuine errors (HTTP failures, or IO errors while the
   * bound network is verifiably still unmetered) so they propagate normally.
   *
   * Runs on the download thread, which is about to exit - blocking it briefly is
   * fine. The grace re-check is needed because the socket abort from a network
   * loss/metering change usually lands before ConnectivityService updates the
   * network's capabilities and delivers onLost.
   */
  fun regateAfterNetworkLoss(id: String, errorCode: Int): Boolean {
    // Only IO/exception-type errors (-1) qualify; positive codes are HTTP errors
    if (errorCode != -1) return false
    val state = resumableDownloader.getState(id) ?: return false
    if (state.isAllowedOverMetered) return false

    val boundNetwork = synchronized(lock) {
      running[id]
        ?: // onLost already moved this download back to the waiting set between the
        // socket error and this classification - suppress the error, it's handled
        return waiting.contains(id)
    }

    var lostUnmetered = !isNetworkUnmetered(boundNetwork)
    if (!lostUnmetered) {
      // Capabilities still look fine - give the connectivity stack a moment to
      // catch up with reality, then trust both the capability query and the
      // callback-maintained set of unmetered networks. Runs unlocked on the
      // exiting download thread.
      try {
        Thread.sleep(DownloadConstants.UNMETERED_RECHECK_DELAY_MS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      lostUnmetered = !isNetworkUnmetered(boundNetwork) ||
        synchronized(lock) { !unmeteredNetworks.contains(boundNetwork) }
    }

    synchronized(lock) {
      // Re-validate under the lock: the grace period may have raced a cancel, a
      // user pause, or an onLost-driven re-gate that already restarted this
      // download on another network. Mutate only if we still own the binding.
      val currentState = resumableDownloader.getState(id)
        ?: return true // cancelled during the grace period - nothing to report
      if (currentState.isCancelled.get()) return true
      if (waiting.contains(id)) return true // already re-gated by onLost
      val currentNetwork = running[id]
        ?: return true // released by a concurrent pause/cancel - error is stale
      if (currentNetwork != boundNetwork) {
        // Restarted on another network during the grace period - the error from
        // the old connection is stale; the new transfer reports for itself
        return true
      }
      if (!lostUnmetered) {
        // The network is genuinely fine - this is a real error (server, disk...)
        return false
      }

      running.remove(id)
      currentState.network = null
      // Paused-like state so the gate can restart it via resume()
      currentState.isPaused.set(true)
      waiting.add(id)
      ensureCallbackLocked()
      // If another unmetered network is already up, restart right away
      unmeteredNetworks.firstOrNull()?.let { startWaitingLocked(it) }
      return true
    }
  }

  /** Tear the gate down (service destroy). */
  fun shutdown() {
    synchronized(lock) {
      waiting.clear()
      running.clear()
      maybeUnregisterCallbackLocked()
    }
    executor.shutdownNow()
  }

  /**
   * Register the shared callback for unmetered networks (idempotent). Caller must
   * hold the lock. Returns false when registration failed.
   *
   * registerNetworkCallback immediately replays onAvailable for networks that
   * already satisfy the request, which arms the initial drain.
   */
  private fun ensureCallbackLocked(): Boolean {
    if (networkCallback != null) return true

    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        runOnExecutor {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Unmetered network available: $network")
          synchronized(lock) {
            unmeteredNetworks.add(network)
            startWaitingLocked(network)
          }
          host.onGateStateChanged()
        }
      }

      override fun onLost(network: Network) {
        // Also delivered when a network stops satisfying the request
        // (e.g. WiFi becomes metered), not only on disconnect
        runOnExecutor {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Unmetered network lost: $network")
          synchronized(lock) {
            unmeteredNetworks.remove(network)
            pauseRunningOnLocked(network)
          }
          host.onGateStateChanged()
        }
      }
    }

    return try {
      connectivityManager.registerNetworkCallback(NetworkRequestUtils.internetRequest(requireUnmetered = true), callback)
      networkCallback = callback
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Registered unmetered network callback")
      true
    } catch (e: Exception) {
      RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to register network callback: ${e.message}")
      false
    }
  }

  private fun runOnExecutor(block: () -> Unit) {
    try {
      executor.execute(block)
    } catch (e: RejectedExecutionException) {
      // Gate is being shut down - its state is going away with it
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Gate executor rejected task (shutting down)")
    }
  }

  /** Caller must hold the lock. */
  private fun maybeUnregisterCallbackLocked() {
    if (waiting.isNotEmpty() || running.isNotEmpty()) return
    val callback = networkCallback ?: return
    networkCallback = null
    unmeteredNetworks.clear()
    try {
      connectivityManager.unregisterNetworkCallback(callback)
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Unregistered unmetered network callback")
    } catch (e: Exception) {
      RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to unregister network callback: ${e.message}")
    }
  }

  /** Caller must hold the lock. */
  private fun clearLocked(id: String) {
    waiting.remove(id)
    running.remove(id)
    // Drop the network binding so a later non-gated restart of the same ID
    // doesn't inherit a stale network
    resumableDownloader.getState(id)?.network = null
    maybeUnregisterCallbackLocked()
  }

  /**
   * Start every waiting unmetered-only download on the given network.
   * Caller must hold the lock; callers refresh the notification afterwards.
   */
  private fun startWaitingLocked(network: Network) {
    for (id in waiting.toList()) {
      val state = resumableDownloader.getState(id)
      val listener = host.createGateListener(id)
      if (state == null || listener == null) {
        // Cancelled concurrently (cancel runs under the lock) - drop the entry
        RNBackgroundDownloaderModuleImpl.logW(TAG, "Gated download $id has no job/state, dropping")
        waiting.remove(id)
        continue
      }

      waiting.remove(id)
      host.onGatedTransferStarting()
      state.network = network
      // Record the binding before resuming so an immediate transfer error
      // classifies correctly in regateAfterNetworkLoss
      running[id] = network
      if (resumableDownloader.resume(id, listener)) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Started gated download $id on unmetered network $network")
      } else {
        RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to start gated download $id")
        running.remove(id)
        state.network = null
      }
    }
  }

  /**
   * Pause gated downloads bound to a network that is no longer unmetered/available
   * and put them back into the waiting set. If another unmetered network is still
   * available they restart on it immediately. Caller must hold the lock.
   */
  private fun pauseRunningOnLocked(network: Network) {
    var movedToWaiting = false
    for ((id, boundNetwork) in running.entries.toList()) {
      if (boundNetwork != network) continue
      running.remove(id)
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Unmetered network lost, pausing gated download $id")
      resumableDownloader.pause(id)
      resumableDownloader.getState(id)?.network = null
      // Back to waiting: auto-restarts when an unmetered network is available again
      waiting.add(id)
      movedToWaiting = true
    }

    if (movedToWaiting) {
      // Another unmetered network may still be up (e.g. ethernet next to WiFi) -
      // its onAvailable already fired, so re-drain the waiting set explicitly
      unmeteredNetworks.firstOrNull()?.let { startWaitingLocked(it) }
    }
  }

  private fun isNetworkUnmetered(network: Network): Boolean {
    return NetworkRequestUtils.isUnmetered(connectivityManager.getNetworkCapabilities(network))
  }
}
