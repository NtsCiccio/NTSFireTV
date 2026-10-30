// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable

/** A launchable app, without images: listing apps is fast, loading banners and icons is not. */
data class LaunchableApp(
    val packageName: String,
    val label: String,
    val info: ResolveInfo,
)

/**
 * Reads the installed apps from the PackageManager.
 * Considers both TV apps (LEANBACK_LAUNCHER) and classic ones (LAUNCHER),
 * because many sideloaded apps only declare the latter.
 */
class AppRepository(private val context: Context) {

    private val pm = context.packageManager

    /** All launchable apps, excluding NTSFireTV, sorted by name. Names only: for images use [loadEntry]. */
    fun listLaunchableApps(): List<LaunchableApp> {
        val byPackage = LinkedHashMap<String, ResolveInfo>()

        // TV apps first: if an app has both categories, the TV version wins
        query(Intent.CATEGORY_LEANBACK_LAUNCHER).forEach { byPackage.putIfAbsent(it.activityInfo.packageName, it) }
        query(Intent.CATEGORY_LAUNCHER).forEach { byPackage.putIfAbsent(it.activityInfo.packageName, it) }

        byPackage.remove(context.packageName)
        return byPackage.values
            .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), it) }
            .sortedBy { it.label.lowercase() }
    }

    /** Loads the app's banner or icon: slow, call it off the UI thread and one app at a time. */
    fun loadEntry(app: LaunchableApp): AppEntry = toEntry(app.info, app.label)

    /** The app with this package, or null if it is not installed. */
    fun findApp(packageName: String): AppEntry? {
        val info = query(Intent.CATEGORY_LEANBACK_LAUNCHER, packageName).firstOrNull()
            ?: query(Intent.CATEGORY_LAUNCHER, packageName).firstOrNull()
            ?: return null
        return toEntry(info, info.loadLabel(pm).toString())
    }

    @Suppress("DEPRECATION") // The ResolveInfoFlags variant requires API 33
    private fun query(category: String, packageName: String? = null): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        if (packageName != null) intent.setPackage(packageName)
        return pm.queryIntentActivities(intent, 0)
    }

    private fun toEntry(info: ResolveInfo, label: String): AppEntry {
        val activity = info.activityInfo
        // First the user-chosen banner, then the app's own
        val banner: Drawable? = CustomBanners.load(context, activity.packageName) ?: loadBanner(activity)
        // The icon is needed only if there is no banner
        val visual: Drawable = banner ?: info.loadIcon(pm)

        return AppEntry(
            packageName = activity.packageName,
            label = label,
            visual = visual,
            isBanner = banner != null,
        )
    }

    /**
     * The activity's TV banner, or the app's, or null if it has none.
     * Uses the PackageManager methods, which explicitly declare a nullable return.
     */
    private fun loadBanner(activity: ActivityInfo): Drawable? = try {
        pm.getActivityBanner(ComponentName(activity.packageName, activity.name))
            ?: pm.getApplicationBanner(activity.applicationInfo)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
