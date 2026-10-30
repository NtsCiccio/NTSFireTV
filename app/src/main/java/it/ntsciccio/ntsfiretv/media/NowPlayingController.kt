// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import it.ntsciccio.ntsfiretv.system.NowPlayingListenerService

/** What is playing, for the home widget. */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val isPlaying: Boolean,
)

/**
 * Follows the active MediaSessions (Spotify and any other player) and notifies [onChange]
 * on the UI thread. Works only if [NowPlayingListenerService] is enabled; otherwise
 * [start] returns false and the widget stays hidden.
 */
class NowPlayingController(
    context: Context,
    private val onChange: (NowPlaying?) -> Unit,
) {

    private val appContext: Context = context.applicationContext
    private val sessionManager: MediaSessionManager? = appContext.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var started = false

    /**
     * Last track and state read from the callbacks. The data delivered with the callback is used instead of
     * querying the player: each query would also copy the artwork (~1 MB), which isn't shown.
     */
    private var title: String? = null
    private var artist: String? = null
    private var playing = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        pick(sessions.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            readTrack(metadata)
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            playing = state?.state == PlaybackState.STATE_PLAYING
            publish()
        }
        override fun onSessionDestroyed() = refresh()
    }

    /** @return false if the listener is not enabled (permission missing). */
    fun start(): Boolean {
        val manager = sessionManager ?: return false
        if (!NowPlayingListenerService.isEnabled(appContext)) return false
        return try {
            val component = NowPlayingListenerService.component(appContext)
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            started = true
            refresh()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaSession non accessibili: listener non abilitato", e)
            false
        }
    }

    fun stop() {
        if (started) sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        started = false
        setController(null)
    }

    fun playPause() {
        val current = controller ?: return
        if (isPlaying(current)) current.transportControls.pause() else current.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    private fun refresh() {
        val manager = sessionManager ?: return
        try {
            pick(manager.getActiveSessions(NowPlayingListenerService.component(appContext)).orEmpty())
        } catch (e: SecurityException) {
            setController(null)
        }
    }

    /** Prefers a playing session, then Spotify, then the first available one. */
    private fun pick(sessions: List<MediaController>) {
        val best = sessions.firstOrNull { isPlaying(it) }
            ?: sessions.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
            ?: sessions.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) setController(best) else publish()
    }

    private fun setController(newController: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = newController
        newController?.registerCallback(callback, handler)
        // A single full read for the new session, then the callbacks are enough
        readTrack(newController?.metadata)
        playing = newController?.let(::isPlaying) == true
        publish()
    }

    private fun readTrack(metadata: MediaMetadata?) {
        title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    }

    private fun publish() {
        val currentTitle = title
        if (controller == null || currentTitle.isNullOrEmpty()) {
            onChange(null)
            return
        }
        onChange(NowPlaying(currentTitle, artist, playing))
    }

    private fun isPlaying(controller: MediaController): Boolean =
        controller.playbackState?.state == PlaybackState.STATE_PLAYING

    private companion object {
        const val TAG = "NTSFireTV"
        const val SPOTIFY_PACKAGE = "com.spotify.tv.android"
    }
}
