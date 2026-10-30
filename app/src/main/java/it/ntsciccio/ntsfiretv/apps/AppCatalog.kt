// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache

/**
 * In-memory cache of installed apps, shared by the home, "Tutte le app" (All apps) and the "Recenti" (Recent) row.
 *
 * - The list (names only) is kept until an app is installed or uninstalled ([invalidate]).
 * - Banners and icons live in an LRU of [MAX_VISUAL_BYTES]: opening "Tutte le app" a second
 *   time is instant, but memory stays bounded (4 MB) and is freed with [trimVisuals].
 *
 * Loading methods must be called off the UI thread.
 */
object AppCatalog {

    private const val MAX_VISUAL_BYTES = 4 * 1024 * 1024
    private const val FALLBACK_BYTES = 64 * 1024

    @Volatile
    private var apps: List<LaunchableApp>? = null

    private val visuals = object : LruCache<String, AppEntry>(MAX_VISUAL_BYTES) {
        override fun sizeOf(key: String, value: AppEntry): Int =
            (value.visual as? BitmapDrawable)?.bitmap?.allocationByteCount ?: FALLBACK_BYTES
    }

    /** All launchable apps (hidden ones included), sorted by name. */
    fun apps(context: Context): List<LaunchableApp> =
        apps ?: AppRepository(context.applicationContext).listLaunchableApps().also { apps = it }

    /** Apps to show in the grid: without those hidden by the user. */
    fun visibleApps(context: Context): List<LaunchableApp> {
        val hidden = AppPreferences.hidden(context)
        return apps(context).filter { it.packageName !in hidden }
    }

    /** The app's banner or icon, from the cache if possible. */
    fun entry(context: Context, app: LaunchableApp): AppEntry =
        visuals.get(app.packageName)
            ?: AppRepository(context.applicationContext).loadEntry(app).also { visuals.put(app.packageName, it) }

    /** Image already cached, without loading it: to redraw the grid immediately (UI thread). */
    fun peek(packageName: String): AppEntry? = visuals.get(packageName)

    /** The installed app with this package (with image), or null. */
    fun find(context: Context, packageName: String): AppEntry? =
        apps(context).firstOrNull { it.packageName == packageName }?.let { entry(context, it) }

    @Volatile
    private var watching = false

    /** Registers, only once and on the app context, a listener for installs and removals. */
    fun watchPackages(context: Context) {
        if (watching) return
        watching = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = invalidate()
        }
        context.applicationContext.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
        )
    }

    /** An app was installed, updated or removed. */
    fun invalidate() {
        apps = null
        visuals.evictAll()
    }

    /** Frees the images (low memory, or custom banners changed). */
    fun trimVisuals() {
        visuals.evictAll()
    }
}
