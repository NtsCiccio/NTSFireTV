// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.content.Context
import android.os.Environment
import android.util.Log
import it.ntsciccio.ntsfiretv.system.StoragePermission
import it.ntsciccio.ntsfiretv.wallpaper.WallpaperRepository
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Available themes and the chosen theme.
 *
 * Built-in: "Predefinito" (Default). User: `*.json` files in `/sdcard/NTSFireTV/themes/`,
 * created with the on-TV editor or copied from the computer (`./gradlew pushTheme`).
 * The current theme stays in memory; [version] changes on every choice, so the home knows when to redraw.
 */
object ThemeStore {

    private const val TAG = "NTSFireTV"
    const val PREFS_NAME = "theme"
    private const val KEY_THEME_ID = "theme_id"
    private const val USER_PREFIX = "user/"
    private const val FOLDER = "NTSFireTV/themes"
    private const val MAX_THEME_BYTES = 64L * 1024

    @Volatile
    private var cached: ThemeSpec? = null

    @Volatile
    var version = 0
        private set

    val folder: File
        get() = File(Environment.getExternalStorageDirectory(), FOLDER)

    fun current(context: Context): ThemeSpec {
        cached?.let { return it }
        val id: String? = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_THEME_ID, null)
        val theme = id?.let { find(context, it) } ?: ThemeSpec.DEFAULT
        cached = theme
        return theme
    }

    /** Reloads the theme from preferences (after a backup restore). */
    fun reload() {
        cached = null
        version++
    }

    /** Activates [theme] and its wallpaper; if the theme has none, the user-chosen wallpaper comes back. */
    fun select(context: Context, theme: ThemeSpec) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_THEME_ID, theme.id).apply()
        cached = theme
        version++
        WallpaperRepository(context.applicationContext).setUseThemeWallpaper(theme.wallpaper != null)
    }

    /** Built-in + readable user themes, sorted by name. */
    fun list(context: Context): List<ThemeSpec> = ThemeSpec.BUILT_IN + userThemes(context)

    fun find(context: Context, id: String): ThemeSpec? {
        ThemeSpec.BUILT_IN.firstOrNull { it.id == id }?.let { return it }
        val stem = id.removePrefix(USER_PREFIX)
        // Only names like those created by slugify: a tampered id cannot escape the themes folder
        return if (id.startsWith(USER_PREFIX) && isValidStem(stem)) read(fileFor(stem), id) else null
    }

    /**
     * Saves [theme] as a JSON file. A new (or built-in) theme gets a file name derived
     * from the theme name; a user theme overwrites its own file. @return the saved theme, or null.
     */
    fun save(context: Context, theme: ThemeSpec): ThemeSpec? {
        if (!StoragePermission.isGranted(context)) return null
        val existing = theme.id.removePrefix(USER_PREFIX)
        val stem = if (theme.id.startsWith(USER_PREFIX) && isValidStem(existing)) existing else uniqueStem(theme.name)
        val saved = theme.copy(id = USER_PREFIX + stem)
        return try {
            folder.mkdirs()
            fileFor(stem).writeText(saved.toJson())
            saved
        } catch (e: IOException) {
            Log.w(TAG, "Salvataggio tema fallito", e)
            null
        }
    }

    private fun userThemes(context: Context): List<ThemeSpec> {
        if (!StoragePermission.isGranted(context)) return emptyList()
        return folder.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() == "json" }
            .filter { isValidStem(it.nameWithoutExtension) }
            .mapNotNull { read(it, USER_PREFIX + it.nameWithoutExtension) }
            .sortedBy { it.name.lowercase() }
    }

    private fun read(file: File, id: String): ThemeSpec? = try {
        // A theme is a few hundred bytes: huge files (even tampered ones) are ignored
        if (file.isFile && file.length() <= MAX_THEME_BYTES) ThemeSpec.fromJson(id, file.readText()) else null
    } catch (e: IOException) {
        null
    }

    private fun fileFor(stem: String): File = File(folder, "$stem.json")

    internal fun isValidStem(stem: String): Boolean = stem.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*"))

    /** Safe file name for a theme: lowercase, digits and hyphens ("Notte a Tokyo!" → "notte-a-tokyo"). */
    internal fun slugify(name: String): String =
        name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "tema" }

    private fun uniqueStem(name: String): String {
        val base = slugify(name)
        var stem = base
        var counter = 2
        while (fileFor(stem).exists()) stem = "$base-${counter++}"
        return stem
    }
}
