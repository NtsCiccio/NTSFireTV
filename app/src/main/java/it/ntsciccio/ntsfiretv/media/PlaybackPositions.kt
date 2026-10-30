// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.media

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Locale

/**
 * How far a video got, returned by VLC on close.
 * @property savedAt when it was saved (ms); 0 for positions saved before this field existed.
 */
data class Progress(val positionMs: Long, val durationMs: Long, val savedAt: Long = 0L)

/**
 * Playback positions per file (key: absolute path), for "Riprendi" (Resume) and for
 * the home's "Continua a guardare" (Continue watching). Value: `posizione:durata:quando` (position:duration:when, in ms).
 * A video watched almost to the end is considered finished and forgotten.
 */
class PlaybackPositions(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(file: File): Progress? = parse(prefs.getString(file.absolutePath, null))

    /**
     * The most recently half-watched video, if the file is still reachable
     * (e.g. the USB stick is plugged in). Reads files: off the UI thread.
     */
    fun latest(): Pair<File, Progress>? =
        prefs.all.entries
            .mapNotNull { (path, value) -> parse(value as? String)?.let { File(path) to it } }
            .sortedByDescending { it.second.savedAt }
            .firstOrNull { it.first.isFile }

    fun save(path: String, positionMs: Long, durationMs: Long) {
        if (!isWorthResuming(positionMs, durationMs)) {
            prefs.edit().remove(path).apply()
        } else {
            val now = System.currentTimeMillis()
            prefs.edit().putString(path, "$positionMs$SEPARATOR$durationMs$SEPARATOR$now").apply()
        }
    }

    internal companion object {

        /** `posizione:durata[:quando]` → [Progress]; null if invalid. */
        fun parse(value: String?): Progress? {
            val parts = value?.split(SEPARATOR) ?: return null
            val position = parts.getOrNull(0)?.toLongOrNull() ?: return null
            return Progress(
                positionMs = position,
                durationMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
                savedAt = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
            )
        }

        /**
         * `true` if offering "Riprendi" (Resume) makes sense: at least [MIN_POSITION_MS] watched and not
         * in the last 30 s (or the last ~3%) of the video, which then counts as finished.
         */
        fun isWorthResuming(positionMs: Long, durationMs: Long): Boolean {
            if (positionMs < MIN_POSITION_MS) return false
            val finished = durationMs > 0 && durationMs - positionMs < maxOf(END_MARGIN_MS, durationMs / 30)
            return !finished
        }

        const val PREFS_NAME = "playback_positions"
        const val SEPARATOR = ":"
        /** Below 10 s it's not worth offering "Riprendi". */
        const val MIN_POSITION_MS = 10_000L
        /** In the last 30 s (or the last ~3%) the video is considered finished. */
        const val END_MARGIN_MS = 30_000L
    }
}

/** Formats a duration as `m:ss` or `h:mm:ss`. */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
