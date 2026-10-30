// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.apps

import android.app.usage.UsageStatsManager
import android.content.Context
import it.ntsciccio.ntsfiretv.system.HomeWatcherService

/**
 * How much and when apps were used in the last 30 days, to sort "Tutte le app" (All apps).
 * Uses the same permission as the Home key; without it, returns an empty map.
 */
object AppUsage {

    data class Stat(val lastUsed: Long, val totalTime: Long)

    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    private const val CACHE_MS = 60_000L

    @Volatile
    private var cache: Pair<Long, Map<String, Stat>>? = null

    /** Off the UI thread. */
    fun stats(context: Context): Map<String, Stat> {
        if (!HomeWatcherService.hasUsageAccess(context)) return emptyMap()
        val now = System.currentTimeMillis()
        cache?.takeIf { now - it.first < CACHE_MS }?.let { return it.second }

        val manager: UsageStatsManager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val result = manager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - WINDOW_MS, now).orEmpty()
            .groupBy { it.packageName }
            .mapValues { (_, list) -> Stat(list.maxOf { it.lastTimeUsed }, list.sumOf { it.totalTimeInForeground }) }
        cache = now to result
        return result
    }
}
