// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import org.json.JSONArray
import org.json.JSONObject

/**
 * Generated theme wallpaper: base gradient, blurred color blobs and optional stripes.
 * No images: it is drawn at 1920×1080 from colors alone (see `ThemeWallpaperRenderer`).
 *
 * Sizes are percentages of the screen, as in the themes HTML preview:
 * a blob has center ([Blob.x], [Blob.y]) and horizontal radius [Blob.radius] % of the width
 * (the vertical one is 1.5 times that, in % of the height).
 */
data class WallpaperSpec(
    val baseStart: Int,
    val baseEnd: Int,
    /** Gradient direction in degrees, as in CSS: 0 = up, 90 = right. */
    val angle: Int,
    val blobs: List<Blob>,
    /** Horizontal stripes at the bottom (sunset style), from top to bottom. */
    val stripes: List<Int> = emptyList(),
) {

    data class Blob(val color: Int, val alpha: Float, val x: Float, val y: Float, val radius: Float)

    fun toJson(): JSONObject = JSONObject().apply {
        put("base", JSONArray().put(colorToHex(baseStart)).put(colorToHex(baseEnd)))
        put("angle", angle)
        put("blobs", JSONArray().apply {
            blobs.forEach { blob ->
                put(JSONObject().apply {
                    put("color", colorToHex(blob.color))
                    put("alpha", blob.alpha.toDouble())
                    put("x", blob.x.toDouble())
                    put("y", blob.y.toDouble())
                    put("r", blob.radius.toDouble())
                })
            }
        })
        if (stripes.isNotEmpty()) put("stripes", JSONArray().apply { stripes.forEach { put(colorToHex(it)) } })
    }

    companion object {

        /** Limits for JSON files: beyond them, drawing the wallpaper would become very slow. */
        private const val MAX_BLOBS = 16
        private const val MAX_STRIPES = 16

        /** Reads the wallpaper from the theme JSON; null if missing or invalid. */
        fun fromJson(json: JSONObject?): WallpaperSpec? {
            if (json == null) return null
            val base = json.optJSONArray("base") ?: return null
            val start = parseColorOrNull(base.optString(0)) ?: return null
            val end = parseColorOrNull(base.optString(1)) ?: start
            val blobs = json.optJSONArray("blobs")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val color = parseColorOrNull(item.optString("color")) ?: return@mapNotNull null
                    Blob(
                        color = color,
                        alpha = item.optDouble("alpha", 0.4).toFloat().coerceIn(0f, 1f),
                        x = item.optDouble("x", 50.0).toFloat().coerceIn(-50f, 150f),
                        y = item.optDouble("y", 50.0).toFloat().coerceIn(-50f, 150f),
                        radius = item.optDouble("r", 50.0).toFloat().coerceIn(5f, 150f),
                    )
                }
            }.orEmpty().take(MAX_BLOBS)
            val stripes = json.optJSONArray("stripes")?.let { array ->
                (0 until array.length()).mapNotNull { parseColorOrNull(array.optString(it)) }
            }.orEmpty().take(MAX_STRIPES)
            return WallpaperSpec(start, end, json.optInt("angle", 180).mod(360), blobs, stripes)
        }

        /** Simple wallpaper derived from a theme's colors (for themes created in the editor). */
        fun fromColors(tile: Int, accent: Int): WallpaperSpec {
            val base = tile or OPAQUE
            return WallpaperSpec(
                baseStart = base,
                baseEnd = darken(base),
                angle = 160,
                blobs = listOf(Blob(accent, 0.40f, 20f, 20f, 55f), Blob(accent, 0.22f, 80f, 95f, 60f)),
            )
        }
    }
}

