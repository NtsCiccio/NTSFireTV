// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.system

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import it.ntsciccio.ntsfiretv.apps.AppCatalog
import it.ntsciccio.ntsfiretv.apps.AppPreferences
import it.ntsciccio.ntsfiretv.apps.CustomBanners
import it.ntsciccio.ntsfiretv.home.DockPreferences
import it.ntsciccio.ntsfiretv.home.HomePreferences
import it.ntsciccio.ntsfiretv.home.ThemeStore
import it.ntsciccio.ntsfiretv.media.StorageVolumes
import it.ntsciccio.ntsfiretv.wallpaper.WallpaperRepository
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Settings backup to a `NTSFireTV/backup/` folder: theme and user themes, dock,
 * hidden apps, wallpaper (including the images), custom banners, home options (memory meter, child lock).
 * The weather city is left out: the backup lives in shared storage and on USB, readable by other apps.
 *
 * It is saved to the stick's storage and, if present, also to every USB drive; restore
 * prefers the USB drive (useful after a stick reset). Requires the storage permission.
 * Video positions are not included. Call off the UI thread.
 */
object SettingsBackup {

    private const val TAG = "NTSFireTV"
    private const val FOLDER = "NTSFireTV/backup"
    private const val SETTINGS_FILE = "impostazioni.json"
    private const val MAX_SETTINGS_BYTES = 256 * 1024

    /** SharedPreferences that are saved (video positions are left out). */
    private val PREFERENCES = listOf(
        ThemeStore.PREFS_NAME, DockPreferences.PREFS_NAME, AppPreferences.PREFS_NAME, WallpaperRepository.PREFS_NAME,
        HomePreferences.PREFS_NAME,
    )

    /** Internal files copied: chosen wallpapers and banners. */
    private val FILES = listOf(WallpaperRepository.DAY_NAME, WallpaperRepository.NIGHT_NAME, CustomBanners.FOLDER)

    /** @return the folders the backup was saved to (empty if it failed). */
    fun export(context: Context): List<File> {
        val targets = listOf(sharedFolder()) + StorageVolumes.removable(context)
            .map { File(it.root, FOLDER) }
        return targets.filter { folder ->
            try {
                folder.deleteRecursively()
                folder.mkdirs()
                File(folder, SETTINGS_FILE).writeText(preferencesToJson(context).toString(2))
                FILES.forEach { name -> copyIfExists(File(context.filesDir, name), File(folder, name)) }
                copyIfExists(ThemeStore.folder, File(folder, "themes"))
                true
            } catch (e: IOException) {
                Log.w(TAG, "Backup in ${folder.absolutePath} non riuscito", e)
                false
            }
        }
    }

    /** Backup folder to restore from: first on USB drives, then in the stick's storage. */
    fun findBackup(context: Context): File? {
        val candidates = StorageVolumes.removable(context).map { File(it.root, FOLDER) } + sharedFolder()
        return candidates.firstOrNull { File(it, SETTINGS_FILE).isFile }
    }

    fun restore(context: Context, folder: File): Boolean = try {
        val settings = File(folder, SETTINGS_FILE)
        if (settings.length() > MAX_SETTINGS_BYTES) throw IOException("File di backup troppo grande")
        val json = JSONObject(settings.readText())
        PREFERENCES.forEach { name ->
            json.optJSONObject(name)?.let { restorePreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), it) }
        }
        restoreImages(context, folder)
        copyIfExists(File(folder, "themes"), ThemeStore.folder)
        // Reload everything that is cached in memory
        ThemeStore.reload()
        CustomBanners.markChanged()
        AppCatalog.invalidate()
        true
    } catch (e: IOException) {
        Log.w(TAG, "Ripristino non riuscito", e)
        false
    } catch (e: JSONException) {
        Log.w(TAG, "File di backup non valido", e)
        false
    }

    /**
     * Wallpapers and banners from the backup: decoded and saved again (with the size limits),
     * never copied as-is, so a malformed image can't break the home.
     */
    private fun restoreImages(context: Context, folder: File) {
        val wallpapers = WallpaperRepository(context)
        listOf(WallpaperRepository.DAY_NAME, WallpaperRepository.NIGHT_NAME).forEach { name ->
            File(context.filesDir, name).delete()
            val source = File(folder, name)
            if (source.isFile) wallpapers.restoreWallpaperImage(source, name)
        }
        File(context.filesDir, CustomBanners.FOLDER).deleteRecursively()
        File(folder, CustomBanners.FOLDER).listFiles().orEmpty()
            .filter { it.isFile && it.extension == "jpg" && CustomBanners.isValidPackageName(it.nameWithoutExtension) }
            .forEach { wallpapers.restoreBannerImage(it, it.nameWithoutExtension) }
    }

    private fun sharedFolder(): File = File(Environment.getExternalStorageDirectory(), FOLDER)

    private fun preferencesToJson(context: Context): JSONObject = JSONObject().apply {
        PREFERENCES.forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                val entry = when (value) {
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                    else -> null
                }
                if (entry != null) values.put(key, entry)
            }
            put(name, values)
        }
    }

    private fun restorePreferences(prefs: SharedPreferences, values: JSONObject) {
        val editor = prefs.edit().clear()
        values.keys().forEach { key ->
            val entry = values.optJSONObject(key) ?: return@forEach
            when (entry.optString("t")) {
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "i" -> editor.putInt(key, entry.optInt("v"))
                "l" -> editor.putLong(key, entry.optLong("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "s" -> editor.putString(key, entry.optString("v"))
                "set" -> {
                    val array = entry.optJSONArray("v") ?: JSONArray()
                    editor.putStringSet(key, (0 until array.length()).map { array.optString(it) }.toSet())
                }
            }
        }
        editor.commit()
    }

    private fun copyIfExists(source: File, target: File) {
        if (!source.exists()) return
        source.copyRecursively(target, overwrite = true)
    }
}
