// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.system

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager

/**
 * Read permission for shared storage and USB drives (wallpapers, videos).
 * On API 25 it is a runtime permission: requested from the app or via `adb shell pm grant`.
 */
object StoragePermission {

    const val REQUEST_CODE = 100

    private val PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

    /** `true` if already granted; otherwise requests it (result in onRequestPermissionsResult) and returns false. */
    fun ensure(activity: Activity): Boolean {
        if (isGranted(activity)) return true
        request(activity)
        return false
    }

    fun request(activity: Activity) {
        activity.requestPermissions(PERMISSIONS, REQUEST_CODE)
    }

    /** `true` if the [Activity.onRequestPermissionsResult] answer grants the permission. */
    fun isGrantedResult(requestCode: Int, grantResults: IntArray): Boolean =
        requestCode == REQUEST_CODE &&
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
}
