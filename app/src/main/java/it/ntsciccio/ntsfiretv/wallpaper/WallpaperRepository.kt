// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Environment
import android.util.Log
import it.ntsciccio.ntsfiretv.R
import it.ntsciccio.ntsfiretv.apps.CustomBanners
import it.ntsciccio.ntsfiretv.home.ThemeStore
import it.ntsciccio.ntsfiretv.home.WallpaperSpec
import it.ntsciccio.ntsfiretv.media.StorageVolumes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Calendar

/**
 * Home wallpapers (and custom banner images, which use the same cropping).
 *
 * Priority: the **theme wallpaper** (generated from the colors, active when a theme that has one is chosen)
 * until the user picks an image, "Nero" (Black) or "Predefinito" (Default) from the picker.
 *
 * The wallpaper is an internal 1920×1080 JPEG copy, so it stays even if the USB stick is removed:
 * `wallpaper.jpg` (day, or always) and, optionally, `wallpaper_night.jpg` (from 20:00 to 7:00).
 * Alternatively "Nero" (black screen, ideal for OLED). Without copies `wallpaper_default` is used.
 * All methods that decode images must be called off the UI thread.
 */
class WallpaperRepository(private val context: Context) {

    /** Which time of day the image is chosen for. */
    enum class Slot { ALWAYS, NIGHT }

    private val dayFile = File(context.filesDir, DAY_NAME)
    private val nightFile = File(context.filesDir, NIGHT_NAME)
    private val themeFile = File(context.filesDir, THEME_NAME)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isBlack: Boolean
        get() = prefs.getBoolean(KEY_BLACK, false)

    val hasNightWallpaper: Boolean
        get() = nightFile.exists()

    /** Theme wallpaper to show now, or null (theme without wallpaper, or the user chose their own). */
    val activeThemeWallpaper: WallpaperSpec?
        get() = if (prefs.getBoolean(KEY_USE_THEME, false)) ThemeStore.current(context).wallpaper else null

    /** Enables or disables the current theme's wallpaper (theme choice, or the "Sfondo del tema" tile). */
    fun setUseThemeWallpaper(use: Boolean) {
        prefs.edit().putBoolean(KEY_USE_THEME, use).apply()
    }

    /**
     * Identifies the wallpaper to show now (takes day/night into account):
     * the home compares it to know whether to reload it.
     */
    fun currentKey(): String = when {
        activeThemeWallpaper != null -> "theme:${themeKey()}"
        isBlack -> "black"
        isNight() && nightFile.exists() -> "night:${nightFile.lastModified()}"
        dayFile.exists() -> "day:${dayFile.lastModified()}"
        else -> "default"
    }

    /** Wallpaper folder in shared storage (`/sdcard/NTSFireTV/wallpapers`). */
    val userFolder: File
        get() = File(Environment.getExternalStorageDirectory(), USER_FOLDER)

    /** Wallpaper to show now at the TV's resolution; null if "Nero" is active. */
    fun loadCurrent(): Bitmap? {
        activeThemeWallpaper?.let { return loadThemeWallpaper(it) }
        if (isBlack) return null
        val file = if (isNight() && nightFile.exists()) nightFile else dayFile
        if (file.exists()) {
            decodeFile(file, TV_WIDTH, TV_HEIGHT)?.let { return it }
        }
        return decodeDefault(TV_WIDTH, TV_HEIGHT)
    }

    fun loadDefaultThumbnail(): Bitmap? = decodeDefault(THUMB_WIDTH, THUMB_HEIGHT)

    /** Thumbnail of the current theme's wallpaper (even if not active), or null if the theme has none. */
    fun loadThemeThumbnail(): Bitmap? =
        ThemeStore.current(context).wallpaper?.let { ThemeWallpaperRenderer.render(it, THUMB_WIDTH, THUMB_HEIGHT) }

