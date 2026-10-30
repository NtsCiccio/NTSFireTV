// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.wallpaper

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import it.ntsciccio.ntsfiretv.R
import it.ntsciccio.ntsfiretv.home.toast
import it.ntsciccio.ntsfiretv.home.tvDialog
import it.ntsciccio.ntsfiretv.home.OverlayActivity
import it.ntsciccio.ntsfiretv.apps.CustomBanners
import it.ntsciccio.ntsfiretv.home.DockTileView
import it.ntsciccio.ntsfiretv.home.addTileInGrid
import it.ntsciccio.ntsfiretv.home.ThemeStore
import it.ntsciccio.ntsfiretv.home.ThemeStyler
import it.ntsciccio.ntsfiretv.system.StoragePermission
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Image picker, in two modes:
 * - **wallpaper** (Menu key on the home, or Settings): "Predefinito" (Default), "Nero" (Black), then the images;
 *   an image can be used always or only at night;
 * - **app banner** ([bannerIntent], Menu key in "Tutte le app"): "Originale" (Original), then the images.
 *
 * Images come from the user folder and USB sticks; the list and thumbnails
 * are loaded in the background, one thumbnail at a time.
 */
class WallpaperPickerActivity : OverlayActivity() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var repository: WallpaperRepository
    private lateinit var grid: GridLayout
    private lateinit var message: TextView
    private var applying = false

    /** Package of the app whose banner is being chosen; null = wallpaper mode. */
    private var bannerPackage: String? = null

    /** The current theme has a generated wallpaper: wallpaper mode shows the "Sfondo del tema" tile. */
    private var themeHasWallpaper = false

    /** Tiles that receive a thumbnail, by reference (no index arithmetic). */
    private var themeTile: DockTileView? = null
    private var defaultTile: DockTileView? = null
    private val imageTiles = mutableListOf<DockTileView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tile_grid)
        ThemeStyler.styleGridScreen(this)
        grid = findViewById(R.id.tile_grid)
        message = findViewById(R.id.grid_message)
        repository = WallpaperRepository(applicationContext)

        bannerPackage = intent.getStringExtra(EXTRA_BANNER_PACKAGE)
        themeHasWallpaper = ThemeStore.current(this).wallpaper != null
        val title = findViewById<TextView>(R.id.grid_title)
        if (bannerPackage == null) {
            title.setText(R.string.wallpaper_title)
        } else {
            title.text = getString(R.string.banner_title, intent.getStringExtra(EXTRA_BANNER_LABEL).orEmpty())
        }

        if (StoragePermission.isGranted(this)) load(withFiles = true) else StoragePermission.request(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != StoragePermission.REQUEST_CODE) return
        load(withFiles = StoragePermission.isGrantedResult(requestCode, grantResults))
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun load(withFiles: Boolean) {
        executor.execute {
            val files: List<File> = if (withFiles) repository.findImages() else emptyList()
            runOnUiThread { if (!isDestroyed) showTiles(files, withFiles) }

            // Thumbnails one at a time; the tiles already exist (runOnUiThread runs in order)
            if (bannerPackage == null) {
                if (themeHasWallpaper) publishThumbnail({ themeTile }, repository.loadThemeThumbnail())
                publishThumbnail({ defaultTile }, repository.loadDefaultThumbnail())
            }
            files.forEachIndexed { index, file ->
                if (Thread.currentThread().isInterrupted) return@execute
                publishThumbnail({ imageTiles.getOrNull(index) }, repository.loadThumbnail(file))
            }
        }
    }

    private fun showTiles(files: List<File>, hasPermission: Boolean) {
        grid.removeAllViews()
        imageTiles.clear()
        val packageName = bannerPackage

        if (packageName == null) {
            if (themeHasWallpaper) themeTile = addTile(getString(R.string.wallpaper_theme)) { applyNow { repository.setUseThemeWallpaper(true) } }
            defaultTile = addTile(getString(R.string.wallpaper_default)) { applyNow { repository.reset() } }
            addTile(getString(R.string.wallpaper_black)) { applyNow { repository.setBlack() } }
                .setVisual(ColorDrawable(0xFF000000.toInt()), isBanner = true)
        } else {
            addTile(getString(R.string.banner_original)) { applyNow { CustomBanners.remove(this, packageName) } }
        }
        files.forEach { file -> imageTiles += addTile(file.nameWithoutExtension) { onImageChosen(file) } }

        val text = when {
            !hasPermission -> getString(R.string.wallpaper_no_permission)
            files.isEmpty() -> getString(R.string.wallpaper_empty, repository.userFolder.absolutePath)
            packageName == null && repository.hasNightWallpaper -> getString(R.string.wallpaper_night_active)
            else -> null
        }
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
        grid.getChildAt(0)?.requestFocus()
    }

    private fun addTile(label: String, onClick: () -> Unit): DockTileView {
        val tile = DockTileView(this).apply {
            bind(label)
            setOnClickListener { onClick() }
        }
        grid.addTileInGrid(tile)
        return tile
    }

    /** Shows [bitmap] on the tile returned by [tile], evaluated on the UI thread. */
    private fun publishThumbnail(tile: () -> DockTileView?, bitmap: Bitmap?) {
        if (bitmap == null) return
        runOnUiThread {
            if (!isDestroyed) tile()?.setVisual(BitmapDrawable(resources, bitmap), isBanner = true)
        }
    }

    private fun onImageChosen(file: File) {
        if (applying) return
        val packageName = bannerPackage
        if (packageName != null) {
            applyInBackground { repository.applyBanner(packageName, file) }
            return
        }
        // Wallpaper: always, or only at night
        val choices = arrayOf(getString(R.string.wallpaper_slot_always), getString(R.string.wallpaper_slot_night))
        tvDialog()
            .setTitle(file.nameWithoutExtension)
            .setItems(choices) { _, which ->
                val slot = if (which == 0) WallpaperRepository.Slot.ALWAYS else WallpaperRepository.Slot.NIGHT
                applyInBackground { repository.apply(file, slot) }
            }
            .show()
    }

    private fun applyInBackground(action: () -> Boolean) {
        applying = true
        toast(R.string.wallpaper_applying)
        executor.execute {
            val ok = action()
            runOnUiThread {
                applying = false
                if (ok) finish() else toast(R.string.wallpaper_error, long = true)
            }
        }
    }

    /** Immediate choices ("Predefinito", "Nero", "Sfondo del tema", "Originale"): apply and close. */
    private fun applyNow(action: () -> Unit) {
        if (applying) return
        action()
        finish()
    }

    companion object {
        private const val EXTRA_BANNER_PACKAGE = "banner_package"
        private const val EXTRA_BANNER_LABEL = "banner_label"

        /** Picker in banner mode for app [packageName]. */
        fun bannerIntent(context: Context, packageName: String, label: String): Intent =
            Intent(context, WallpaperPickerActivity::class.java)
                .putExtra(EXTRA_BANNER_PACKAGE, packageName)
                .putExtra(EXTRA_BANNER_LABEL, label)
    }
}
