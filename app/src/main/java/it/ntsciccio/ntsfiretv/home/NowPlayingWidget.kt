// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import it.ntsciccio.ntsfiretv.R
import it.ntsciccio.ntsfiretv.media.NowPlaying
import it.ntsciccio.ntsfiretv.media.NowPlayingController

/**
 * "In riproduzione" (Now playing) widget above the dock: title, artist and D-pad-navigable controls.
 * Hidden if there is no media session.
 *
 * Players update their state many times in a row (especially when pressing ⏯ ⏭), sometimes
 * going through "no session": the widget hides only after [HIDE_DELAY_MS]
 * and updates only what changed, so it does not flicker.
 */
class NowPlayingWidget(private val root: View) {

    private val title: TextView = root.findViewById(R.id.now_playing_title)
    private val artist: TextView = root.findViewById(R.id.now_playing_artist)
    private val playPause: ImageView = root.findViewById(R.id.now_playing_play_pause)
    private val controller = NowPlayingController(root.context, ::show)

    private var shownTrack: String? = null
    private var shownPlaying: Boolean? = null

    private val hideRunnable = Runnable {
        root.visibility = View.GONE
        shownTrack = null
        shownPlaying = null
    }

    init {
        val previous: View = root.findViewById(R.id.now_playing_previous)
        val next: View = root.findViewById(R.id.now_playing_next)
        previous.setOnClickListener { controller.previous() }
        next.setOnClickListener { controller.next() }
        playPause.setOnClickListener { controller.playPause() }

        // Colors and shapes from the chosen theme
        val theme = ThemeStore.current(root.context)
        root.background = ThemeStyler.panelBackground(root.context, theme)
        ThemeStyler.styleSecondary(artist, theme)
        listOf(previous, playPause, next).forEach { it.background = ThemeStyler.controlButtonBackground(theme) }
    }

    fun start() {
        if (!controller.start()) root.visibility = View.GONE
    }

    fun stop() {
        controller.stop()
        root.removeCallbacks(hideRunnable)
    }

    private fun show(nowPlaying: NowPlaying?) {
        if (nowPlaying == null) {
            // Often it is just a moment between two player states: wait before hiding
            if (root.visibility == View.VISIBLE) {
                root.removeCallbacks(hideRunnable)
                root.postDelayed(hideRunnable, HIDE_DELAY_MS)
            }
            return
        }
        root.removeCallbacks(hideRunnable)

        val track = "${nowPlaying.title}\u0000${nowPlaying.artist}"
        if (track != shownTrack) {
            shownTrack = track
            title.text = nowPlaying.title
            artist.text = nowPlaying.artist
            artist.visibility = if (nowPlaying.artist.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        if (nowPlaying.isPlaying != shownPlaying) {
            shownPlaying = nowPlaying.isPlaying
            playPause.setImageResource(if (nowPlaying.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            playPause.contentDescription = root.context.getString(
                if (nowPlaying.isPlaying) R.string.now_playing_pause else R.string.now_playing_play,
            )
        }
        root.visibility = View.VISIBLE
    }

    private companion object {
        const val HIDE_DELAY_MS = 1_500L
    }
}
