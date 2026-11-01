// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.system

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.Formatter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import it.ntsciccio.ntsfiretv.R
import it.ntsciccio.ntsfiretv.home.toast
import it.ntsciccio.ntsfiretv.home.tvDialog
import it.ntsciccio.ntsfiretv.home.OverlayActivity
import it.ntsciccio.ntsfiretv.apps.AppPreferences
import it.ntsciccio.ntsfiretv.home.Weather
import it.ntsciccio.ntsfiretv.home.DockOrderActivity
import it.ntsciccio.ntsfiretv.home.HomePreferences
import it.ntsciccio.ntsfiretv.home.Row
import it.ntsciccio.ntsfiretv.home.RowAdapter
import it.ntsciccio.ntsfiretv.home.ThemeEditorActivity
import it.ntsciccio.ntsfiretv.home.ThemeSpec
import it.ntsciccio.ntsfiretv.home.ThemeStore
import it.ntsciccio.ntsfiretv.home.ThemeStyler
import it.ntsciccio.ntsfiretv.wallpaper.WallpaperPickerActivity
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * NTSFireTV settings: wallpaper, dock order, service status
 * (with the ADB command to enable them), Fire OS settings and version.
 * The status refreshes every time the screen is shown again.
 */
class SettingsActivity : OverlayActivity() {

