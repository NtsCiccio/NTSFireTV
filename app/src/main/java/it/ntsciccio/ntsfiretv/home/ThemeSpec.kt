// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.graphics.Typeface
import org.json.JSONException
import org.json.JSONObject

/**
 * A visual theme: colors, corners and fonts of tiles, panels, clock and titles.
 *
 * User themes are JSON files (see docs/DEVELOPMENT.md, Themes section); missing keys
 * take the value of the "Predefinito" (Default) theme, so a file can contain only what changes.
 *
 * @property id "default" for the built-in theme, "user/<file name>" for user themes.
 */
data class ThemeSpec(
    val id: String,
    val name: String,
    /** Main color: list selection, widget buttons. */
    val accent: Int,
    val tile: Int,
    val tileFocused: Int,
    val tileBorder: Int,
    val focusBorder: Int,
    val cornerRadiusDp: Int,
    /** "In riproduzione" (Now playing) widget panel. */
    val panel: Int,
    /** Scrim of the screens overlaid on the home. */
    val overlay: Int,
    val textSecondary: Int,
    val clockFont: String,
    val clockSizeSp: Int,
    /** Clock shadow/glow; transparent = none. */
    val clockShadow: Int,
    val titleFont: String,
    /** Tile shape: see [TILE_STYLES]. */
    val tileStyle: String,
    /** Focus with double border (white inside, accent outside): visible on any background. */
    val doubleFocus: Boolean,
    /** Wallpaper generated from the theme colors; null = keep the wallpaper chosen by the user. */
    val wallpaper: WallpaperSpec? = null,
    /** Dark gradient at the bottom behind the dock (useless on black backgrounds). */
    val homeScrim: Boolean = true,
) {

    val isBuiltIn: Boolean get() = BUILT_IN.any { it.id == id }

    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("accent", colorToHex(accent))
        put("tile", colorToHex(tile))
        put("tileFocused", colorToHex(tileFocused))
        put("tileBorder", colorToHex(tileBorder))
        put("focusBorder", colorToHex(focusBorder))
        put("cornerRadius", cornerRadiusDp)
        put("panel", colorToHex(panel))
        put("overlay", colorToHex(overlay))
        put("textSecondary", colorToHex(textSecondary))
        put("clockFont", clockFont)
        put("clockSize", clockSizeSp)
        put("clockShadow", colorToHex(clockShadow))
        put("titleFont", titleFont)
        put("tileStyle", tileStyle)
        put("doubleFocus", doubleFocus)
        put("homeScrim", homeScrim)
        wallpaper?.let { put("wallpaper", it.toJson()) }
    }.toString(2)

    companion object {

        /** Fonts available on Fire OS 6: name in the JSON → name shown in the editor. */
        val FONTS = linkedMapOf(
            "light" to "Sottile", "regular" to "Normale", "medium" to "Medio", "bold" to "Grassetto",
            "black" to "Nero", "condensed" to "Condensato", "casual" to "Informale",
        )

        /**
         * Tile shapes: `modern` (theme corners), `capsule` (pill-shaped), `classic` (square,
         * no border), `premium` (colored glow on focus).
         */
        val TILE_STYLES = linkedMapOf(
            "modern" to "Moderno", "capsule" to "Capsula", "classic" to "Classico", "premium" to "Premium (alone)",
        )

        val DEFAULT = ThemeSpec(
            id = "default",
            name = "Predefinito",
            accent = 0xFFFFFFFF.toInt(),
            tile = 0x991C1C1E.toInt(),
            tileFocused = 0xCC1C1C1E.toInt(),
            tileBorder = 0x40FFFFFF,
            focusBorder = 0xFFFFFFFF.toInt(),
            cornerRadiusDp = 12,
            panel = 0xCC1C1C1E.toInt(),
            overlay = 0xD9000000.toInt(),
            textSecondary = 0xB3FFFFFF.toInt(),
            clockFont = "light",
            clockSizeSp = 40,
            clockShadow = 0xE6000000.toInt(),
            titleFont = "light",
            tileStyle = "modern",
            doubleFocus = true,
        )

        /** "Predefinito" (Default) + themes inspired by desktop palettes (see [BuiltInThemes]). Lazy: avoids initialization cycles. */
        val BUILT_IN: List<ThemeSpec> by lazy { listOf(DEFAULT) + BuiltInThemes.ALL }

        /** Reads a JSON theme; null if the file is not valid JSON. */
        fun fromJson(id: String, text: String): ThemeSpec? {
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return null
            }
            val base = DEFAULT
            fun color(key: String, fallback: Int): Int = parseColorOrNull(json.optString(key)) ?: fallback
            fun font(key: String, fallback: String): String =
                json.optString(key).takeIf { it in FONTS } ?: fallback

            return ThemeSpec(
                id = id,
                name = json.optString("name").ifBlank { id.substringAfter('/') },
                accent = color("accent", base.accent),
                tile = color("tile", base.tile),
                tileFocused = color("tileFocused", base.tileFocused),
                tileBorder = color("tileBorder", base.tileBorder),
                focusBorder = color("focusBorder", base.focusBorder),
                cornerRadiusDp = json.optInt("cornerRadius", base.cornerRadiusDp).coerceIn(0, 40),
                panel = color("panel", base.panel),
                overlay = color("overlay", base.overlay),
                textSecondary = color("textSecondary", base.textSecondary),
                clockFont = font("clockFont", base.clockFont),
                clockSizeSp = json.optInt("clockSize", base.clockSizeSp).coerceIn(20, 96),
                clockShadow = color("clockShadow", base.clockShadow),
                titleFont = font("titleFont", base.titleFont),
                tileStyle = json.optString("tileStyle").takeIf { it in TILE_STYLES } ?: base.tileStyle,
                doubleFocus = json.optBoolean("doubleFocus", base.doubleFocus),
                wallpaper = WallpaperSpec.fromJson(json.optJSONObject("wallpaper")),
                homeScrim = json.optBoolean("homeScrim", base.homeScrim),
            )
        }

        fun typeface(font: String): Typeface = when (font) {
            "regular" -> Typeface.create("sans-serif", Typeface.NORMAL)
            "medium" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            "bold" -> Typeface.create("sans-serif", Typeface.BOLD)
            "black" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
            "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            "casual" -> Typeface.create("casual", Typeface.NORMAL)
            else -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
    }
}
