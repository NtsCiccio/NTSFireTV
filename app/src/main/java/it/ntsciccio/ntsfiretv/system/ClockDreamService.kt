// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.system

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import it.ntsciccio.ntsfiretv.home.ThemeSpec
import it.ntsciccio.ntsfiretv.home.ThemeStore
import it.ntsciccio.ntsfiretv.home.WallpaperSpec
import it.ntsciccio.ntsfiretv.home.Weather
import it.ntsciccio.ntsfiretv.wallpaper.AmbientWallpaperView
import kotlin.random.Random

/**
 * Screensaver in the style of the active theme: the theme's background with slowly moving blobs
 * (with Retrò also the sunset stripes), plus clock, date and weather in the theme's font.
 * The clock group moves to a new position every [MOVE_INTERVAL_MS] with a slow animation,
 * so it doesn't leave marks on screens. Fire OS uses the Amazon screensaver: ours is enabled via ADB.
 */
class ClockDreamService : DreamService() {

    private lateinit var root: FrameLayout
    private lateinit var ambient: AmbientWallpaperView
    private lateinit var clockGroup: LinearLayout

    private val moveClock = object : Runnable {
        override fun run() {
            val maxX = root.width - clockGroup.width
            val maxY = root.height - clockGroup.height
            if (maxX > 0 && maxY > 0) {
                clockGroup.animate()
                    .x(Random.nextInt(maxX).toFloat())
                    .y(Random.nextInt(maxY).toFloat())
                    .setDuration(MOVE_DURATION_MS)
                    .start()
            }
            root.postDelayed(this, MOVE_INTERVAL_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true

        val theme = ThemeStore.current(this)
        // The Default theme has no generated background: derive one from its colors
        ambient = AmbientWallpaperView(this, theme.wallpaper ?: WallpaperSpec.fromColors(theme.tile, theme.accent))
        clockGroup = clockGroup(theme)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(ambient, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(clockGroup, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        ambient.start()
        // First position as soon as the layout is ready, then at intervals
        root.post(moveClock)
    }

    override fun onDreamingStopped() {
        ambient.stop()
        root.removeCallbacks(moveClock)
        super.onDreamingStopped()
    }

    /** Large clock, date and (if a city is set) weather, centered and in the theme's fonts. */
    private fun clockGroup(theme: ThemeSpec): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(clockText("HH:mm", theme.clockSizeSp * CLOCK_SCALE, Color.WHITE, theme))
        addView(clockText("EEEE d MMMM", DETAIL_SIZE_SP, theme.textSecondary, theme))
        Weather.cached(this@ClockDreamService)?.let { weather ->
            val text = listOf("${weather.temperature}°", weather.description).filter { it.isNotEmpty() }.joinToString(" · ")
            addView(styled(TextView(this@ClockDreamService), DETAIL_SIZE_SP, theme.textSecondary, theme).apply { this.text = text })
        }
    }

    private fun clockText(format: String, sizeSp: Float, color: Int, theme: ThemeSpec): TextClock =
        styled(TextClock(this), sizeSp, color, theme).apply {
            format12Hour = format
            format24Hour = format
        }

    private fun <T : TextView> styled(view: T, sizeSp: Float, color: Int, theme: ThemeSpec): T = view.apply {
        typeface = ThemeSpec.typeface(theme.clockFont)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        setShadowLayer(SHADOW_RADIUS, 0f, 2f, 0xCC000000.toInt())
        textAlignment = View.TEXT_ALIGNMENT_CENTER
    }

    companion object {
        private const val MOVE_INTERVAL_MS = 40_000L
        private const val MOVE_DURATION_MS = 4_000L
        /** Screensaver clock, larger than the home one. */
        private const val CLOCK_SCALE = 2.2f
        private const val DETAIL_SIZE_SP = 24f
        private const val SHADOW_RADIUS = 16f
        private const val SCREENSAVER_COMPONENTS = "screensaver_components"

        fun component(context: Context): ComponentName = ComponentName(context, ClockDreamService::class.java)

        /** `true` if our screensaver is the one set in the system. */
        fun isEnabled(context: Context): Boolean =
            isComponentListed(context, SCREENSAVER_COMPONENTS, ',', component(context))
    }
}
