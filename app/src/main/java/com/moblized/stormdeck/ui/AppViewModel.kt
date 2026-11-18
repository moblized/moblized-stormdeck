package com.moblized.stormdeck.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moblized.stormdeck.Graph
import com.moblized.stormdeck.alerts.SyncWorker
import com.moblized.stormdeck.data.Enso
import com.moblized.stormdeck.data.EnsoSource
import com.moblized.stormdeck.data.Forecast
import com.moblized.stormdeck.data.FrameSet
import com.moblized.stormdeck.data.Geo
import com.moblized.stormdeck.data.LatLon
import com.moblized.stormdeck.data.NexradSite
import com.moblized.stormdeck.data.Nws
import com.moblized.stormdeck.data.NwsPeriod
import com.moblized.stormdeck.data.NwsPoint
import com.moblized.stormdeck.data.Outlook
import com.moblized.stormdeck.data.Place
import com.moblized.stormdeck.data.PlaceSearch
import com.moblized.stormdeck.data.Radar
import com.moblized.stormdeck.data.RadarProduct
import com.moblized.stormdeck.data.RadarSource
import com.moblized.stormdeck.data.SatelliteMode
import com.moblized.stormdeck.data.Storm
import com.moblized.stormdeck.data.StormTrack
import com.moblized.stormdeck.data.Tropics
import com.moblized.stormdeck.data.WeatherAlert
import com.moblized.stormdeck.data.WeatherRepo
import com.moblized.stormdeck.data.friendlyError
import com.moblized.stormdeck.widget.WeatherWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Something fetched from the network: the last good value, whether a fetch is running, and the last error. */
data class Remote<T>(val value: T? = null, val loading: Boolean = false, val error: String? = null) {
    fun loading() = copy(loading = true, error = null)
    fun failed(e: Throwable) = copy(loading = false, error = friendlyError(e))
}

enum class Tab(val label: String) { Radar("Radar"), Forecast("Forecast"), Tropics("Tropics"), Enso("El Niño"), Settings("Settings") }

/** Where the map should fly to next; [serial] makes repeated requests for the same spot still move it. */
data class MapFocus(val point: LatLon, val zoom: Double, val serial: Long = System.nanoTime())

