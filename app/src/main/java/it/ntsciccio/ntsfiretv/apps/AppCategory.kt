// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.apps

import android.content.Context
import it.ntsciccio.ntsfiretv.R

/**
 * Categories of "Tutte le app" (All apps), shown as sections in this order.
 * Each app has the category chosen by the user (Menu key ≡) or one guessed from the package name.
 */
enum class AppCategory(val id: String, val labelRes: Int) {
    VIDEO("video", R.string.category_video),
    MUSIC("music", R.string.category_music),
    GAMES("games", R.string.category_games),
    TOOLS("tools", R.string.category_tools),
    OTHER("other", R.string.category_other);

    companion object {

        /** Known packages (prefixes) and their category; everything else ends up in "Altre" (Other). */
        private val KNOWN = listOf(
            "com.netflix" to VIDEO, "com.amazon.avod" to VIDEO, "com.amazon.firebat" to VIDEO,
            "com.google.android.youtube" to VIDEO, "com.amazon.firetv.youtube" to VIDEO, "org.xbmc.kodi" to VIDEO,
            "org.videolan.vlc" to VIDEO, "com.disney" to VIDEO, "com.plexapp" to VIDEO, "tv.pluto" to VIDEO,
            "com.tubitv" to VIDEO, "com.dazn" to VIDEO, "it.rai" to VIDEO, "it.mediaset" to VIDEO, "com.apple.atve" to VIDEO,
            "com.spotify" to MUSIC, "deezer" to MUSIC, "com.aspiro.tidal" to MUSIC, "com.amazon.mp3" to MUSIC,
            "com.limelight" to GAMES, "com.valvesoftware" to GAMES, "com.retroarch" to GAMES, "com.nvidia" to GAMES,
            "com.phlox" to TOOLS, "com.esaba.downloader" to TOOLS, "com.amazon.venezia" to TOOLS,
            "com.lonelycatgames" to TOOLS, "com.estrongs" to TOOLS, "com.amazon.tv.settings" to TOOLS,
        )

        fun of(context: Context, packageName: String): AppCategory {
            val chosen = AppPreferences.category(context, packageName)
            return entries.firstOrNull { it.id == chosen } ?: guess(packageName)
        }

        /** Category from the package name, ignoring user choices. */
        internal fun guess(packageName: String): AppCategory =
            KNOWN.firstOrNull { (prefix, _) -> packageName.startsWith(prefix) || packageName.contains(".$prefix") }?.second
                ?: OTHER
    }
}