    /** Theme wallpaper: generated once and cached until theme and wallpaper change. */
    private fun loadThemeWallpaper(spec: WallpaperSpec): Bitmap? {
        val key = themeKey()
        if (themeFile.exists() && prefs.getString(KEY_THEME_CACHE, null) == key) {
            decodeFile(themeFile, THEME_WIDTH, THEME_HEIGHT, Bitmap.Config.ARGB_8888)?.let { return it }
        }
        // Half resolution: they are just blurry gradients, scaled up to full screen they look identical
        // but take 2 MB instead of 8 (app and graphics memory)
        val bitmap = ThemeWallpaperRenderer.render(spec, THEME_WIDTH, THEME_HEIGHT)
        try {
            // PNG: no JPEG artifacts in the gradients
            FileOutputStream(themeFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            prefs.edit().putString(KEY_THEME_CACHE, key).apply()
        } catch (e: IOException) {
            Log.w(TAG, "Cache dello sfondo del tema non salvata", e)
        }
        return bitmap
    }

    private fun themeKey(): String {
        val theme = ThemeStore.current(context)
        return "${theme.id}:${theme.wallpaper.hashCode()}"
    }

    /**
     * Thumbnail of an image, saved in `cache/thumbs/`: later openings of the picker
     * read a JPEG of a few KB instead of decoding the original again (slow from a USB stick).
     */
    fun loadThumbnail(file: File): Bitmap? {
        val cached = thumbnailCacheFile(file)
        if (cached.exists()) BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }

        val bitmap = decodeFile(file, THUMB_WIDTH, THUMB_HEIGHT) ?: return null
        try {
            cached.parentFile?.mkdirs()
            FileOutputStream(cached).use { bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
        } catch (e: IOException) {
            Log.w(TAG, "Miniatura non salvata in cache", e)
        }
        return bitmap
    }

    /** Name tied to path, date and size: if the image changes, the thumbnail is regenerated. */
    private fun thumbnailCacheFile(file: File): File {
        val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}".hashCode()
        return File(File(context.cacheDir, THUMB_FOLDER), "${Integer.toHexString(key)}.jpg")
    }

    /**
     * Available images: user folder (created if missing) + root and `wallpapers/` folder
     * of each USB volume. Requires the storage read permission.
     */
    fun findImages(): List<File> {
        val folders = mutableListOf<File>()
        val user = userFolder
        if (!user.exists() && !user.mkdirs()) Log.w(TAG, "Impossibile creare ${user.absolutePath}")
        folders += user

        StorageVolumes.removable(context).forEach { volume ->
            folders += volume.root
            folders += File(volume.root, "wallpapers")
        }

        return folders
            .flatMap { folder -> folder.listFiles().orEmpty().filter(::isImage) }
            .distinctBy { it.absolutePath }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Sets [file] as wallpaper: [Slot.ALWAYS] replaces the wallpaper (and removes the night one),
     * [Slot.NIGHT] uses it only from 20:00 to 7:00. @return false if the image can't be read or saved.
     */
    fun apply(file: File, slot: Slot): Boolean {
        val target = if (slot == Slot.NIGHT) nightFile else dayFile
        if (!saveCropped(file, target, TV_WIDTH, TV_HEIGHT)) return false
        if (slot == Slot.ALWAYS) nightFile.delete()
        setMode(black = false)
        return true
    }

    /** Black screen instead of the image. */
    fun setBlack() {
        setMode(black = true)
    }

    /** Reverts to the default wallpaper (also removes the night one and "Nero"). */
    fun reset() {
        dayFile.delete()
        nightFile.delete()
        setMode(black = false)
    }

    /**
     * Restore from backup: [source] is decoded (with the memory limits) and saved again
     * at 1920×1080 as wallpaper [name] ([DAY_NAME] or [NIGHT_NAME]), without touching the preferences.
     */
    fun restoreWallpaperImage(source: File, name: String): Boolean =
        saveCropped(source, File(context.filesDir, name), TV_WIDTH, TV_HEIGHT)

    /** Restore of a banner from backup, re-saved at tile size. */
    fun restoreBannerImage(source: File, packageName: String): Boolean {
        val target = CustomBanners.file(context, packageName)
        target.parentFile?.mkdirs()
        return saveCropped(source, target, BANNER_WIDTH, BANNER_HEIGHT)
    }

    /** A user choice (image, "Nero", "Predefinito") replaces the theme wallpaper. */
    private fun setMode(black: Boolean) {
        prefs.edit().putBoolean(KEY_BLACK, black).putBoolean(KEY_USE_THEME, false).apply()
    }

    /** Uses [file] as the banner of app [packageName] (16:9, at tile size). */
    fun applyBanner(packageName: String, file: File): Boolean {
        val target = CustomBanners.file(context, packageName)
        target.parentFile?.mkdirs()
        val saved = saveCropped(file, target, BANNER_WIDTH, BANNER_HEIGHT)
        if (saved) CustomBanners.markChanged()
        return saved
    }

    /** Crops [source] to [width]×[height] (centerCrop, EXIF rotation) and saves a JPEG to [target]. */
    private fun saveCropped(source: File, target: File, width: Int, height: Int): Boolean {
        val decoded: Bitmap = decodeFile(source, width, height) ?: return false
        val oriented: Bitmap = applyExifRotation(source, decoded)
        val output: Bitmap = crop(oriented, width, height)
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()

        val tmp = File(target.path + ".tmp")
        val saved = try {
            FileOutputStream(tmp).use { output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } catch (e: IOException) {
            Log.w(TAG, "Salvataggio immagine fallito", e)
            false
        } finally {
            output.recycle()
        }
        return saved && tmp.renameTo(target)
    }

    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    private fun isImage(file: File): Boolean =
        file.isFile && !file.name.startsWith(".") && file.extension.lowercase() in IMAGE_EXTENSIONS

    /** Decodes with `inSampleSize` set to the largest power of 2 that stays ≥ [minWidth]×[minHeight]. */
    private fun decodeFile(
        file: File,
        minWidth: Int,
        minHeight: Int,
        config: Bitmap.Config = Bitmap.Config.RGB_565,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = decodeOptions(bounds.outWidth, bounds.outHeight, minWidth, minHeight, config)
        return try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Immagine troppo grande: ${file.name}")
            null
        }
    }

    private fun decodeDefault(minWidth: Int, minHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeResource(context.resources, R.drawable.wallpaper_default, bounds)
        val options = decodeOptions(bounds.outWidth, bounds.outHeight, minWidth, minHeight, Bitmap.Config.RGB_565)
        return BitmapFactory.decodeResource(context.resources, R.drawable.wallpaper_default, options)
    }

    private fun decodeOptions(
        width: Int,
        height: Int,
        minWidth: Int,
        minHeight: Int,
        config: Bitmap.Config,
    ): BitmapFactory.Options {
        var sample = 1
        while (width / (sample * 2) >= minWidth && height / (sample * 2) >= minHeight) sample *= 2
        // Memory cap: even a huge, or very long and narrow, image stays below MAX_DECODE_BYTES
        val bytesPerPixel = if (config == Bitmap.Config.ARGB_8888) 4L else 2L
        while ((width / sample).toLong() * (height / sample) * bytesPerPixel > MAX_DECODE_BYTES) sample *= 2
        return BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            // RGB_565: half the memory; on photos gradients show no visible banding.
            // Only the theme wallpaper (pure gradients) uses ARGB_8888, at half resolution
            inPreferredConfig = config
        }
    }

