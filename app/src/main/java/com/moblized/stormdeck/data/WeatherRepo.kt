package com.moblized.stormdeck.data

import android.content.Context
import com.moblized.stormdeck.Graph

/** Fetching shared by the app, the widget, the alert checks and the car screen. */
object WeatherRepo {
    private fun cacheKey(place: Place, s: AppSettings) =
        "${place.id}|${"%.3f".format(java.util.Locale.US, place.lat)},${"%.3f".format(java.util.Locale.US, place.lon)}|${s.temp}|${s.wind}|${s.precip}|${s.model}"

    suspend fun forecast(place: Place, s: AppSettings): Forecast {
        val url = OpenMeteo.url(place.lat, place.lon, s)
        // One retry: the free forecast server is occasionally slow to answer.
        val body = try {
            Http.text(url)
        } catch (e: java.io.IOException) {
            kotlinx.coroutines.delay(1500)
            Http.text(url)
        }
        val f = OpenMeteo.parse(body, s.model)
        Graph.cache.saveForecast(cacheKey(place, s), body, f)
        return f
    }

    /** The stored forecast for this place and these units, if there is one. */
    fun cached(place: Place, s: AppSettings): Forecast? {
        val (body, _, at) = Graph.cache.loadForecastJson(cacheKey(place, s)) ?: return null
        return runCatching { OpenMeteo.parse(body, s.model, at) }.getOrNull()?.also { Graph.cache.setForecast(it) }
    }

    /** Finds the phone, names the spot and stores it as "current". Null without permission or a fix. */
    suspend fun refreshCurrentPlace(context: Context): Place? {
        val loc = PhoneLocation.locate(context) ?: return null
        val old = Graph.places.current.value
        // Only look the name up again after moving a few km.
        val (name, region) = if (old != null && Geo.distanceKm(old.point, LatLon(loc.latitude, loc.longitude)) < 3.0) {
            old.name to old.region
        } else {
            PhoneLocation.name(context, loc.latitude, loc.longitude)
        }
        val p = Place(Place.CURRENT, name, region, loc.latitude, loc.longitude)
        Graph.places.setCurrent(p)
        return p
    }
}
