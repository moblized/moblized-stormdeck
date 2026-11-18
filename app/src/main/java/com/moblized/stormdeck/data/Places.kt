package com.moblized.stormdeck.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class Place(
    val id: String,
    val name: String,
    val region: String,
    val lat: Double,
    val lon: Double,
) {
    val point get() = LatLon(lat, lon)
    val isCurrent get() = id == CURRENT

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("region", region).put("lat", lat).put("lon", lon)

    companion object {
        const val CURRENT = "current"

        fun fromJson(o: JSONObject) = Place(
            o.optString("id"), o.optString("name"), o.optString("region"), o.optDouble("lat"), o.optDouble("lon"),
        )
    }
}

/**
 * Your places: where you are now (refreshed whenever the app opens) and any you saved.
 * The selected one drives the forecast, the widget and the alerts.
 */
class PlacesStore(context: Context) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    private val _current = MutableStateFlow(prefs.getString("current", null)?.let { runCatching { Place.fromJson(JSONObject(it)) }.getOrNull() })
    /** The last known spot of the phone. */
    val current: StateFlow<Place?> = _current.asStateFlow()

    private val _saved = MutableStateFlow(loadSaved())
    val saved: StateFlow<List<Place>> = _saved.asStateFlow()

    private val _selectedId = MutableStateFlow(prefs.getString("selected", Place.CURRENT) ?: Place.CURRENT)
    val selectedId: StateFlow<String> = _selectedId.asStateFlow()

    private fun loadSaved(): List<Place> = runCatching {
        val a = JSONArray(prefs.getString("saved", "[]"))
        (0 until a.length()).map { Place.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** The place the app is showing, or null before the first location fix. */
    fun selected(): Place? {
        val id = _selectedId.value
        return if (id == Place.CURRENT) _current.value else _saved.value.firstOrNull { it.id == id } ?: _current.value
    }

    fun setCurrent(p: Place) {
        _current.value = p
        prefs.edit().putString("current", p.toJson().toString()).apply()
    }

    fun select(id: String) {
        _selectedId.value = id
        prefs.edit().putString("selected", id).apply()
    }

    fun add(p: Place) {
        val list = _saved.value.filterNot { it.id == p.id } + p
        saveList(list)
        select(p.id)
    }

    fun remove(id: String) {
        saveList(_saved.value.filterNot { it.id == id })
        if (_selectedId.value == id) select(Place.CURRENT)
    }

    private fun saveList(list: List<Place>) {
        _saved.value = list
        prefs.edit().putString("saved", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }
}

object PlaceSearch {
    suspend fun search(query: String): List<Place> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val body = Http.text("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=12&language=en&format=json")
        return parse(body)
    }

    fun parse(body: String): List<Place> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val o = results.getJSONObject(i)
            val region = listOf(o.optString("admin1"), o.optString("country"))
                .filter { it.isNotBlank() && it != "null" }
                .joinToString(", ")
            Place(
                id = "om-" + o.optLong("id"),
                name = o.optString("name"),
                region = region,
                lat = o.optDouble("latitude"),
                lon = o.optDouble("longitude"),
            )
        }
    }
}

object PhoneLocation {
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A fresh fix if one comes within a few seconds, otherwise the best recent one. */
    @SuppressLint("MissingPermission")
    suspend fun locate(context: Context): Location? {
        if (!allowed(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        for (provider in providers) {
            val fix = withTimeoutOrNull(8_000) { currentFix(lm, provider) }
            if (fix != null) return fix
        }
        return lastKnown(lm)
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFix(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, executor) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    private val executor = Executors.newSingleThreadExecutor()

    /** "Springfield, Missouri" for a spot, or a plain description if the phone can't name it. */
    @Suppress("DEPRECATION")
    suspend fun name(context: Context, lat: Double, lon: Double): Pair<String, String> = withContext(Dispatchers.IO) {
        val a = runCatching {
            if (!Geocoder.isPresent()) null else Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
        }.getOrNull()
        val name = a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: "My location"
        val region = listOfNotNull(a?.adminArea?.takeIf { it != name }, a?.countryCode).joinToString(", ")
        name to region
    }
}
