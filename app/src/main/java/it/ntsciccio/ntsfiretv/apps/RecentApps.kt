// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.apps

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import it.ntsciccio.ntsfiretv.system.HomeWatcherService

/**
 * Most recently used apps, for the home's "Recenti" (Recent) row. Uses the same permission as the Home key
 * (appop GET_USAGE_STATS): without it the row stays hidden.
 */
object RecentApps {

    private const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    /** The last result is reused for a minute: returning home doesn't re-read 7 days of stats. */
    private const val CACHE_MS = 60_000L

    @Volatile
    private var cache: Cached? = null

    private class Cached(val key: String, val time: Long, val packages: List<String>)

    /** System screens that don't count as "used apps". */
    private val SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.amazon.tv.launcher",
        "com.amazon.tv.settings.v2",
        "com.amazon.ftv.screensaver",
    )

    /** Up to [limit] apps, most recent first; excludes those in [exclude] (e.g. already in the dock). Off the UI thread. */
    fun load(context: Context, exclude: Set<String>, limit: Int): List<LaunchableApp> {
        if (!HomeWatcherService.hasUsageAccess(context)) return emptyList()
        val launchable = AppCatalog.visibleApps(context).associateBy { it.packageName }
        val now = System.currentTimeMillis()
        val key = "$limit|${exclude.sorted().joinToString()}"
        cache?.takeIf { it.key == key && now - it.time < CACHE_MS }?.let { cached ->
            return cached.packages.mapNotNull { launchable[it] }
        }

        val manager: UsageStatsManager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val stats: List<UsageStats> = manager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - WINDOW_MS, now).orEmpty()
        val result = stats
            .filter { it.lastTimeUsed > 0 && it.totalTimeInForeground > 0 }
            .sortedByDescending { it.lastTimeUsed }
            .map { it.packageName }
            .distinct()
            .filter { it != context.packageName && it !in SYSTEM_PACKAGES && it !in exclude }
            .mapNotNull { launchable[it] }
            .take(limit)
        cache = Cached(key, now, result.map { it.packageName })
        return result
    }
}
