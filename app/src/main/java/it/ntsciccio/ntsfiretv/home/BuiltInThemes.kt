// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import it.ntsciccio.ntsfiretv.home.WallpaperSpec.Blob

/**
 * Built-in themes besides the "Predefinito" (Default) one. Currently only "Retrò", with colors from the Gruvbox palette
 * (MIT license): only the colors are taken, the wallpaper is generated, no external images.
 * More themes can be created with the on-TV editor or as JSON files (see docs/DEVELOPMENT.md).
 */
object BuiltInThemes {

    val ALL: List<ThemeSpec> by lazy { listOf(retro()) }

    private fun retro() = ThemeSpec.DEFAULT.copy(
        id = "retro",
        name = "Retrò",
        accent = c("#FABD2F"),
        tile = c("#3C3836", .85f),
        tileFocused = c("#504945", .95f),
        tileBorder = c("#000000", 0f),
        focusBorder = c("#FABD2F"),
        cornerRadiusDp = 2,
        panel = c("#282828", .9f),
        overlay = c("#282828", .9f),
        textSecondary = c("#D5C4A1"),
        clockFont = "condensed",
        clockSizeSp = 48,
        clockShadow = c("#000000", .9f),
        titleFont = "condensed",
        tileStyle = "classic",
        doubleFocus = false,
        wallpaper = WallpaperSpec(
            c("#282828"), c("#1D2021"), 180,
            listOf(Blob(c("#FE8019"), .30f, 50f, 120f, 90f)),
            stripes = listOf(c("#FABD2F"), c("#FE8019"), c("#D65D0E"), c("#8EC07C")),
        ),
    )

    /** `#RRGGBB` color with opacity 0–1. */
    private fun c(hex: String, alpha: Float = 1f): Int = withAlpha(parseColorOrNull(hex)!!, (alpha * 255).toInt())
}
