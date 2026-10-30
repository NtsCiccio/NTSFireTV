// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Weather below the clock, from Open-Meteo (free, no account or key).
 * Off until a city is chosen in "Impostazioni → Meteo". The service only receives
 * the city name (once, to find its coordinates) and then the coordinates, every 30 minutes.
 * Network calls must be made off the UI thread.
 */
object Weather {

    data class Place(val name: String, val latitude: Double, val longitude: Double)

    data class Current(val temperature: Int, val description: String)

    const val PREFS_NAME = "weather"
    private const val TAG = "NTSFireTV"
    private const val KEY_NAME = "name"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_TEMP = "temp"
    private const val KEY_CODE = "code"
    private const val KEY_FETCHED = "fetched_at"
    private const val REFRESH_MS = 30L * 60 * 1000
    /** After this time the old data is no longer shown. */
    private const val MAX_AGE_MS = 3L * 60 * 60 * 1000
    private const val TIMEOUT_MS = 8_000

    fun place(context: Context): Place? {
        val prefs = prefs(context)
        val name: String = prefs.getString(KEY_NAME, null) ?: return null
        return Place(name, prefs.getFloat(KEY_LAT, 0f).toDouble(), prefs.getFloat(KEY_LON, 0f).toDouble())
    }

    /** Sets the city; null turns the weather off. */
    fun setPlace(context: Context, place: Place?) {
        val editor = prefs(context).edit().clear()
        if (place != null) {
            editor.putString(KEY_NAME, place.name)
                .putFloat(KEY_LAT, place.latitude.toFloat())
                .putFloat(KEY_LON, place.longitude.toFloat())
        }
        editor.apply()
    }

    /** Last downloaded data, if not too old. */
    fun cached(context: Context): Current? {
        val prefs = prefs(context)
        val fetched = prefs.getLong(KEY_FETCHED, 0L)
        if (place(context) == null || System.currentTimeMillis() - fetched > MAX_AGE_MS) return null
        return Current(prefs.getInt(KEY_TEMP, 0), describe(prefs.getInt(KEY_CODE, -1)))
    }

    fun needsRefresh(context: Context): Boolean =
        place(context) != null && System.currentTimeMillis() - prefs(context).getLong(KEY_FETCHED, 0L) > REFRESH_MS

    /** Searches a city by name (network). @return the first one found, or null. */
    fun search(name: String): Place? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=it&format=json&name=" +
                URLEncoder.encode(name, "UTF-8")
        val json = get(url) ?: return null
        val result = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        val label = listOf(result.optString("name"), result.optString("admin1"))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
        return Place(label.ifBlank { name }, result.optDouble("latitude"), result.optDouble("longitude"))
    }

    /** Downloads the current weather and saves it (network). */
    fun refresh(context: Context): Current? {
        val place = place(context) ?: return null
        val url = String.format(
            Locale.ROOT,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&timezone=auto",
            place.latitude,
            place.longitude,
        )
        val current = get(url)?.optJSONObject("current") ?: return null
        val temperature = current.optDouble("temperature_2m", Double.NaN)
        if (temperature.isNaN()) return null
        val code = current.optInt("weather_code", -1)
        prefs(context).edit()
            .putInt(KEY_TEMP, temperature.roundToInt())
            .putInt(KEY_CODE, code)
            .putLong(KEY_FETCHED, System.currentTimeMillis())
            .apply()
        return Current(temperature.roundToInt(), describe(code))
    }

    /** WMO weather codes used by Open-Meteo; empty string for unknown codes. */
    internal fun describe(code: Int): String = when (code) {
        0 -> "Sereno"
        1, 2 -> "Poco nuvoloso"
        3 -> "Nuvoloso"
        45, 48 -> "Nebbia"
        in 51..57 -> "Pioggerella"
        in 61..67 -> "Pioggia"
        in 71..77 -> "Neve"
        in 80..82 -> "Rovesci"
        85, 86 -> "Rovesci di neve"
        in 95..99 -> "Temporale"
        else -> ""
    }

    private fun get(url: String): JSONObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: IOException) {
            Log.w(TAG, "Meteo non disponibile", e)
            null
        } catch (e: JSONException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
