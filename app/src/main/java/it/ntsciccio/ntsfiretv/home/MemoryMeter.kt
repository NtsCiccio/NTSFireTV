// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

/**
 * Free memory shown under the clock (idea from the dumbtv launcher, MIT): it only shows the numbers,
 * it closes and frees nothing. Pure functions, tested on the computer.
 */
object MemoryMeter {

    /** Below this share of free memory the meter turns amber. */
    private const val LOW_PERCENT = 15

    /** Memory in use, from 0 to 100, for the bar. */
    fun usedPercent(available: Long, total: Long): Int {
        if (total <= 0) return 0
        return (100 - available * 100 / total).toInt().coerceIn(0, 100)
    }

    /** Low memory: Android says so (`lowMemory`) or less than 15% is free. */
    fun isLow(available: Long, total: Long, lowMemory: Boolean): Boolean =
        lowMemory || (total > 0 && available * 100 < total * LOW_PERCENT)
}
