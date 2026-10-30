// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast

/* Small helpers shared by the screens: dialogs and messages with the same look everywhere. */

/** Dark-style dialog used throughout the app (Material, readable from a distance). */
fun Activity.tvDialog(): AlertDialog.Builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

fun Context.toast(message: CharSequence, long: Boolean = false) {
    Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

fun Context.toast(messageRes: Int, long: Boolean = false) = toast(getString(messageRes), long)
