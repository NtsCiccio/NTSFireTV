// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.widget.ListView
import android.widget.TextView
import it.ntsciccio.ntsfiretv.R

/**
 * Draws UI elements with the colors of a [ThemeSpec]: themes are data read
 * at runtime, so shapes and backgrounds are built in code instead of XML drawables.
 */
object ThemeStyler {

    /** Background of a tile-shaped element (e.g. buttons): theme glass, stronger border on focus. */
    fun tileBackground(context: Context, theme: ThemeSpec): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), tileShape(context, theme, focused = true))
        addState(intArrayOf(), tileShape(context, theme, focused = false))
    }

    /** Latest card drawables, shared by all tiles until the theme changes. */
    private var cardCache: Triple<ThemeSpec, Drawable.ConstantState, Drawable.ConstantState>? = null

    /**
     * Background (glass) and foreground (focus border) of a tile card. The border is in the foreground
     * so it stays visible even on banners that fill the tile. The drawables are built once
     * per theme and each tile gets a lightweight copy that shares their state.
     */
    fun tileCard(context: Context, theme: ThemeSpec): Pair<Drawable, Drawable> {
        val cached = cardCache?.takeIf { it.first == theme }
            ?: Triple(theme, tileFill(context, theme).constantState!!, tileFocusOverlay(context, theme).constantState!!)
                .also { cardCache = it }
        return cached.second.newDrawable(context.resources) to cached.third.newDrawable(context.resources)
    }

    private fun tileFill(context: Context, theme: ThemeSpec): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(tileRadius(context, theme), theme.tileFocused, 0, 0))
        addState(intArrayOf(), tileShape(context, theme, focused = false))
    }

    private fun tileFocusOverlay(context: Context, theme: ThemeSpec): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focusedTile(context, theme, fill = 0))
        addState(intArrayOf(), ColorDrawable(0))
    }

    /**
     * A single tile shape (normal or selected), also used for the preview in the editor.
     * The style decides corners and borders; on focus: glow (Premium), double border or simple border.
     */
    fun tileShape(context: Context, theme: ThemeSpec, focused: Boolean): Drawable {
        if (focused) return focusedTile(context, theme, fill = theme.tileFocused)
        val classic = theme.tileStyle == "classic"
        return shape(tileRadius(context, theme), theme.tile, if (classic) 0 else px(context, 1), theme.tileBorder)
    }

    /** Selected tile according to the style; with [fill] = 0 only the border (for the card foreground). */
    private fun focusedTile(context: Context, theme: ThemeSpec, fill: Int): Drawable {
        val radius = tileRadius(context, theme)
        return when {
            theme.tileStyle == "premium" -> {
                // Accent-color glow around the tile
                val glow = px(context, 5)
                layered(
                    outer = shape(radius, 0, glow, withAlpha(theme.accent, 0x66)),
                    inner = shape(radius - glow, fill, px(context, 2), theme.focusBorder),
                    inset = glow - px(context, 1),
                )
            }
            theme.doubleFocus -> {
                // Outer accent border + inner white border: visible on both light and dark backgrounds
                val outer = px(context, 3)
                layered(
                    outer = shape(radius, 0, outer, theme.accent),
                    inner = shape(radius - outer, fill, px(context, 2), 0xFFFFFFFF.toInt()),
                    inset = outer,
                )
            }
            else -> shape(radius, fill, px(context, if (theme.tileStyle == "classic") 3 else 2), theme.focusBorder)
        }
    }

    /** Selected row of the lists. */
    fun listSelector(context: Context, theme: ThemeSpec): Drawable = GradientDrawable().apply {
        cornerRadius = minOf(tileRadius(context, theme), dp(context, 16))
        setColor(withAlpha(theme.accent, 0x40))
    }

    private fun tileRadius(context: Context, theme: ThemeSpec): Float = when (theme.tileStyle) {
        // The radius is limited to half the height: the tile becomes a pill
        "capsule" -> dp(context, 999)
        "classic" -> dp(context, 2)
        else -> dp(context, theme.cornerRadiusDp)
    }

    /** Rounded rectangle: [fill] 0 = transparent, [strokeWidth] 0 = no border. */
    private fun shape(radius: Float, fill: Int, strokeWidth: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = maxOf(radius, 0f)
            setColor(fill)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private fun layered(outer: Drawable, inner: Drawable, inset: Int): Drawable =
        LayerDrawable(arrayOf(outer, inner)).apply { setLayerInset(1, inset, inset, inset, inset) }

    fun panelBackground(context: Context, theme: ThemeSpec): Drawable = GradientDrawable().apply {
        cornerRadius = minOf(tileRadius(context, theme), dp(context, 28)) + dp(context, 4)
        setColor(theme.panel)
        setStroke(dp(context, 1).toInt(), theme.tileBorder)
    }

    /** Widget buttons: accent-color circle on focus. */
    fun controlButtonBackground(theme: ThemeSpec): Drawable = StateListDrawable().apply {
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(theme.accent, 0x99))
        }
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(), ColorDrawable(0))
    }

    fun styleClock(view: TextView, theme: ThemeSpec) {
        view.typeface = ThemeSpec.typeface(theme.clockFont)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.clockSizeSp.toFloat())
        view.setShadowLayer(SHADOW_RADIUS, 0f, 2f, theme.clockShadow)
    }

    fun styleTitle(view: TextView, theme: ThemeSpec) {
        view.typeface = ThemeSpec.typeface(theme.titleFont)
    }

    fun styleSecondary(view: TextView, theme: ThemeSpec) {
        view.setTextColor(theme.textSecondary)
    }

    /** Scrim of the screens overlaid on the home (translucent Overlay theme). */
    fun styleOverlayWindow(activity: Activity, theme: ThemeSpec) {
        activity.window.setBackgroundDrawable(ColorDrawable(theme.overlay))
    }

    /** Screen with the `activity_list` layout: scrim, title, message and list selection. */
    fun styleListScreen(activity: Activity, theme: ThemeSpec = ThemeStore.current(activity)): ThemeSpec {
        styleOverlayWindow(activity, theme)
        styleTitle(activity.findViewById(R.id.list_title), theme)
        styleSecondary(activity.findViewById(R.id.list_message), theme)
        activity.findViewById<ListView>(R.id.list).selector = listSelector(activity, theme)
        return theme
    }

    /** Screen with the `activity_tile_grid` layout: scrim, title and message. */
    fun styleGridScreen(activity: Activity): ThemeSpec {
        val theme = ThemeStore.current(activity)
        styleOverlayWindow(activity, theme)
        styleTitle(activity.findViewById(R.id.grid_title), theme)
        styleSecondary(activity.findViewById(R.id.grid_message), theme)
        return theme
    }

    fun styleTileLike(view: View, theme: ThemeSpec) {
        view.background = tileBackground(view.context, theme)
    }


    private fun dp(context: Context, value: Int): Float = value * context.resources.displayMetrics.density

    private fun px(context: Context, value: Int): Int = dp(context, value).toInt()

    private const val SHADOW_RADIUS = 12f
}
