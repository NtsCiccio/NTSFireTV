// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.system

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * "Empty" notification listener: it doesn't read notifications, it exists only because Android
 * lets enabled listeners see active MediaSessions ("In riproduzione" / Now Playing widget).
 * Enabled via ADB, see docs/DEVELOPMENT.md.
 */
class NowPlayingListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connesso")
    }

    companion object {
        private const val TAG = "NTSFireTV"
        private const val ENABLED_LISTENERS = "enabled_notification_listeners"

        fun component(context: Context): ComponentName =
            ComponentName(context, NowPlayingListenerService::class.java)

        fun isEnabled(context: Context): Boolean =
            isComponentListed(context, ENABLED_LISTENERS, ':', component(context))
    }
}
