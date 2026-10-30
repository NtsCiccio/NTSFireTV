// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.wallpaper

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import it.ntsciccio.ntsfiretv.home.WallpaperSpec
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated theme wallpaper for the screensaver: the color blobs move slowly
 * (a few percentage points, one cycle roughly every minute), the stripes stay still, all dimmed
 * by a dark veil. Redraws about [FPS] times per second, only between [start] and [stop].
 */
class AmbientWallpaperView(context: Context, private val spec: WallpaperSpec) : View(context) {

    private val paint = ThemeWallpaperRenderer.newPaint()
    private val startedAt = SystemClock.elapsedRealtime()
    private var running = false

    fun start() {
        running = true
        invalidate()
    }

    fun stop() {
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000f
        ThemeWallpaperRenderer.draw(canvas, spec, width.toFloat(), height.toFloat(), paint) { index ->
            // Each blob has its own phase: they move together but not in unison
            val phase = seconds * SPEED + index * 1.7f
            AMPLITUDE * sin(phase) to AMPLITUDE * cos(phase * 0.8f + index)
        }
        canvas.drawColor(DIM_COLOR)
        if (running) postInvalidateDelayed(1000L / FPS)
    }

    private companion object {
        /** Maximum blob displacement, in percentage points of the screen. */
        const val AMPLITUDE = 8f
        /** Radians per second: one full oscillation in about a minute. */
        const val SPEED = 0.1f
        /** Slow motion: 15 frames per second are enough and save CPU. */
        const val FPS = 15
        /** 35% dark veil: the screen stays dim at night. */
        const val DIM_COLOR = 0x59000000
    }
}
