// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import it.ntsciccio.ntsfiretv.R
import java.io.File
import it.ntsciccio.ntsfiretv.system.StoragePermission
import it.ntsciccio.ntsfiretv.media.formatDuration
import it.ntsciccio.ntsfiretv.media.VlcLauncher
import it.ntsciccio.ntsfiretv.media.StorageVolumes
import it.ntsciccio.ntsfiretv.media.Progress
import it.ntsciccio.ntsfiretv.media.PlaybackPositions
import android.text.format.Formatter
import android.os.StatFs
import it.ntsciccio.ntsfiretv.apps.AllAppsActivity
import it.ntsciccio.ntsfiretv.apps.AppCatalog
import it.ntsciccio.ntsfiretv.apps.AppEntry
import it.ntsciccio.ntsfiretv.apps.AppLauncher
import it.ntsciccio.ntsfiretv.apps.LaunchableApp
import it.ntsciccio.ntsfiretv.apps.RecentApps
import it.ntsciccio.ntsfiretv.media.VideoBrowserActivity
import it.ntsciccio.ntsfiretv.system.HomeWatcherService
import it.ntsciccio.ntsfiretv.system.SettingsActivity
import it.ntsciccio.ntsfiretv.wallpaper.WallpaperPickerActivity
import it.ntsciccio.ntsfiretv.wallpaper.WallpaperRepository
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Main launcher screen: wallpaper (Menu key to change it), clock with date and weather,
 * "In riproduzione" (Now playing) widget, "Recenti" (Recents) row and dock. Starts [HomeWatcherService] for the Home key.
 *
 * Everything that reads apps, images or network runs in the background; the view updates on return.
 */
