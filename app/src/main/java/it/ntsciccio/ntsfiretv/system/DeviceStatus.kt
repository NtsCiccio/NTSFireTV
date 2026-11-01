// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.net.Inet4Address
import java.net.NetworkInterface

/** Stick status for "Impostazioni → Stato della stick" (Settings → Stick status): network, memory, storage, OS. */
data class DeviceStatus(
    /** IP on the home network: the one to use with `adb connect`. */
    val ip: String?,
    val wifiName: String?,
    /** Wi-Fi signal from 0 (none) to 4 (excellent). */
    val wifiSignal: Int?,
    val ramFree: Long,
    val ramTotal: Long,
    val storageFree: Long,
    val storageTotal: Long,
    val system: String,
) {
    companion object {

        private const val SIGNAL_LEVELS = 5
        private const val DNS_FILTER_PACKAGE = "dnsfilter.android"

        fun read(context: Context): DeviceStatus {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            val storage = StatFs(Environment.getDataDirectory().absolutePath)
            val wifi = wifiInfo(context)
            return DeviceStatus(
                ip = localIp(),
                wifiName = wifi?.first,
                wifiSignal = wifi?.second,
                ramFree = memory.availMem,
                ramTotal = memory.totalMem,
                storageFree = storage.availableBytes,
                storageTotal = storage.totalBytes,
                system = "Android ${Build.VERSION.RELEASE} · ${Build.MODEL} · ${Build.DISPLAY}",
            )
        }

        /**
         * On-device DNS filter (personalDNSfilter, `./gradlew dnsFilter`): installed, enabled and with its VPN
         * tunnel up. Reads the network interfaces, no permissions needed.
         */
        fun dnsFilterActive(context: Context): Boolean {
            val enabled = try {
                context.packageManager.getApplicationInfo(DNS_FILTER_PACKAGE, 0).enabled
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            return enabled && runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { it.isUp && it.name.startsWith("tun") }
            }.getOrDefault(false)
        }

        /** First private IPv4 address (Wi-Fi or Ethernet), no permissions needed. */
        private fun localIp(): String? =
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress

        /** Network name and signal level; null if not connected to Wi-Fi. */
        @Suppress("DEPRECATION") // connectionInfo and calculateSignalLevel(rssi, n): the alternatives require API 29/30
        private fun wifiInfo(context: Context): Pair<String?, Int>? {
            val manager: WifiManager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
            val info = manager.connectionInfo ?: return null
            if (info.networkId == -1) return null
            val name = info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            return name to WifiManager.calculateSignalLevel(info.rssi, SIGNAL_LEVELS)
        }
    }
}
