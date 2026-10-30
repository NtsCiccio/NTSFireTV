// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.system

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** `true` if [component] appears in the Settings.Secure list [key] (components separated by [separator]). */
internal fun isComponentListed(context: Context, key: String, separator: Char, component: ComponentName): Boolean {
    val value: String = Settings.Secure.getString(context.contentResolver, key) ?: return false
    return value.split(separator).any { ComponentName.unflattenFromString(it) == component }
}
