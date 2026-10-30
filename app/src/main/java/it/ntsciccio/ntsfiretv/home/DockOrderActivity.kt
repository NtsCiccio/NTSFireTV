// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import it.ntsciccio.ntsfiretv.R

/**
 * Dock reordering with the D-pad: OK on a tile "picks it up", ◀ ▶ move it,
 * OK or Back drops it. Menu on a user-added app removes it. The order is saved immediately.
 */
class DockOrderActivity : OverlayActivity() {

    private lateinit var row: LinearLayout
    private val items = mutableListOf<DockItem>()
    /** Tile being moved, -1 if none. */
    private var movingIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dock_order)
        val theme = ThemeStore.current(this)
        ThemeStyler.styleOverlayWindow(this, theme)
        ThemeStyler.styleTitle(findViewById(R.id.dock_order_title), theme)
        ThemeStyler.styleSecondary(findViewById(R.id.dock_order_hint), theme)
        ThemeStyler.styleTileLike(findViewById(R.id.dock_order_reset), theme)
        row = findViewById(R.id.dock_order_row)
        findViewById<View>(R.id.dock_order_reset).setOnClickListener { resetOrder() }

        items += DockPreferences.load(this)
        render(focusIndex = 0)
    }

    // Fire OS 6 (API 25) has no Android 13+ predictive back: onKeyDown is enough
    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && movingIndex < 0) {
            val index = (0 until row.childCount).firstOrNull { row.getChildAt(it).isFocused }
            val item = index?.let { items.getOrNull(it) }
            if (index != null && item != null && DockPreferences.isRemovable(item)) {
                items.removeAt(index)
                DockPreferences.save(this, items)
                toast(getString(R.string.app_dock_removed, item.label))
                render(focusIndex = (index - 1).coerceAtLeast(0))
                return true
            }
        }
        if (movingIndex >= 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    move(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    move(+1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                KeyEvent.KEYCODE_BACK -> {
                    drop()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun render(focusIndex: Int) {
        row.removeAllViews()
        items.forEachIndexed { index, item ->
            val label = if (index == movingIndex) getString(R.string.dock_order_moving, item.label) else item.label
            row.addTileInRow(
                DockTileView(this).apply {
                    bind(label)
                    setVisual(getDrawable(item.iconRes), isBanner = false)
                    setOnClickListener { onTileClicked(index) }
                },
            )
        }
        row.getChildAt(focusIndex)?.requestFocus()
    }

    private fun onTileClicked(index: Int) {
        if (movingIndex >= 0) {
            drop()
        } else {
            movingIndex = index
            render(focusIndex = index)
        }
    }

    private fun move(delta: Int) {
        val target = movingIndex + delta
        if (target !in items.indices) return
        items.add(target, items.removeAt(movingIndex))
        movingIndex = target
        render(focusIndex = target)
    }

    private fun drop() {
        val index = movingIndex
        movingIndex = -1
        DockPreferences.save(this, items)
        render(focusIndex = index)
    }

    private fun resetOrder() {
        DockPreferences.reset(this)
        movingIndex = -1
        items.clear()
        items += DefaultDock.items
        render(focusIndex = 0)
    }
}
