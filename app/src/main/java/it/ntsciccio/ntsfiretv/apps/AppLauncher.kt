// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Launches external apps, preferring the TV version when it exists. */
object AppLauncher {

    /** @return false if the app is not installed or cannot be launched. */
    fun launch(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return false

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Opens the app's page in the Amazon Appstore. @return false if not possible. */
    fun openStorePage(context: Context, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?p=$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
