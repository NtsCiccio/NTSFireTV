// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.apps

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import it.ntsciccio.ntsfiretv.R
import it.ntsciccio.ntsfiretv.home.DockPreferences
import it.ntsciccio.ntsfiretv.home.DockTileView
import it.ntsciccio.ntsfiretv.home.OverlayActivity
import it.ntsciccio.ntsfiretv.home.ThemeSpec
import it.ntsciccio.ntsfiretv.home.ThemeStyler
import it.ntsciccio.ntsfiretv.home.addTileInGrid
import it.ntsciccio.ntsfiretv.home.toast
import it.ntsciccio.ntsfiretv.home.withAlpha
import it.ntsciccio.ntsfiretv.home.tvDialog
import it.ntsciccio.ntsfiretv.wallpaper.WallpaperPickerActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * All installed apps (except hidden ones), split into sections by [AppCategory] and sorted
 * by name, by usage time or by recent use. The bar at the top searches by name:
 * while a search is active, the sections give way to the results.
 *
 * Tiles appear immediately with their name; banners and icons come from [AppCatalog]
 * (cached from the second opening on). The Menu key on an app opens: dock, category, banner, hide.
 */
class AllAppsActivity : OverlayActivity() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var theme: ThemeSpec
    private lateinit var sections: LinearLayout
    private lateinit var search: EditText
    private lateinit var sortButton: TextView
    private var apps: List<LaunchableApp> = emptyList()
    private var usage: Map<String, AppUsage.Stat> = emptyMap()
    /**
     * Tiles by package, created only once: sorting, search and categories move them
     * between grids without recreating them, so already loaded banners and icons are kept.
     */
    private val tiles = HashMap<String, DockTileView>()
    /** Custom banners version the images were loaded with. */
    private var bannersVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_apps)
        theme = ThemeStyler.styleGridScreen(this)
        // Opaque veil: with many sections, the home dock and recents showing through are distracting
        window.setBackgroundDrawable(ColorDrawable(withAlpha(theme.overlay, 0xFF)))
        sections = findViewById(R.id.app_sections)
        search = findViewById<EditText>(R.id.app_search).apply {
            ThemeStyler.styleTileLike(this, theme)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = render()
            })
            // "Avanti" (Next) on the TV keyboard: closes the keyboard and moves to the first result
            setOnEditorActionListener { view, _, _ ->
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
                firstTile()?.requestFocus()
                true
            }
        }
        sortButton = findViewById<TextView>(R.id.app_sort).apply {
            ThemeStyler.styleTileLike(this, theme)
            setOnClickListener { nextSort() }
        }
        showSortLabel()
        bannersVersion = CustomBanners.version
        load()
    }

    override fun onResume() {
        super.onResume()
        if (isLeaving) return
        // When returning from the picker an app's banner may have changed
        if (apps.isNotEmpty() && CustomBanners.version != bannersVersion) {
            bannersVersion = CustomBanners.version
            val list = apps
            executor.execute { loadVisuals(list) }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val app = (currentFocus as? DockTileView)?.tag as? LaunchableApp
        if (keyCode == KeyEvent.KEYCODE_MENU && app != null) {
            showAppMenu(app)
            return true
        }
        // Back with an active search: first clears the search, then (on the second press) closes
        if (keyCode == KeyEvent.KEYCODE_BACK && search.text.isNotEmpty()) {
            search.text.clear()
            firstTile()?.requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** List (without hidden apps) and usage stats in the background, then tiles and images. */
    private fun load() {
        executor.execute {
            val list = AppCatalog.visibleApps(applicationContext)
            val stats = AppUsage.stats(applicationContext)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps = list
                usage = stats
                render()
                firstTile()?.requestFocus()
            }
            loadVisuals(list)
        }
    }

    /** Loads banners and icons one app at a time (in the background). */
    private fun loadVisuals(list: List<LaunchableApp>) {
        list.forEach { app ->
            if (Thread.currentThread().isInterrupted) return
            val entry = AppCatalog.entry(applicationContext, app)
            runOnUiThread { if (!isDestroyed) tiles[app.packageName]?.setVisual(entry.visual, entry.isBanner) }
        }
    }

    /** Redraws the sections (categories, or search results) keeping focus on the app. */
    private fun render() {
        val focused = ((currentFocus as? DockTileView)?.tag as? LaunchableApp)?.packageName
        sections.removeAllViews()

        val sorted = sorted(apps)
        val query = search.text.toString().trim()
        if (query.isNotEmpty()) {
            val found = sorted.filter { it.label.contains(query, ignoreCase = true) }
            if (found.isEmpty()) addHeader(getString(R.string.all_apps_no_results, query))
            else addSection(getString(R.string.all_apps_results, found.size), found)
        } else {
            val byCategory = sorted.groupBy { AppCategory.of(this, it.packageName) }
            AppCategory.entries.forEach { category ->
                byCategory[category]?.let { addSection(getString(category.labelRes), it) }
            }
        }
        focused?.let { tiles[it]?.requestFocus() }
    }

    private fun sorted(list: List<LaunchableApp>): List<LaunchableApp> {
        val byName = compareBy<LaunchableApp> { it.label.lowercase() }
        return when (AppPreferences.sort(this)) {
            AppPreferences.Sort.ALPHABETICAL -> list.sortedWith(byName)
            AppPreferences.Sort.MOST_USED ->
                list.sortedWith(compareByDescending<LaunchableApp> { usage[it.packageName]?.totalTime ?: 0L }.then(byName))
            AppPreferences.Sort.RECENT ->
                list.sortedWith(compareByDescending<LaunchableApp> { usage[it.packageName]?.lastUsed ?: 0L }.then(byName))
        }
    }

    private fun addSection(title: String, list: List<LaunchableApp>) {
        addHeader(title)
        val grid = GridLayout(this).apply {
            columnCount = COLUMNS
            clipChildren = false
            clipToPadding = false
        }
        list.forEach { app ->
            val tile = tileFor(app)
            (tile.parent as? ViewGroup)?.removeView(tile)
            grid.addTileInGrid(tile)
        }
        // Left-aligned like the titles: a section with few apps doesn't float in the middle
        sections.addView(grid, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    private fun tileFor(app: LaunchableApp): DockTileView = tiles.getOrPut(app.packageName) {
        DockTileView(this).apply {
            bind(app.label)
            tag = app
            AppCatalog.peek(app.packageName)?.let { setVisual(it.visual, it.isBanner) }
            setOnClickListener { launch(app) }
        }
    }

    private fun addHeader(text: String) {
        val header = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_SIZE_SP)
            ThemeStyler.styleSecondary(this, theme)
            ThemeStyler.styleTitle(this, theme)
        }
        val margin = resources.getDimensionPixelSize(R.dimen.grid_tile_spacing)
        sections.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = margin })
    }

    /** First tile of the first section. */
    private fun firstTile(): DockTileView? =
        (0 until sections.childCount).asSequence()
            .mapNotNull { sections.getChildAt(it) as? GridLayout }
            .firstOrNull { it.childCount > 0 }
            ?.getChildAt(0) as? DockTileView

    private fun nextSort() {
        val all = AppPreferences.Sort.entries
        AppPreferences.setSort(this, all[(AppPreferences.sort(this).ordinal + 1) % all.size])
        showSortLabel()
        render()
    }

    private fun showSortLabel() {
        val label = when (AppPreferences.sort(this)) {
            AppPreferences.Sort.ALPHABETICAL -> R.string.sort_alphabetical
            AppPreferences.Sort.MOST_USED -> R.string.sort_most_used
            AppPreferences.Sort.RECENT -> R.string.sort_recent
        }
        sortButton.text = getString(R.string.all_apps_sort, getString(label))
    }

    private fun showAppMenu(app: LaunchableApp) {
        val inDock = DockPreferences.contains(this, app.packageName)
        val options = arrayOf(
            getString(if (inDock) R.string.app_menu_dock_remove else R.string.app_menu_dock_add),
            getString(R.string.app_menu_category),
            getString(R.string.app_menu_banner),
            getString(R.string.app_menu_hide),
        )
        tvDialog()
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleDock(app, inDock)
                    1 -> chooseCategory(app)
                    2 -> startActivity(WallpaperPickerActivity.bannerIntent(this, app.packageName, app.label))
                    3 -> hide(app)
                }
            }
            .show()
    }

    private fun chooseCategory(app: LaunchableApp) {
        val current = AppCategory.of(this, app.packageName)
        val labels = AppCategory.entries.map { (if (it == current) "✓  " else "     ") + getString(it.labelRes) }
        tvDialog()
            .setTitle(getString(R.string.app_menu_category_title, app.label))
            .setItems(labels.toTypedArray()) { _, which ->
                AppPreferences.setCategory(this, app.packageName, AppCategory.entries[which].id)
                render()
                tiles[app.packageName]?.requestFocus()
            }
            .show()
    }

    private fun toggleDock(app: LaunchableApp, inDock: Boolean) {
        if (inDock) {
            DockPreferences.removeApp(this, app.packageName)
            toast(getString(R.string.app_dock_removed, app.label))
        } else {
            DockPreferences.addApp(this, app.packageName)
            toast(getString(R.string.app_dock_added, app.label))
        }
    }

    private fun hide(app: LaunchableApp) {
        AppPreferences.hide(this, app.packageName)
        toast(getString(R.string.app_hidden, app.label))
        apps = apps - app
        tiles.remove(app.packageName)
        render()
        firstTile()?.requestFocus()
    }

    private fun launch(app: LaunchableApp) {
        if (!AppLauncher.launch(this, app.packageName)) toast(getString(R.string.app_not_installed, app.label))
    }

    private companion object {
        const val COLUMNS = 6
        const val HEADER_SIZE_SP = 18f
    }
}