class HomeActivity : Activity() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var wallpaperView: ImageView
    private lateinit var dock: LinearLayout
    private lateinit var recents: View
    private lateinit var recentsRow: LinearLayout
    private lateinit var weatherView: TextView
    private lateinit var usbPanel: View
    /** Video opened in VLC from "Continua a guardare" (Continue watching), to save its position on return. */
    private var playingPath: String? = null
    private lateinit var wallpaperRepository: WallpaperRepository
    private lateinit var nowPlaying: NowPlayingWidget
    private val dockTiles = mutableListOf<Pair<DockItem, DockTileView>>()
    /** Drawn dock order (saved string); different = the dock must be rebuilt. */
    private var dockOrderKey: String? = null
    private var dockBuilt = false
    /** Content shown in "Recenti" (video to resume + packages): if it does not change, it is not rebuilt. */
    private var shownRecents: List<String> = emptyList()
    /** Last hour the wallpaper was checked (day/night changes only at 7 and 20). */
    private var wallpaperCheckedHour = -1
    /** Wallpaper shown (see [WallpaperRepository.currentKey]); null = to be loaded. */
    private var wallpaperKey: String? = null
    /** Theme version the home was drawn with: if it changes in Settings, the home is recreated. */
    private var appliedThemeVersion = 0

    /** Every minute: day/night wallpaper (only when the hour changes) and weather update by themselves. */
    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hour != wallpaperCheckedHour) {
                wallpaperCheckedHour = hour
                refreshWallpaper()
            }
            refreshWeather()
        }
    }

    /** USB stick plugged in or removed: the USB panel appears or disappears. */
    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshUsb()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        playingPath = savedInstanceState?.getString(STATE_PLAYING)
        appliedThemeVersion = ThemeStore.version
        val theme = ThemeStore.current(this)
        ThemeStyler.styleClock(findViewById(R.id.clock), theme)
        ThemeStyler.styleSecondary(findViewById(R.id.clock_date), theme)
        ThemeStyler.styleSecondary(findViewById(R.id.recents_label), theme)
        findViewById<View>(R.id.home_scrim).visibility = if (theme.homeScrim) View.VISIBLE else View.GONE

        wallpaperView = findViewById(R.id.wallpaper)
        dock = findViewById(R.id.dock)
        recents = findViewById(R.id.recents)
        recentsRow = findViewById(R.id.recents_row)
        weatherView = findViewById<TextView>(R.id.clock_weather).also { ThemeStyler.styleSecondary(it, theme) }
        wallpaperRepository = WallpaperRepository(applicationContext)
        nowPlaying = NowPlayingWidget(findViewById(R.id.now_playing))
        usbPanel = findViewById<View>(R.id.usb_panel).apply {
            background = ThemeStyler.tileBackground(this@HomeActivity, theme)
            ThemeStyler.styleSecondary(findViewById(R.id.usb_subtitle), theme)
        }
        setupShortcuts(theme)

        AppCatalog.watchPackages(this)

        // Home key (requires the GET_USAGE_STATS appop)
        if (HomeWatcherService.hasUsageAccess(this)) HomeWatcherService.start(this)
    }

    override fun onStart() {
        super.onStart()
        nowPlaying.start()
        registerReceiver(timeTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        registerReceiver(
            mountReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addDataScheme("file")
            },
        )
    }

    override fun onResume() {
        super.onResume()
        HomeNavigation.clear()
        if (ThemeStore.version != appliedThemeVersion) {
            recreate()
            return
        }
        // Order, installed apps, recents, wallpaper and weather may have changed in the meantime
        refreshDockOrder()
        refreshDockAndRecents()
        refreshWallpaper()
        refreshWeather()
        refreshUsb()
    }

    override fun onStop() {
        unregisterReceiver(timeTickReceiver)
        unregisterReceiver(mountReceiver)
        nowPlaying.stop()
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Memory: if the system asks for it while another app is in use (e.g. during a video), app images
     * and the wallpaper are released, and they are reloaded on return.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN arrives every time an app is opened: releasing there would force reloading the banners on return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            AppCatalog.trimVisuals()
            wallpaperView.setImageDrawable(null)
            wallpaperKey = null
        }
    }

    // Fire OS 6 (API 25) has no Android 13+ predictive back: onKeyDown is enough
    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        // Being the home, the Back key must not close it
        KeyEvent.KEYCODE_BACK -> true
        KeyEvent.KEYCODE_MENU -> {
            startActivity(Intent(this, WallpaperPickerActivity::class.java))
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    /** Rebuilds the dock only if the saved order changed (no needless PackageManager reads). */
    private fun refreshDockOrder() {
        val key = DockPreferences.orderKey(this)
        if (dockBuilt && key == dockOrderKey) return
        dockBuilt = true
        dockOrderKey = key
        buildDock(DockPreferences.load(this))
    }

    private fun buildDock(items: List<DockItem>) {
        dock.removeAllViews()
        dockTiles.clear()
        items.forEach { item ->
            val tile = DockTileView(this).apply {
                bind(item.label)
                setVisual(getDrawable(item.iconRes), isBanner = false)
                setOnClickListener { onDockItemSelected(item) }
            }
            dock.addTileInRow(tile)
            dockTiles += item to tile
        }

        // Initial focus on the first tile
        dock.post { dock.getChildAt(0)?.requestFocus() }
    }

    /** Banners of the dock apps and the "Recenti" row, read in the background. */
    private fun refreshDockAndRecents() {
        val tiles = dockTiles.toList()
        val dockPackages = tiles.mapNotNull { (it.first.target as? DockTarget.App)?.packageName }.toSet()
        executor.execute {
            val entries: List<AppEntry?> = tiles.map { (item, _) ->
                (item.target as? DockTarget.App)?.let { AppCatalog.find(this, it.packageName) }
            }
            val recent = RecentApps.load(this, exclude = dockPackages, limit = MAX_RECENTS)
            val recentEntries = recent.map { it to AppCatalog.entry(this, it) }
            // "Continua a guardare": the last video left halfway, if the file is reachable
            val resume = if (StoragePermission.isGranted(this)) PlaybackPositions(this).latest() else null
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                tiles.forEachIndexed { index, (item, tile) ->
                    if (item.target !is DockTarget.App) return@forEachIndexed
                    val app = entries[index]
                    if (app != null) tile.setVisual(app.visual, app.isBanner) else tile.setVisual(getDrawable(item.iconRes), isBanner = false)
                    tile.setAvailable(app != null)
                }
                showRecents(recentEntries, resume)
            }
        }
    }

    /**
     * "Recenti" row: first the video to resume, if any, then the most recently used apps.
     * Rebuilt only if the content changes, otherwise only the images are updated.
     */
    private fun showRecents(apps: List<Pair<LaunchableApp, AppEntry>>, resume: Pair<File, Progress>?) {
        val key = listOfNotNull(resume?.let { "${it.first.absolutePath}@${it.second.positionMs}" }) +
                apps.map { it.first.packageName }
        val offset = if (resume != null) 1 else 0
        if (key == shownRecents) {
            apps.forEachIndexed { index, (_, entry) ->
                (recentsRow.getChildAt(index + offset) as? DockTileView)?.setVisual(entry.visual, entry.isBanner)
            }
            return
        }
        shownRecents = key
        val focusedInRow = recentsRow.hasFocus()
        recentsRow.removeAllViews()
        recents.visibility = if (key.isEmpty()) View.GONE else View.VISIBLE
        resume?.let { (file, progress) ->
            recentsRow.addTileInRow(
                DockTileView(this).apply {
                    bind(getString(R.string.continue_watching, file.nameWithoutExtension, formatDuration(progress.positionMs)))
                    setVisual(getDrawable(R.drawable.ic_video), isBanner = false)
                    setOnClickListener { playVideo(file, progress.positionMs) }
                },
            )
        }
        apps.forEach { (app, entry) ->
            recentsRow.addTileInRow(
                DockTileView(this).apply {
                    bind(app.label)
                    setVisual(entry.visual, entry.isBanner)
                    setOnClickListener { AppLauncher.launch(this@HomeActivity, app.packageName) }
                },
            )
        }
        if (focusedInRow) recentsRow.getChildAt(0)?.requestFocus()
    }

    private fun playVideo(file: File, positionMs: Long) {
        if (VlcLauncher.play(this, file, positionMs)) playingPath = file.absolutePath else toast(R.string.vlc_not_installed, long = true)
    }

    /** VLC returns where playback got to: updates "Continua a guardare". */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VlcLauncher.REQUEST_CODE) return
        val path = playingPath ?: return
        playingPath = null
        val position = data?.getLongExtra(VlcLauncher.EXTRA_POSITION, -1L) ?: -1L
        val duration = data?.getLongExtra(VlcLauncher.EXTRA_DURATION, 0L) ?: 0L
        if (position >= 0) PlaybackPositions(applicationContext).save(path, position, duration)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PLAYING, playingPath)
    }

    /** USB stick panel: the first connected USB storage, with its free space. */
    private fun refreshUsb() {
        executor.execute {
            val volume = StorageVolumes.removable(this).firstOrNull()
            val free = volume?.let { runCatching { StatFs(it.root.absolutePath).availableBytes }.getOrNull() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (volume == null) {
                    usbPanel.visibility = View.GONE
                    return@runOnUiThread
                }
                usbPanel.findViewById<TextView>(R.id.usb_title).text = volume.label
                usbPanel.findViewById<TextView>(R.id.usb_subtitle).text =
                    if (free != null) getString(R.string.usb_free, Formatter.formatShortFileSize(this, free)) else getString(R.string.usb_open)
                usbPanel.setOnClickListener { startActivity(VideoBrowserActivity.intent(this, volume.root)) }
                usbPanel.visibility = View.VISIBLE
            }
        }
    }

    /** Reloads the wallpaper in the background, only if it changed (choice, theme, day/night, "Nero"). */
    private fun refreshWallpaper() {
        val key = wallpaperRepository.currentKey()
        if (key == wallpaperKey) return
        wallpaperKey = key
        if (key == "black") {
            wallpaperView.setImageDrawable(null)
            return
        }
        executor.execute {
            val bitmap: Bitmap? = wallpaperRepository.loadCurrent()
            runOnUiThread { if (!isDestroyed && bitmap != null) wallpaperView.setImageBitmap(bitmap) }
        }
    }

    /** Weather below the clock: cached data right away, network update every 30 minutes. */
    private fun refreshWeather() {
        showWeather(Weather.cached(this))
        if (!Weather.needsRefresh(this)) return
        executor.execute {
            val current = Weather.refresh(applicationContext)
            runOnUiThread { if (!isDestroyed && current != null) showWeather(current) }
        }
    }

    private fun showWeather(current: Weather.Current?) {
        if (current == null) {
            weatherView.visibility = View.GONE
            return
        }
        val text = if (current.description.isEmpty()) {
            getString(R.string.weather_temperature, current.temperature)
        } else {
            getString(R.string.weather_format, current.temperature, current.description)
        }
        // Called every minute: rewritten only if it changes, so the clock is not re-laid out
        if (weatherView.text.toString() != text) weatherView.text = text
        weatherView.visibility = View.VISIBLE
    }

    private fun onDockItemSelected(item: DockItem) {
        when (val target = item.target) {
            is DockTarget.App -> {
                if (!AppLauncher.launch(this, target.packageName)) {
                    // App not installed: open its Appstore page, if there is one
                    val message = if (AppLauncher.openStorePage(this, target.packageName)) {
                        R.string.app_not_installed_store
                    } else {
                        R.string.app_not_installed
                    }
                    toast(getString(message, item.label))
                }
            }
            DockTarget.AllApps -> startActivity(Intent(this, AllAppsActivity::class.java))
            DockTarget.Video -> startActivity(Intent(this, VideoBrowserActivity::class.java))
            DockTarget.Settings -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /**
     * Round buttons below the clock: they open Fire OS pages without Amazon permissions
     * ("Schermo e suoni", "Preferenze" and "Accessibilità" require `LAUNCHER_SETTINGS`). The name appears on focus.
     */
    private fun setupShortcuts(theme: ThemeSpec) {
        val label = findViewById<TextView>(R.id.shortcut_label)
        SHORTCUTS.forEach { (id, action) ->
            findViewById<View>(id).apply {
                background = ThemeStyler.controlButtonBackground(theme)
                setOnClickListener { openSystemPage(action) }
                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) label.text = view.contentDescription
                    label.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                }
            }
        }
    }

    /** Opens the settings page; if Fire OS does not open it, the general settings. */
    private fun openSystemPage(action: String) {
        if (startSystemActivity(action)) return
        toast(R.string.shortcut_unavailable)
        startSystemActivity(Settings.ACTION_SETTINGS)
    }

    private fun startSystemActivity(action: String): Boolean = try {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private companion object {
        const val STATE_PLAYING = "playing"
        const val MAX_RECENTS = 5
        val SHORTCUTS = listOf(
            R.id.shortcut_network to Settings.ACTION_WIFI_SETTINGS,
            R.id.shortcut_controllers to "android.settings.CONTROLLERS_SETTINGS",
            R.id.shortcut_apps to "com.amazon.device.settings.action.APPLICATIONS",
            R.id.shortcut_device to "com.amazon.device.settings.action.DEVICE",
        )
    }
}
