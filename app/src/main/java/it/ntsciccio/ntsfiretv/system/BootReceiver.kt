// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts [HomeWatcherService] when the stick powers on.
 * Also listens for LOCKED_BOOT_COMPLETED because on Fire OS BOOT_COMPLETED arrives minutes late.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action: String? = intent?.action
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED && action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("NTSFireTV", "Ricevuto $action")
        HomeWatcherService.start(context, fromBoot = true)
    }
}
