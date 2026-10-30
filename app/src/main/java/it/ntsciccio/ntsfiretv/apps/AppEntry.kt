// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.apps

import android.graphics.drawable.Drawable

/**
 * An installed, launchable app.
 *
 * @property visual TV banner (16:9) if the app has one, otherwise the classic icon.
 * @property isBanner true if [visual] is a banner, false if it is an icon.
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val visual: Drawable,
    val isBanner: Boolean,
)