    /** Phone photos store rotation in EXIF data instead of in the pixels. */
    private fun applyExifRotation(file: File, bitmap: Bitmap): Bitmap {
        val degrees = try {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: IOException) {
            0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun crop(source: Bitmap, width: Int, height: Int): Bitmap {
        val targetRatio = width.toFloat() / height
        val sourceRatio = source.width.toFloat() / source.height
        val area = if (sourceRatio > targetRatio) {
            val cropWidth = (source.height * targetRatio).toInt()
            val left = (source.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, source.height)
        } else {
            val cropHeight = (source.width / targetRatio).toInt()
            val top = (source.height - cropHeight) / 2
            Rect(0, top, source.width, top + cropHeight)
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        Canvas(output).drawBitmap(source, area, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    companion object {
        /** Preferences and internal files of the chosen wallpapers (also used by the backup). */
        const val PREFS_NAME = "wallpaper"
        const val DAY_NAME = "wallpaper.jpg"
        const val NIGHT_NAME = "wallpaper_night.jpg"

        private const val TAG = "NTSFireTV"
        /** Maximum memory for a single decoded image (1920×1080 ARGB ≈ 8 MB). */
        private const val MAX_DECODE_BYTES = 16L * 1024 * 1024
        private const val THEME_NAME = "wallpaper_theme_540.png"
        private const val KEY_USE_THEME = "use_theme"
        private const val KEY_THEME_CACHE = "theme_cache"
        private const val KEY_BLACK = "black"
        private const val USER_FOLDER = "NTSFireTV/wallpapers"
        private const val NIGHT_START_HOUR = 20
        private const val NIGHT_END_HOUR = 7
        private const val TV_WIDTH = 1920
        private const val TV_HEIGHT = 1080
        private const val THEME_WIDTH = 960
        private const val THEME_HEIGHT = 540
        private const val THUMB_FOLDER = "thumbs"
        private const val THUMB_QUALITY = 85
        private const val THUMB_WIDTH = 256
        private const val THUMB_HEIGHT = 144
        private const val BANNER_WIDTH = 384
        private const val BANNER_HEIGHT = 216
        private const val JPEG_QUALITY = 90
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
