// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import it.ntsciccio.ntsfiretv.home.WallpaperSpec
import it.ntsciccio.ntsfiretv.home.withAlpha
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a theme's wallpaper ([WallpaperSpec]) with the same math as the HTML preview:
 * a base linear gradient, then elliptical radial blobs fading to transparent at 70%,
 * then the stripes. [render] produces the static image (off the UI thread), [draw] is also used by the
 * screensaver, which slowly moves the blobs.
 */
object ThemeWallpaperRenderer {

    fun render(spec: WallpaperSpec, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), spec, width.toFloat(), height.toFloat(), newPaint())
        return bitmap
    }

    /** Paint for [draw]: antialiasing and dithering against banding in dark gradients. */
    fun newPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    /**
     * Draws the wallpaper on a [w]×[h] [canvas]. [blobOffset] moves the n-th blob
     * by (dx, dy) percentage points: 0 for the static wallpaper, values changing over time
     * for the animated screensaver.
     */
    fun draw(
        canvas: Canvas,
        spec: WallpaperSpec,
        w: Float,
        h: Float,
        paint: Paint,
        blobOffset: (index: Int) -> Pair<Float, Float> = { 0f to 0f },
    ) {
        // Base gradient: a line through the center in the direction of the angle (like CSS)
        val radians = Math.toRadians(spec.angle.toDouble())
        val dx = sin(radians).toFloat()
        val dy = -cos(radians).toFloat()
        val half = (abs(w * dx) + abs(h * dy)) / 2f
        paint.shader = LinearGradient(
            w / 2f - dx * half, h / 2f - dy * half,
            w / 2f + dx * half, h / 2f + dy * half,
            spec.baseStart, spec.baseEnd, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // Blobs: a faded circle squashed vertically to become an ellipse
        spec.blobs.forEachIndexed { index, blob ->
            val (offsetX, offsetY) = blobOffset(index)
            val rx = blob.radius / 100f * w
            val ry = blob.radius * 1.5f / 100f * h
            val color = withAlpha(blob.color, (blob.alpha * 255).toInt())
            val clear = withAlpha(blob.color, 0)
            canvas.save()
            canvas.translate((blob.x + offsetX) / 100f * w, (blob.y + offsetY) / 100f * h)
            canvas.scale(1f, ry / rx)
            paint.shader = RadialGradient(
                0f, 0f, rx,
                intArrayOf(color, clear, clear), floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(0f, 0f, rx, paint)
            canvas.restore()
        }

        // Horizontal stripes from 58% of the height, each 3.2% tall with a thin gap
        paint.shader = null
        spec.stripes.forEachIndexed { index, color ->
            paint.color = withAlpha(color, STRIPE_ALPHA)
            val top = (STRIPES_START + index * STRIPE_HEIGHT) * h
            val bottom = (STRIPES_START + (index + 1) * STRIPE_HEIGHT - STRIPE_GAP) * h
            canvas.drawRect(0f, top, w, bottom, paint)
        }
    }

    private const val STRIPES_START = 0.58f
    private const val STRIPE_HEIGHT = 0.032f
    private const val STRIPE_GAP = 0.006f
    private const val STRIPE_ALPHA = 140
}
