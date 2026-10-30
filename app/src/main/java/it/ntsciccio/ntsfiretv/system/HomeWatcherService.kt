// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.system

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import it.ntsciccio.ntsfiretv.R
import it.ntsciccio.ntsfiretv.home.HomeActivity
import it.ntsciccio.ntsfiretv.home.HomeNavigation

/**
 * Brings NTSFireTV back whenever the Amazon home comes to the foreground
 * (remote's Home button, stick startup, exiting an app).
 *
 * Without root and without accessibility (blocked by Fire OS 6.7): every [POLL_MS] it reads the events
 * from [UsageStatsManager] and checks which activity is in the foreground. Requires the appop
 * `GET_USAGE_STATS`, granted via ADB. Polling is suspended while the screen is off.
 */
class HomeWatcherService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var usageStats: UsageStatsManager? = null

    /** Last activity moved to the foreground according to the events read so far. */
    private var foregroundPackage: String? = null
    private var foregroundClass: String? = null
    /** Time (currentTimeMillis) at which that activity moved to the foreground. */
    private var foregroundSince = 0L
    private var lastEventTime = 0L
    private var lastRedirectAt = 0L
    private var bootLaunchScheduled = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            // Stops while the screen is off: resumes on ACTION_SCREEN_ON. Also needed if the service
            // started with the stick already in standby (in that case SCREEN_OFF never arrives)
            if (getSystemService(PowerManager::class.java)?.isInteractive == false) return
            poll()
            handler.postDelayed(this, POLL_MS)
        }
    }

    /**
     * Opens the home after power-on. With LOCKED_BOOT_COMPLETED the system may still be
     * locked and HomeActivity can't start: wait for unlock, then let the Amazon home appear.
     */
    private val bootLaunchRunnable = object : Runnable {
        override fun run() {
            val userManager: UserManager? = getSystemService(UserManager::class.java)
            if (userManager?.isUserUnlocked != true) {
                handler.postDelayed(this, UNLOCK_CHECK_MS)
                return
            }
            handler.postDelayed({
                // If another app was opened in the meantime, don't interrupt it
                if (foregroundPackage == null || foregroundPackage == AMAZON_LAUNCHER) {
                    Log.i(TAG, "Avvio della stick: apro HomeActivity")
                    startActivity(homeIntent())
                }
            }, BOOT_LAUNCH_DELAY_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(pollRunnable)
                Intent.ACTION_SCREEN_ON -> {
                    handler.removeCallbacks(pollRunnable)
                    handler.post(pollRunnable)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!hasUsageAccess(this)) {
            Log.w(TAG, "Appop GET_USAGE_STATS non concesso: HomeWatcher fermo")
            stopSelf()
            return
        }

        usageStats = getSystemService(UsageStatsManager::class.java)
        thread = HandlerThread("HomeWatcher", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        handler = Handler(thread.looper)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter, null, handler)
        handler.post(pollRunnable)
        isRunning = true
        Log.i(TAG, "HomeWatcher avviato")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Both LOCKED_BOOT_COMPLETED and BOOT_COMPLETED arrive: the home must be opened only once
        if (intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true &&
            ::handler.isInitialized && !bootLaunchScheduled
        ) {
            bootLaunchScheduled = true
            handler.post(bootLaunchRunnable)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        if (::thread.isInitialized) {
            unregisterReceiver(screenReceiver)
            handler.removeCallbacks(pollRunnable)
            thread.quitSafely()
        }
        super.onDestroy()
    }

    /** Reads only the events new since the last check and redirects if needed. */
    private fun poll() {
        val manager: UsageStatsManager = usageStats ?: return
        val now = System.currentTimeMillis()
        val begin = if (lastEventTime > 0) lastEventTime + 1 else now - INITIAL_LOOKBACK_MS
        val events: UsageEvents = manager.queryEvents(begin, now) ?: return

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            lastEventTime = maxOf(lastEventTime, event.timeStamp)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                foregroundPackage = event.packageName
                foregroundClass = event.className
                foregroundSince = event.timeStamp
            }
        }

        // Only the actual home: search and other Amazon screens remain usable
        if (foregroundPackage == AMAZON_LAUNCHER && foregroundClass == AMAZON_HOME_ACTIVITY) {
            redirectHome(now - foregroundSince)
        }
    }

    /**
     * Returns to NTSFireTV. If our task exists it moves it to the front: that isn't an activity
     * start, so it avoids the ~5 s delay Android applies after the Home button.
     * Otherwise (e.g. right after boot) starts [HomeActivity] normally.
     */
    private fun redirectHome(detectedAfterMs: Long) {
        // Avoids rapid-fire attempts while our home is still appearing
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirectAt < DEBOUNCE_MS) return
        lastRedirectAt = now

        // The task comes back as it was (e.g. with Settings open): the screens above the home
        // close themselves in onResume (see HomeNavigation), without starting activities
        HomeNavigation.requestHome()
        if (moveOwnTaskToFront()) {
            Log.i(TAG, "Home Amazon rilevata dopo $detectedAfterMs ms: task portato in primo piano")
            return
        }

        Log.i(TAG, "Home Amazon rilevata dopo $detectedAfterMs ms: nessun task, avvio HomeActivity")
        startActivity(homeIntent())
    }

    /** Intent to open our home in a new task, without animation. */
    private fun homeIntent(): Intent = Intent(this, HomeActivity::class.java).addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION,
    )

    /** Brings this app's first valid task to the front. `false` if there is none. */
    private fun moveOwnTaskToFront(): Boolean {
        val activityManager: ActivityManager = getSystemService(ActivityManager::class.java) ?: return false
        val tasks: List<ActivityManager.AppTask> = activityManager.appTasks ?: return false
        for (task in tasks) {
            try {
                val info: ActivityManager.RecentTaskInfo = task.taskInfo ?: continue
                if (info.baseActivity == null && info.topActivity == null) continue
                task.moveToFront()
                return true
            } catch (e: IllegalArgumentException) {
                // Task closed in the meantime: try the next one
                Log.w(TAG, "Task non più valido", e)
            }
        }
        return false
    }

    /** Mandatory foreground service notification: channel only from API 26. */
    private fun buildNotification(): Notification {
        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager: NotificationManager? = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.home_watcher_channel),
                NotificationManager.IMPORTANCE_MIN,
            )
            manager?.createNotificationChannel(channel)
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.home_watcher_notification))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "NTSFireTV"
        private const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        private const val AMAZON_HOME_ACTIVITY = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
        private const val CHANNEL_ID = "home_watcher"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 100L
        private const val DEBOUNCE_MS = 1_000L
        /** On the first check, looks back far enough to see the Amazon home opened at boot. */
        private const val INITIAL_LOOKBACK_MS = 60_000L
        /** Delay after boot before opening the home, so we aren't covered by the Amazon home. */
        private const val BOOT_LAUNCH_DELAY_MS = 2_500L
        private const val EXTRA_FROM_BOOT = "from_boot"
        private const val UNLOCK_CHECK_MS = 500L

        /** `true` while the service is monitoring the foreground app (for Settings). */
        @Volatile
        var isRunning = false
            private set

        /** Starts the service (idempotent: nothing happens if it's already running). */
        fun start(context: Context, fromBoot: Boolean = false) {
            val intent = Intent(context, HomeWatcherService::class.java).putExtra(EXTRA_FROM_BOOT, fromBoot)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** `true` if the GET_USAGE_STATS appop has been granted (via ADB). */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps: AppOpsManager = context.getSystemService(AppOpsManager::class.java) ?: return false
            @Suppress("DEPRECATION")
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}