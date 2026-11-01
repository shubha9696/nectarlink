// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Eliminates the latency and throughput gap between Router Wi-Fi mode and Phone Hotspot mode
 * (`android/app/src/main/java/app/nectarlink/core/network/WifiLowLatencyLockManager.kt`).
 *
 * Why Hotspot feels fast while Router Wi-Fi lags or drops speed:
 * - When Android runs a Hotspot (SoftAP), the OS holds the Wi-Fi radio in continuous
 *   active mode (CAM) with zero DTIM sleep.
 * - When Android connects as a client to an external Wi-Fi router, the Wi-Fi chipset
 *   aggressively enters 802.11 Power Save Mode (PSM) between frames, adding 50–200 ms
 *   latency spikes and causing QUIC ACKs to bunch up (which collapses file transfer speed).
 *
 * Acquiring `WIFI_MODE_FULL_LOW_LATENCY` (Android 10 / API 29+) instructs the Wi-Fi
 * firmware to disable power-save buffering and reduce transmit aggregation latency
 * while NectarLink has an active session or file transfer.
 */
class WifiLowLatencyLockManager(context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val activeConsumers = AtomicInteger(0)

    @Suppress("DEPRECATION")
    private val wifiLock: WifiManager.WifiLock = wifiManager.createWifiLock(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        },
        "NectarLink::LowLatencyQuicLock",
    ).apply {
        setReferenceCounted(false)
    }

    private val wakeLock: PowerManager.WakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "NectarLink::TransferCpuWakeLock",
    ).apply {
        setReferenceCounted(false)
    }

    /**
     * Call when starting a file transfer, screen mirror, webcam stream, or interactive
     * PC control session. Safe to call from multiple concurrent features.
     */
    @Synchronized
    fun acquireForActiveTransfer() {
        val count = activeConsumers.incrementAndGet()
        if (count == 1) {
            if (!wifiLock.isHeld) {
                wifiLock.acquire()
            }
            if (!wakeLock.isHeld) {
                // 30-minute safety ceiling per transfer session so battery is never drained if leaked
                wakeLock.acquire(30 * 60 * 1000L)
            }
        }
    }

    /**
     * Call inside a `finally` block when a file transfer or stream finishes.
     */
    @Synchronized
    fun releaseTransfer() {
        val remaining = activeConsumers.decrementAndGet().coerceAtLeast(0)
        activeConsumers.set(remaining)
        if (remaining == 0) {
            if (wifiLock.isHeld) {
                wifiLock.release()
            }
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    /**
     * Returns `true` if the phone is currently connected as a client to an external
     * Wi-Fi router (where `WIFI_MODE_FULL_LOW_LATENCY` and router hairpin pacing apply).
     */
    fun isConnectedToExternalWifiRouter(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}