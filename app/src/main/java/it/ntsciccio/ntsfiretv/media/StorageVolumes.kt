// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.media

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/** A mounted volume: shared internal storage or USB stick/HDD. */
data class Volume(
    val label: String,
    val root: File,
    val isRemovable: Boolean,
)

/** Lists the mounted volumes by reading [StorageManager]. */
object StorageVolumes {

    fun list(context: Context): List<Volume> {
        val manager: StorageManager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes.mapNotNull { volume ->
            val state: String? = volume.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                return@mapNotNull null
            }
            val root: File = rootOf(volume) ?: return@mapNotNull null
            val description: String? = volume.getDescription(context)
            Volume(description ?: root.name, root, volume.isRemovable)
        }
    }

    /** Only USB sticks and drives. */
    fun removable(context: Context): List<Volume> = list(context).filter { it.isRemovable }

    /** The volume containing [file], or null if it is not on any mounted volume. */
    fun find(context: Context, file: File): Volume? {
        val path = file.absolutePath
        return list(context).firstOrNull { volume ->
            val root = volume.root.absolutePath
            path == root || path.startsWith("$root/")
        }
    }

    /**
     * Root folder of the volume. `StorageVolume.getDirectory()` exists only since API 30:
     * on Fire OS 6 (API 25) `getPath()` is used, hidden but unrestricted before Android 9.
     */
    private fun rootOf(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory
        return try {
            val path = volume.javaClass.getMethod("getPath").invoke(volume) as? String
            path?.let(::File)
        } catch (e: ReflectiveOperationException) {
            null
        }
    }
}