data class NwsInfo(val point: NwsPoint, val periods: List<NwsPeriod>, val discussion: String?)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val radarPrefs = app.getSharedPreferences("radar", Context.MODE_PRIVATE)

    var tab by mutableStateOf(Tab.Radar)

    var place by mutableStateOf(Graph.places.selected())
        private set
    var forecast by mutableStateOf(Remote<Forecast>())
        private set
    var alerts by mutableStateOf<List<WeatherAlert>>(emptyList())
        private set
    var nws by mutableStateOf<NwsInfo?>(null)
        private set
    var locating by mutableStateOf(false)
        private set

    // ---- Radar ----
    var radarSource by mutableStateOf(
        radarPrefs.getString("source", null)?.let { n -> RadarSource.entries.firstOrNull { it.name == n } } ?: RadarSource.UsMosaic,
    )
        private set
    var frames by mutableStateOf(Remote<FrameSet>())
        private set
    var sites by mutableStateOf<List<NexradSite>>(emptyList())
        private set
    var site by mutableStateOf<NexradSite?>(null)
        private set
    var products by mutableStateOf<List<RadarProduct>>(emptyList())
        private set
    var product by mutableStateOf(RadarProduct(radarPrefs.getString("product", "N0B") ?: "N0B", "Base Reflectivity (Super Res)"))
        private set
    var satellite by mutableStateOf(
        radarPrefs.getString("sat", null)?.let { n -> SatelliteMode.entries.firstOrNull { it.name == n } } ?: SatelliteMode.Off,
    )
        private set
    var showWarnings by mutableStateOf(radarPrefs.getBoolean("warnings", true))
        private set
    var showStorms by mutableStateOf(radarPrefs.getBoolean("storms", true))
        private set
    var showSst by mutableStateOf(radarPrefs.getBoolean("sst", false))
        private set
    var focus by mutableStateOf<MapFocus?>(null)
        private set
    var playing by mutableStateOf(true)

    // ---- Tropics ----
    var storms by mutableStateOf(Remote<List<Storm>>())
        private set
    var tracks by mutableStateOf<Map<String, StormTrack>>(emptyMap())
        private set
    var selectedStorm by mutableStateOf<String?>(null)
    var outlooks by mutableStateOf<List<Outlook>>(emptyList())
        private set

    // ---- ENSO ----
    var enso by mutableStateOf(Remote<Enso>())
        private set

    // ---- Place search ----
    var searchResults by mutableStateOf<List<Place>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    init {
        place?.let { p -> WeatherRepo.cached(p, Graph.settings.value)?.let { forecast = Remote(it) } }
        viewModelScope.launch {
            Graph.places.selectedId.drop(1).collect { onPlaceChanged() }
        }
        viewModelScope.launch {
            Graph.settings.settings
                .map { listOf(it.temp, it.wind, it.precip, it.model) }
                .distinctUntilChanged()
                .drop(1)
                // Units or model changed: fetch the forecast again in the new units.
                .collect { refreshForecast() }
        }
        refreshAll()
    }

    fun refreshAll() {
        locate()
        refreshForecast()
        refreshFrames()
        loadTropics()
        loadEnso()
    }

    /** Asks the phone where it is; if the selected place is "current", the forecast follows. */
    fun locate() {
        if (locating) return
        locating = true
        viewModelScope.launch {
            val before = Graph.places.current.value
            val p = runCatching { WeatherRepo.refreshCurrentPlace(ctx) }.getOrNull()
            locating = false
            if (p != null && Graph.places.selectedId.value == Place.CURRENT) {
                val moved = before == null || Geo.distanceKm(before.point, p.point) > 1.0
                place = p
                if (moved || forecast.value == null) onPlaceChanged()
                if (before == null) focus = MapFocus(p.point, 7.0)
            }
        }
    }

    private fun onPlaceChanged() {
        val p = Graph.places.selected()
        place = p
        nws = null
        alerts = emptyList()
        if (p != null) {
            WeatherRepo.cached(p, Graph.settings.value)?.let { forecast = Remote(it) } ?: run { forecast = Remote() }
            // A first visit to a US spot starts on NEXRAD, elsewhere on global radar.
            if (!radarPrefs.contains("source")) {
                radarSource = if (Geo.inUsRadarArea(p.point)) RadarSource.UsMosaic else RadarSource.Global
            }
            focus = MapFocus(p.point, 7.0)
            site = null
        }
        refreshForecast()
        refreshFrames()
    }

    fun refreshForecast() {
        val p = place ?: Graph.places.selected() ?: return
        place = p
        forecast = forecast.loading()
        viewModelScope.launch {
            val s = Graph.settings.value
            runCatching { WeatherRepo.forecast(p, s) }
                .onSuccess {
                    forecast = Remote(it)
                    runCatching { WeatherWidget().updateAll(ctx) }
                }
                .onFailure { forecast = forecast.failed(it) }
        }
        if (Geo.inUsRadarArea(p.point)) {
            viewModelScope.launch {
                alerts = runCatching { Nws.alerts(p.lat, p.lon) }.getOrDefault(alerts)
            }
            viewModelScope.launch {
                val point = runCatching { Nws.point(p.lat, p.lon) }.getOrNull() ?: return@launch
                val periods = async { runCatching { Nws.periods(point) }.getOrDefault(emptyList()) }
                val afd = async { runCatching { Nws.discussion(point.office) }.getOrNull() }
                nws = NwsInfo(point, periods.await(), afd.await())
                // The grid point names this place's own radar; use it for station mode.
                if (site == null && point.radar.isNotBlank()) {
                    val id = Radar.iemId(point.radar)
                    ensureSites()
                    sites.firstOrNull { it.id == id }?.let { if (site == null) site = it }
                }
            }
        }
    }

    // ---- Radar ----

    fun setSource(s: RadarSource) {
        radarSource = s
        radarPrefs.edit().putString("source", s.name).apply()
        refreshFrames()
    }

    fun setSatellite(m: SatelliteMode) {
        satellite = m
        radarPrefs.edit().putString("sat", m.name).apply()
    }

    fun toggleWarnings(on: Boolean) {
        showWarnings = on
        radarPrefs.edit().putBoolean("warnings", on).apply()
    }

    fun toggleStorms(on: Boolean) {
        showStorms = on
        radarPrefs.edit().putBoolean("storms", on).apply()
    }

    fun toggleSst(on: Boolean) {
        showSst = on
        radarPrefs.edit().putBoolean("sst", on).apply()
        if (on) focus = MapFocus(LatLon(0.0, -140.0), 2.2)
    }

    private suspend fun ensureSites() {
        if (sites.isEmpty()) sites = runCatching { Radar.sites(ctx) }.getOrDefault(emptyList())
    }

    fun selectSite(s: NexradSite) {
        site = s
        if (radarSource != RadarSource.Station) {
            radarSource = RadarSource.Station
            radarPrefs.edit().putString("source", RadarSource.Station.name).apply()
        }
        focus = MapFocus(s.point, 8.0)
        refreshFrames()
    }

    fun selectProduct(p: RadarProduct) {
        product = p
        radarPrefs.edit().putString("product", p.id).apply()
        refreshFrames()
    }

    private var framesJob: Job? = null

    fun refreshFrames() {
        framesJob?.cancel()
        frames = frames.loading()
        framesJob = viewModelScope.launch {
            runCatching {
                when (radarSource) {
                    RadarSource.Off -> null
                    RadarSource.Global -> Radar.global()
                    RadarSource.UsMosaic -> Radar.usMosaic()
                    RadarSource.Station -> {
                        ensureSites()
                        val here = place?.point ?: LatLon(39.0, -96.0)
                        val s = site ?: Radar.nearest(sites, here)?.also { site = it }
                            ?: throw IllegalStateException("Couldn't load the NEXRAD station list.")
                        val list = runCatching { Radar.products(s) }.getOrDefault(emptyList())
                        products = list
                        val prod = list.firstOrNull { it.id == product.id } ?: list.firstOrNull() ?: product
                        product = prod
                        Radar.station(s, prod)
                    }
                }
            }.onSuccess { fs ->
                frames = Remote(fs)
                if (fs != null && fs.frames.isEmpty()) frames = Remote(fs, error = "No recent scans from this radar.")
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) frames = frames.failed(it) }
        }
    }

    /** Keeps the loop fresh while the radar is on screen. */
    suspend fun keepFramesFresh() {
        while (true) {
            delay(4 * 60_000L)
            refreshFrames()
        }
    }

    fun focusOn(p: LatLon, zoom: Double) {
        focus = MapFocus(p, zoom)
    }

    // ---- Tropics ----

    fun loadTropics() {
        storms = storms.loading()
        viewModelScope.launch {
            runCatching { Tropics.active() }
                .onSuccess { list ->
                    storms = Remote(list)
                    list.forEach { st ->
                        launch {
                            runCatching { Tropics.track(st) }.onSuccess { t -> tracks = tracks + (st.key to t) }
                        }
                    }
                }
                .onFailure { storms = storms.failed(it) }
        }
        viewModelScope.launch {
            outlooks = runCatching { Tropics.outlooks() }.getOrDefault(outlooks)
        }
    }

    fun showStormOnMap(st: Storm) {
        selectedStorm = st.key
        if (!showStorms) toggleStorms(true)
        focus = MapFocus(st.point, 4.5)
        tab = Tab.Radar
    }

    // ---- ENSO ----

    fun loadEnso() {
        enso = enso.loading()
        viewModelScope.launch {
            runCatching { EnsoSource.load() }
                .onSuccess { enso = Remote(it) }
                .onFailure { enso = enso.failed(it) }
        }
    }

    // ---- Places ----

    fun search(q: String) {
        searchJob?.cancel()
        if (q.length < 2) {
            searchResults = emptyList()
            searching = false
            return
        }
        searching = true
        searchJob = viewModelScope.launch {
            delay(300)
            searchResults = runCatching { PlaceSearch.search(q) }.getOrDefault(emptyList())
            searching = false
        }
    }

    fun choose(p: Place) {
        if (p.isCurrent) Graph.places.select(Place.CURRENT) else Graph.places.add(p)
        searchResults = emptyList()
        SyncWorker.runSoon(ctx)
    }

    fun removePlace(p: Place) = Graph.places.remove(p.id)
}