    private lateinit var listView: ListView
    private lateinit var adapter: RowAdapter
    /** Network (weather) and file (backup) work off the UI thread, one at a time. */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    /** Theme loading: fast, must not queue behind network and backup. */
    private val themeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var themeDialogPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        ThemeStyler.styleListScreen(this)
        findViewById<TextView>(R.id.list_title).setText(R.string.settings_title)
        listView = findViewById(R.id.list)
        adapter = RowAdapter(this)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> adapter.getItem(position).action?.invoke() }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        themeExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (isLeaving) return
        val selected = listView.selectedItemPosition
        adapter.setRows(buildRows())
        listView.setSelection(selected.coerceAtLeast(0))
        listView.requestFocus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        adapter.setRows(buildRows())
    }

    private fun buildRows(): List<Row> {
        val homeActive = HomeWatcherService.isRunning
        val usageAccess = HomeWatcherService.hasUsageAccess(this)
        val listenerEnabled = NowPlayingListenerService.isEnabled(this)
        val storageGranted = StoragePermission.isGranted(this)
        val listener = NowPlayingListenerService.component(this).flattenToString()

        return listOf(
            Row(
                title = getString(R.string.settings_wallpaper),
                subtitle = getString(R.string.settings_wallpaper_hint),
                action = { startActivity(Intent(this, WallpaperPickerActivity::class.java)) },
            ),
            Row(
                title = getString(R.string.settings_theme),
                subtitle = ThemeStore.current(this).name,
                action = { chooseTheme() },
            ),
            Row(
                title = getString(R.string.settings_dock),
                subtitle = getString(R.string.settings_dock_hint),
                action = { startActivity(Intent(this, DockOrderActivity::class.java)) },
            ),
            Row(
                title = getString(R.string.settings_home_key),
                subtitle = statusText(
                    homeActive,
                    if (usageAccess) R.string.status_restart_needed else R.string.status_needs_adb,
                ),
                action = { showAdbCommand(R.string.settings_home_key, "adb shell appops set $packageName GET_USAGE_STATS allow") },
            ),
            Row(
                title = getString(R.string.settings_now_playing),
                subtitle = statusText(listenerEnabled, R.string.status_needs_adb),
                action = {
                    showAdbCommand(
                        R.string.settings_now_playing,
                        "adb shell settings put secure enabled_notification_listeners $listener",
                    )
                },
            ),
            Row(
                title = getString(R.string.settings_storage),
                subtitle = statusText(storageGranted, R.string.status_press_to_allow),
                action = {
                    if (storageGranted) {
                        showAdbCommand(R.string.settings_storage, storageCommand())
                    } else {
                        StoragePermission.request(this)
                    }
                },
            ),
            Row(
                title = getString(R.string.settings_memory_meter),
                subtitle = getString(if (HomePreferences.memoryMeter(this)) R.string.settings_shown else R.string.settings_hidden),
                action = {
                    HomePreferences.setMemoryMeter(this, !HomePreferences.memoryMeter(this))
                    adapter.setRows(buildRows())
                },
            ),
            Row(
                title = getString(R.string.settings_child_lock),
                subtitle = getString(
                    if (HomePreferences.childLock(this)) R.string.settings_child_lock_on else R.string.settings_child_lock_off,
                ),
                action = { toggleChildLock() },
            ),
            Row(
                title = getString(R.string.settings_hidden_apps),
                subtitle = hiddenAppsText(),
                action = { showHiddenApps() },
            ),
            Row(
                title = getString(R.string.settings_weather),
                subtitle = Weather.place(this)?.name ?: getString(R.string.settings_weather_off),
                action = { chooseWeatherCity() },
            ),
            Row(
                title = getString(R.string.settings_backup),
                subtitle = getString(R.string.settings_backup_hint),
                action = { exportBackup() },
            ),
            Row(
                title = getString(R.string.settings_restore),
                subtitle = getString(R.string.settings_restore_hint),
                action = { restoreBackup() },
            ),
            Row(
                title = getString(R.string.settings_screensaver),
                subtitle = statusText(ClockDreamService.isEnabled(this), R.string.status_needs_adb),
                action = {
                    val component = ClockDreamService.component(this).flattenToString()
                    showAdbCommand(
                        R.string.settings_screensaver,
                        "adb shell settings put secure screensaver_components $component\n" +
                                "adb shell settings put secure screensaver_enabled 1",
                    )
                },
            ),
            Row(
                title = getString(R.string.settings_dns_filter),
                subtitle = statusText(DeviceStatus.dnsFilterActive(this), R.string.status_dns_hint),
                action = { showDnsFilterHelp() },
            ),
            Row(
                title = getString(R.string.settings_status),
                subtitle = getString(R.string.settings_status_hint),
                action = { showDeviceStatus() },
            ),
            Row(
                title = getString(R.string.settings_fire_os),
                subtitle = getString(R.string.settings_fire_os_hint),
                action = { openSystemSettings() },
            ),
            Row(
                title = getString(R.string.settings_version),
                subtitle = versionName(),
            ),
        )
    }

    /**
     * Theme picker: built-in and user themes, plus "Nuovo tema" (New theme: editor, starting from the current theme)
     * and "Modifica" (Edit) if the current theme is a user one. This screen is recreated at once, the home on return.
     *
     * User themes are read from /sdcard, so the list is prepared in the background on its own queue
     * (it must not wait for a weather search or a backup); repeated OK presses open only one dialog.
     */
    private fun chooseTheme() {
        if (themeDialogPending) return
        themeDialogPending = true
        themeExecutor.execute {
            val themes = ThemeStore.list(applicationContext)
            runOnUiThread {
                themeDialogPending = false
                if (!isDestroyed) showThemeDialog(themes)
            }
        }
    }

    private fun showThemeDialog(themes: List<ThemeSpec>) {
        val current = ThemeStore.current(this)
        val canEdit = !current.isBuiltIn
        val labels = themes.map { if (it.id == current.id) "✓  ${it.name}" else "    ${it.name}" } +
                getString(R.string.theme_new) +
                (if (canEdit) listOf(getString(R.string.theme_edit, current.name)) else emptyList())

        tvDialog()
            .setTitle(R.string.settings_theme)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < themes.size -> if (themes[which].id != current.id) {
                        ThemeStore.select(this, themes[which])
                        recreate()
                    }
                    which == themes.size -> startActivity(ThemeEditorActivity.intent(this, themeId = null))
                    else -> startActivity(ThemeEditorActivity.intent(this, themeId = current.id))
                }
            }
            .show()
    }

    /** Child lock: turning it on asks for confirmation and explains how to unlock; off at once. */
    private fun toggleChildLock() {
        if (HomePreferences.childLock(this)) {
            HomePreferences.setChildLock(this, false)
            toast(R.string.child_lock_disabled)
            adapter.setRows(buildRows())
            return
        }
        tvDialog()
            .setTitle(R.string.settings_child_lock)
            .setMessage(R.string.child_lock_message)
            .setPositiveButton(R.string.child_lock_enable) { _, _ ->
                HomePreferences.setChildLock(this, true)
                toast(R.string.child_lock_enabled)
                // Back to the home: Settings is no longer reachable from there
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The DNS filter is installed from the computer: shows the Gradle command with this stick's IP. */
    private fun showDnsFilterHelp() {
        runInBackground({ DeviceStatus.read(applicationContext).ip }) { ip ->
            tvDialog()
                .setTitle(R.string.settings_dns_filter)
                .setMessage(getString(R.string.settings_dns_filter_message, ip ?: "IP"))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** IP, network, memory and storage: read on demand, in the background. */
    private fun showDeviceStatus() {
        runInBackground({ DeviceStatus.read(applicationContext) }) { status ->
            fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)
            val signal = status.wifiSignal?.let { "▂▄▆█".take(it).padEnd(4, '·') }
            val lines = listOf(
                getString(R.string.status_ip, status.ip ?: getString(R.string.status_unknown)),
                getString(
                    R.string.status_wifi,
                    status.wifiName ?: getString(R.string.status_not_connected),
                    signal.orEmpty(),
                ),
                getString(R.string.status_ram, size(status.ramFree), size(status.ramTotal)),
                getString(R.string.status_storage, size(status.storageFree), size(status.storageTotal)),
                getString(R.string.status_system, status.system),
                getString(R.string.status_adb, status.ip ?: "IP"),
            )
            tvDialog()
                .setTitle(R.string.settings_status)
                .setMessage(lines.joinToString("\n\n"))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun hiddenAppsText(): String {
        val count = AppPreferences.hidden(this).size
        return if (count == 0) getString(R.string.settings_hidden_none) else getString(R.string.settings_hidden_count, count)
    }

    /** List of hidden apps: OK on one puts it back in "Tutte le app" (All apps). */
    private fun showHiddenApps() {
        val hidden = AppPreferences.hidden(this).toList()
        if (hidden.isEmpty()) {
            toast(R.string.settings_hidden_none)
            return
        }
        val labels = hidden.map { packageName ->
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName
            }
        }
        tvDialog()
            .setTitle(R.string.settings_hidden_show_title)
            .setItems(labels.toTypedArray()) { _, which ->
                AppPreferences.show(this, hidden[which])
                adapter.setRows(buildRows())
            }
            .show()
    }

    /** Weather city: looked up on Open-Meteo; empty = weather off. */
    private fun chooseWeatherCity() {
        val input = EditText(this).apply {
            setText(Weather.place(this@SettingsActivity)?.name?.substringBefore(','))
            hint = getString(R.string.settings_weather_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSelectAllOnFocus(true)
        }
        tvDialog()
            .setTitle(R.string.settings_weather)
            .setMessage(R.string.settings_weather_privacy)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> searchCity(input.text.toString().trim()) }
            .setNeutralButton(R.string.settings_weather_disable) { _, _ ->
                Weather.setPlace(this, null)
                adapter.setRows(buildRows())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun searchCity(name: String) {
        if (name.isEmpty()) return
        runInBackground({ Weather.search(name) }) { place ->
            if (place == null) {
                toast(getString(R.string.settings_weather_not_found, name), long = true)
            } else {
                Weather.setPlace(this, place)
                toast(getString(R.string.settings_weather_set, place.name))
                adapter.setRows(buildRows())
            }
        }
    }

    private fun exportBackup() {
        if (!StoragePermission.ensure(this)) return
        runInBackground({ SettingsBackup.export(applicationContext) }) { folders ->
            val message = if (folders.isEmpty()) {
                getString(R.string.settings_backup_error)
            } else {
                getString(R.string.settings_backup_done, folders.joinToString("\n") { it.absolutePath })
            }
            tvDialog()
                .setTitle(R.string.settings_backup)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun restoreBackup() {
        if (!StoragePermission.ensure(this)) return
        // Searching USB drives can block (e.g. an HDD spinning up): off the UI thread
        runInBackground({ SettingsBackup.findBackup(applicationContext) }) { folder ->
            if (folder == null) toast(R.string.settings_restore_none, long = true) else confirmRestore(folder)
        }
    }

    private fun confirmRestore(folder: File) {
        tvDialog()
            .setTitle(R.string.settings_restore)
            .setMessage(getString(R.string.settings_restore_confirm, folder.absolutePath))
            .setPositiveButton(R.string.settings_restore_action) { _, _ ->
                runInBackground({ SettingsBackup.restore(applicationContext, folder) }) { ok ->
                    toast(if (ok) R.string.settings_restore_done else R.string.settings_restore_error, long = true)
                    if (ok) recreate()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Runs [work] in the background and passes the result to [done] on the UI thread. */
    private fun <T> runInBackground(work: () -> T, done: (T) -> Unit) {
        executor.execute {
            val result = work()
            runOnUiThread { if (!isDestroyed) done(result) }
        }
    }

    private fun statusText(active: Boolean, inactiveHint: Int): String =
        if (active) getString(R.string.status_active) else getString(R.string.status_inactive, getString(inactiveHint))

    private fun storageCommand(): String =
        "adb shell pm grant $packageName android.permission.READ_EXTERNAL_STORAGE\n" +
                "adb shell pm grant $packageName android.permission.WRITE_EXTERNAL_STORAGE"

    private fun showAdbCommand(titleRes: Int, command: String) {
        tvDialog()
            .setTitle(titleRes)
            .setMessage(getString(R.string.settings_adb_message, command))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.settings_fire_os_error)
        }
    }

    private fun versionName(): String = try {
        @Suppress("DEPRECATION")
        val name: String? = packageManager.getPackageInfo(packageName, 0).versionName
        name.orEmpty()
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }
}
