// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import java.util.Locale

/*
 * ARGB color functions used by themes, wallpapers and the editor.
 * Written without `android.graphics.Color` so they can be verified with tests on the computer.
 */

/** Opaque alpha channel mask. */
internal const val OPAQUE: Int = 0xFF000000.toInt()

/** `#RRGGBB` (opaque) or `#AARRGGBB` → ARGB color; null if empty or invalid. */
internal fun parseColorOrNull(value: String?): Int? {
    val hex = value?.trim()?.removePrefix("#") ?: return null
    if (hex.length != 6 && hex.length != 8) return null
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    val parsed = hex.toLong(16).toInt()
    return if (hex.length == 6) parsed or OPAQUE else parsed
}

/** Color → `#AARRGGBB`, the format of the theme JSON files. */
internal fun colorToHex(color: Int): String = String.format(Locale.ROOT, "#%08X", color)

/** Same color with opacity [alpha] (0–255, clamped). */
internal fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

/** Same color at 75% brightness, opaque. */
internal fun darken(color: Int): Int = mapRgb(color) { it * 3 / 4 }

/** Slightly lighter variant, opaque (e.g. the selected tile in the editor). */
internal fun lighten(color: Int): Int = mapRgb(color) { minOf(it + 0x14, 0xFF) }

/** Applies [change] to red, green and blue; the result is opaque. */
private fun mapRgb(color: Int, change: (Int) -> Int): Int {
    fun channel(shift: Int): Int = change((color shr shift) and 0xFF).coerceIn(0, 255) shl shift
    return OPAQUE or channel(16) or channel(8) or channel(0)
}
