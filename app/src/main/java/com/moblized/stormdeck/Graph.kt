package com.moblized.stormdeck

import android.content.Context
import com.moblized.stormdeck.data.Forecast
import com.moblized.stormdeck.data.PlacesStore
import com.moblized.stormdeck.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import java.io.File

/** The app's few shared objects. */
object Graph {
    lateinit var settings: SettingsStore
        private set
    lateinit var places: PlacesStore
        private set
    lateinit var cache: SnapshotCache
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ready = false

    fun init(context: Context) {
        if (ready) return
        val app = context.applicationContext
        settings = SettingsStore(app)
        places = PlacesStore(app)
        cache = SnapshotCache(app)
        ready = true
    }
}

/**
 * The last forecast, kept on disk as Open-Meteo's own JSON, so the widget, the car screen and a cold start have
 * something to show right away.
 */
class SnapshotCache(context: Context) {
    private val dir = File(context.filesDir, "snapshots").apply { mkdirs() }

    @Volatile
    var forecast: Forecast? = null
        private set

    fun saveForecast(placeId: String, json: String, f: Forecast) {
        forecast = f
        runCatching {
            File(dir, "forecast.json").writeText(
                JSONObject().put("place", placeId).put("model", f.model.name).put("at", f.fetchedAt).put("body", json).toString(),
            )
        }
    }

    /** Returns the stored forecast JSON and when it was fetched, if it belongs to [placeId]. */
    fun loadForecastJson(placeId: String): Triple<String, String, Long>? = runCatching {
        val o = JSONObject(File(dir, "forecast.json").readText())
        if (o.optString("place") != placeId) return null
        Triple(o.getString("body"), o.optString("model"), o.optLong("at"))
    }.getOrNull()

    fun setForecast(f: Forecast) {
        forecast = f
    }
}
