// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import it.ntsciccio.ntsfiretv.R

/**
 * Apple TV-style tile, used in the dock and in the "Tutte le app" (All apps) grid:
 * rounded card that grows and lifts on focus, with a label appearing below.
 */
class DockTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val card: View
    private val icon: ImageView
    private val placeholder: TextView
    private val label: TextView

    private val focusedElevation = resources.getDimension(R.dimen.dock_tile_focused_elevation)
    private val iconPadding = resources.getDimensionPixelSize(R.dimen.tile_icon_padding)
    private val focusInterpolator = DecelerateInterpolator()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true

        LayoutInflater.from(context).inflate(R.layout.view_dock_tile, this, true)
        card = findViewById(R.id.dock_card)
        icon = findViewById(R.id.dock_icon)
        placeholder = findViewById(R.id.dock_placeholder)
        label = findViewById(R.id.dock_label)

        // Shape and colors from the chosen theme; clips the image to the card's rounded corners
        val theme = ThemeStore.current(context)
        // The focus border goes in the foreground, otherwise a banner filling the tile would cover it
        val (fill, focusBorder) = ThemeStyler.tileCard(context, theme)
        card.background = fill
        card.foreground = focusBorder
        card.clipToOutline = true
    }

    fun bind(text: String) {
        label.text = text
        placeholder.text = text.first().uppercase()
        contentDescription = text
    }

    /**
     * Shows the app banner or icon. With null it goes back to the placeholder.
     * Banners (16:9) fill the card, icons are centered with a margin.
     */
    fun setVisual(drawable: Drawable?, isBanner: Boolean) {
        if (drawable == null) {
            icon.setImageDrawable(null)
            icon.visibility = GONE
            placeholder.visibility = VISIBLE
            return
        }

        icon.setImageDrawable(drawable)
        icon.scaleType = if (isBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        val padding = if (isBanner) 0 else iconPadding
        icon.setPadding(padding, padding, padding, padding)
        icon.visibility = VISIBLE
        placeholder.visibility = GONE
    }

    /** Apps that are not installed stay visible but dimmed. */
    fun setAvailable(available: Boolean) {
        card.alpha = if (available) 1f else UNAVAILABLE_ALPHA
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)

        val scale = if (gainFocus) FOCUSED_SCALE else 1f
        card.animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationZ(if (gainFocus) focusedElevation else 0f)
            .setDuration(ANIM_DURATION_MS)
            .setInterpolator(focusInterpolator)
            .start()

        label.animate()
            .alpha(if (gainFocus) 1f else 0f)
            .setDuration(ANIM_DURATION_MS)
            .start()
    }

    private companion object {
        const val FOCUSED_SCALE = 1.15f
        const val UNAVAILABLE_ALPHA = 0.55f
        const val ANIM_DURATION_MS = 150L
    }
}

/** Adds [tile] at the end of a row (dock, recents), spaced from the previous one. */
fun LinearLayout.addTileInRow(tile: DockTileView) {
    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    if (childCount > 0) params.marginStart = resources.getDimensionPixelSize(R.dimen.dock_tile_spacing)
    addView(tile, params)
}

/** Adds [tile] to a grid ("Tutte le app", image picker) with the standard spacing. */
fun GridLayout.addTileInGrid(tile: DockTileView) {
    val margin = resources.getDimensionPixelSize(R.dimen.grid_tile_spacing) / 2
    addView(tile, GridLayout.LayoutParams().apply { setMargins(margin, margin, margin, margin) })
}
