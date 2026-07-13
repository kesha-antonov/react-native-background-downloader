package com.eko.utils

import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Single source of truth for what "acceptable network" means for downloads.
 * Used by both enforcement mechanisms of isAllowedOverMetered - the UIDT
 * JobScheduler constraint (Android 14+) and the foreground-service unmetered
 * gate (Android < 14) - so the two paths can't drift apart.
 */
object NetworkRequestUtils {

    /**
     * A request for an internet-capable network, optionally restricted to
     * unmetered networks (matching DownloadManager.Request.setAllowedOverMetered
     * semantics).
     *
     * NET_CAPABILITY_NOT_VPN (added by Builder default) is removed so that VPN
     * networks (e.g. Proton VPN, full-tunnel VPNs) are accepted. Without this,
     * only non-VPN networks are considered; a kill-switch VPN blocks that
     * traffic and the transfer never starts.
     */
    fun internetRequest(requireUnmetered: Boolean): NetworkRequest {
        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (requireUnmetered) {
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        return builder.build()
    }

    /**
     * Whether the capabilities describe a network that satisfies
     * internetRequest(requireUnmetered = true).
     */
    fun isUnmetered(capabilities: NetworkCapabilities?): Boolean {
        return capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
