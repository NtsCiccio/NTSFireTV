// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import java.io.File

/**
 * Playback with VLC through an explicit intent.
 * VLC's player is opened directly because only then, on close,
 * does it return position and duration (extras `extra_position` / `extra_duration`).
 */
object VlcLauncher {

    const val REQUEST_CODE = 42
    const val EXTRA_POSITION = "extra_position"
    const val EXTRA_DURATION = "extra_duration"

    private const val PACKAGE = "org.videolan.vlc"
    private const val PLAYER_ACTIVITY = "org.videolan.vlc.gui.video.VideoPlayerActivity"

    /** @return false if VLC is not installed. */
    fun play(activity: Activity, file: File, positionMs: Long): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaFileProvider.uriFor(activity, file), MediaFileProvider.mimeTypeOf(file))
            component = ComponentName(PACKAGE, PLAYER_ACTIVITY)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("title", file.nameWithoutExtension)
            // from_start = true stops VLC from asking again where to resume
            putExtra("from_start", positionMs <= 0)
            if (positionMs > 0) putExtra("position", positionMs)
        }

        return try {
            activity.startActivityForResult(intent, REQUEST_CODE)
            true
        } catch (e: ActivityNotFoundException) {
            // VLC versions with a different player: it still opens, but without a returned position
            try {
                intent.component = null
                intent.setPackage(PACKAGE)
                activity.startActivityForResult(intent, REQUEST_CODE)
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
    }
}
