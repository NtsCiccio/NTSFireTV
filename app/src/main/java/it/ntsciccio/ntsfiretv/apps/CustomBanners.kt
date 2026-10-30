// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.apps

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File

/**
 * User-chosen banners for apps that don't have one (or have an ugly one).
 * They are internal JPEGs in `files/banners/<package>.jpg`, created by the image picker;
 * [version] changes on every edit, so the grids know when to reload.
 */
object CustomBanners {

    @Volatile
    var version = 0
        private set

    fun file(context: Context, packageName: String): File =
        File(File(context.filesDir, FOLDER), "$packageName.jpg")

    /**
     * The app's custom banner, or null. Banners saved by the app are 384×216:
     * a larger file (e.g. from a tampered backup) is ignored instead of exhausting memory.
     */
    fun load(context: Context, packageName: String): Drawable? {
        val file = file(context, packageName)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth !in 1..MAX_SIDE || bounds.outHeight !in 1..MAX_SIDE) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    /** Valid file name for a banner: an Android package name. */
    fun isValidPackageName(name: String): Boolean = name.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))

    fun remove(context: Context, packageName: String) {
        if (file(context, packageName).delete()) markChanged()
    }

    fun markChanged() {
        version++
        AppCatalog.trimVisuals()
    }

    /** Banner folder in `files/` (also used by the backup). */
    const val FOLDER = "banners"

    private const val MAX_SIDE = 1024
}
