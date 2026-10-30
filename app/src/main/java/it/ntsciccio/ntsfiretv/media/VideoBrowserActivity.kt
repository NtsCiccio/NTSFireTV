// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.media

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ListView
import android.widget.TextView
import it.ntsciccio.ntsfiretv.R
import it.ntsciccio.ntsfiretv.home.toast
import it.ntsciccio.ntsfiretv.home.tvDialog
import it.ntsciccio.ntsfiretv.home.OverlayActivity
import it.ntsciccio.ntsfiretv.home.Row
import it.ntsciccio.ntsfiretv.home.RowAdapter
import it.ntsciccio.ntsfiretv.home.ThemeStyler
import it.ntsciccio.ntsfiretv.system.StoragePermission
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Video file browser: volumes → folders → files.
 *
 * Back goes up one folder; from a volume's root it returns to the volume list.
 * Volumes refresh on their own when a USB stick is plugged in or removed.
 * Videos open in VLC; on close VLC returns the position for "Riprendi" (Resume).
 */
class VideoBrowserActivity : OverlayActivity() {

    /** Recreating it would lose work in progress: it keeps the theme it was opened with. */
    override val recreateOnThemeChange = false

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: RowAdapter
    private lateinit var positions: PlaybackPositions

    /** Folder shown; null = volume list. */
    private var currentDir: File? = null
    /** Video opened in VLC, to save its position on return. */
    private var playingPath: String? = null
    /** Discards results of loads superseded by a more recent one. */
    private var generation = 0

    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val dir = currentDir
            when {
                dir == null -> showVolumes(focusKey = null)
                StorageVolumes.find(this@VideoBrowserActivity, dir) == null -> {
                    toast(R.string.video_volume_removed)
                    showVolumes(focusKey = null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        ThemeStyler.styleListScreen(this)
        titleView = findViewById(R.id.list_title)
        messageView = findViewById(R.id.list_message)
        listView = findViewById(R.id.list)
        adapter = RowAdapter(this)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> adapter.getItem(position).action?.invoke() }
        positions = PlaybackPositions(applicationContext)

        currentDir = (savedInstanceState?.getString(STATE_DIR) ?: intent.getStringExtra(EXTRA_DIR))?.let(::File)
        playingPath = savedInstanceState?.getString(STATE_PLAYING)

        if (StoragePermission.isGranted(this)) reload(focusKey = null) else StoragePermission.request(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != StoragePermission.REQUEST_CODE) return
        if (StoragePermission.isGrantedResult(requestCode, grantResults)) {
            reload(focusKey = null)
        } else {
            titleView.setText(R.string.video_title)
            showMessage(getString(R.string.video_no_permission))
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        registerReceiver(mountReceiver, filter)
    }

    override fun onStop() {
        unregisterReceiver(mountReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DIR, currentDir?.absolutePath)
        outState.putString(STATE_PLAYING, playingPath)
    }

    /** VLC returns how far playback got: save it and update the "in corso" (in progress) indicator. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VlcLauncher.REQUEST_CODE) return
        val path = playingPath ?: return
        val position = data?.getLongExtra(VlcLauncher.EXTRA_POSITION, -1L) ?: -1L
        val duration = data?.getLongExtra(VlcLauncher.EXTRA_DURATION, 0L) ?: 0L
        if (position >= 0) positions.save(path, position, duration)
        playingPath = null
        reload(focusKey = path)
    }

    // Fire OS 6 (API 25) has no Android 13+ predictive back: onKeyDown is enough
    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && goUp()) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun reload(focusKey: String?) {
        val dir = currentDir
        if (dir == null || StorageVolumes.find(this, dir) == null) showVolumes(focusKey) else showDir(dir, focusKey)
    }

    /** @return false if already in the volume list (Back closes the screen). */
    private fun goUp(): Boolean {
        val dir = currentDir ?: return false
        val volume = StorageVolumes.find(this, dir)
        val parent = dir.parentFile
        if (volume == null || dir.absolutePath == volume.root.absolutePath || parent == null) {
            showVolumes(focusKey = volume?.root?.absolutePath)
        } else {
            showDir(parent, focusKey = dir.absolutePath)
        }
        return true
    }

    private fun showVolumes(focusKey: String?) {
        currentDir = null
        load(getString(R.string.video_title), focusKey, getString(R.string.video_no_volumes)) {
            StorageVolumes.list(this).map { volume ->
                Row(
                    title = volume.label,
                    subtitle = volume.root.absolutePath,
                    iconRes = if (volume.isRemovable) R.drawable.ic_usb else R.drawable.ic_storage,
                    key = volume.root.absolutePath,
                    action = { showDir(volume.root, focusKey = null) },
                )
            }
        }
    }

    private fun showDir(dir: File, focusKey: String?) {
        currentDir = dir
        val volume = StorageVolumes.find(this, dir)
        val relative = volume?.let { dir.absolutePath.removePrefix(it.root.absolutePath) }.orEmpty()
        val title = (volume?.label ?: dir.name) + relative

        load(title, focusKey, getString(R.string.video_empty_folder)) {
            val children = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }
            val folders = children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            val videos = children
                .filter { it.isFile && it.extension.lowercase() in VIDEO_EXTENSIONS }
                .sortedBy { it.name.lowercase() }

            folders.map { folder ->
                Row(
                    title = folder.name,
                    iconRes = R.drawable.ic_folder,
                    key = folder.absolutePath,
                    action = { showDir(folder, focusKey = null) },
                )
            } + videos.map { video -> videoRow(video) }
        }
    }

    private fun videoRow(video: File): Row {
        val progress = positions.get(video)
        val subtitle = progress?.let {
            if (it.durationMs > 0) {
                getString(R.string.video_in_progress_of, formatDuration(it.positionMs), formatDuration(it.durationMs))
            } else {
                getString(R.string.video_in_progress, formatDuration(it.positionMs))
            }
        }
        return Row(
            title = video.name,
            subtitle = subtitle,
            iconRes = R.drawable.ic_video,
            key = video.absolutePath,
            action = { onVideoSelected(video) },
        )
    }

    /** Lists in the background with [producer], then shows the rows and restores focus on [focusKey]. */
    private fun load(title: String, focusKey: String?, emptyMessage: String, producer: () -> List<Row>) {
        val requested = ++generation
        titleView.text = title
        executor.execute {
            val rows = producer()
            runOnUiThread {
                if (isDestroyed || requested != generation) return@runOnUiThread
                adapter.setRows(rows)
                if (rows.isEmpty()) showMessage(emptyMessage) else messageView.visibility = View.GONE
                listView.setSelection(adapter.indexOfKey(focusKey).coerceAtLeast(0))
                listView.requestFocus()
            }
        }
    }

    private fun showMessage(text: String) {
        messageView.text = text
        messageView.visibility = View.VISIBLE
    }

    private fun onVideoSelected(video: File) {
        val progress = positions.get(video)
        if (progress == null) {
            play(video, positionMs = 0L)
            return
        }
        val choices = arrayOf(
            getString(R.string.video_resume_from, formatDuration(progress.positionMs)),
            getString(R.string.video_from_start),
        )
        tvDialog()
            .setTitle(video.nameWithoutExtension)
            .setItems(choices) { _, which -> play(video, if (which == 0) progress.positionMs else 0L) }
            .show()
    }

    private fun play(video: File, positionMs: Long) {
        if (VlcLauncher.play(this, video, positionMs)) {
            playingPath = video.absolutePath
        } else {
            toast(R.string.vlc_not_installed, long = true)
        }
    }

    companion object {
        private const val EXTRA_DIR = "dir"

        /** Opens the file browser directly in folder [dir] (e.g. the root of a USB stick). */
        fun intent(context: Context, dir: File): Intent =
            Intent(context, VideoBrowserActivity::class.java).putExtra(EXTRA_DIR, dir.absolutePath)

        private const val STATE_DIR = "dir"
        private const val STATE_PLAYING = "playing"
        private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts")
    }
}
