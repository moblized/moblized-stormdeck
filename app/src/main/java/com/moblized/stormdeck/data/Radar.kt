package com.moblized.stormdeck.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class RadarSource(val label: String) {
    UsMosaic("US NEXRAD"),
    Station("Station"),
    Global("Global"),
    Off("Off"),
}

enum class Legend { Reflectivity, Velocity, Global }

data class RadarFrame(val timeMillis: Long, val url: String)

/** An animation loop: one tile URL template per frame, oldest first. */
data class FrameSet(
    val source: RadarSource,
    val frames: List<RadarFrame>,
    val tileSize: Int,
    val maxZoom: Float,
    val attribution: String,
    val legend: Legend,
    val title: String,
) {
    val key: String get() = source.name + ":" + title + ":" + frames.joinToString(",") { it.timeMillis.toString() }
}

data class NexradSite(val id: String, val name: String, val state: String, val lat: Double, val lon: Double) {
    val point get() = LatLon(lat, lon)

    /** The radar's full ICAO name: KOKX in the lower 48, PAHG in Alaska, PHKI in Hawaii, TJUA in Puerto Rico. */
    val callSign: String
        get() = when (state) {
            "AK", "HI", "GU" -> "P$id"
            "PR" -> "T$id"
            else -> "K$id"
        }
}

data class RadarProduct(val id: String, val name: String)

object Radar {
    private const val IEM = "https://mesonet.agron.iastate.edu"
    private val iemTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).withZone(ZoneOffset.UTC)
    private val ridgeStamp = DateTimeFormatter.ofPattern("yyyyMMddHHmm", Locale.US).withZone(ZoneOffset.UTC)

    // ---- Global composite (RainViewer: free for personal use, zoom ≤ 7, Universal Blue colors) ----

    suspend fun global(): FrameSet = parseRainViewer(Http.text("https://api.rainviewer.com/public/weather-maps.json"))

    fun parseRainViewer(body: String): FrameSet {
        val o = JSONObject(body)
        val host = o.optString("host", "https://tilecache.rainviewer.com")
        val past = o.optJSONObject("radar")?.optJSONArray("past") ?: JSONArray()
        val frames = (0 until past.length()).map { past.getJSONObject(it) }
            .map { RadarFrame(it.optLong("time") * 1000, host + it.optString("path") + "/512/{z}/{x}/{y}/2/1_1.png") }
            // Their free tier allows 100 tile requests a minute, so a shorter loop keeps us well inside it.
            .takeLast(7)
        return FrameSet(RadarSource.Global, frames, 512, 7f, "Radar © RainViewer", Legend.Global, "Global radar")
    }

    // ---- NEXRAD via the Iowa Environmental Mesonet (US mosaic and single stations) ----

    private fun listUrl(radar: String, product: String, minutes: Long): String {
        val end = Instant.now()
        val start = end.minusSeconds(minutes * 60)
        return "$IEM/json/radar.py?operation=list&radar=$radar&product=$product&start=${iemTime.format(start)}&end=${iemTime.format(end)}"
    }

    fun parseScans(body: String): List<Long> {
        val scans = JSONObject(body).optJSONArray("scans") ?: return emptyList()
        return (0 until scans.length()).mapNotNull { i ->
            val ts = scans.getJSONObject(i).optString("ts")
            runCatching { LocalDateTime.parse(ts.removeSuffix("Z")).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }.sorted()
    }

    fun ridgeUrl(radar: String, product: String, timeMillis: Long): String =
        "$IEM/c/tile.py/1.0.0/ridge::$radar-$product-${ridgeStamp.format(Instant.ofEpochMilli(timeMillis))}/{z}/{x}/{y}.png"

    suspend fun usMosaic(): FrameSet {
        val times = runCatching { parseScans(Http.text(listUrl("USCOMP", "N0Q", 60))) }.getOrDefault(emptyList()).takeLast(12)
        val frames = if (times.isNotEmpty()) {
            times.map { RadarFrame(it, ridgeUrl("USCOMP", "N0Q", it)) }
        } else {
            // Fallback: the rolling "N minutes ago" mosaics.
            val now = System.currentTimeMillis() / 300_000 * 300_000
            (55 downTo 0 step 5).map { m ->
                val suffix = if (m == 0) "" else "-m%02dm".format(m)
                RadarFrame(now - m * 60_000L, "$IEM/cache/tile.py/1.0.0/nexrad-n0q-900913$suffix/{z}/{x}/{y}.png?t=${now / 60_000}")
            }
        }
        return FrameSet(RadarSource.UsMosaic, frames, 256, 9f, "NEXRAD via Iowa Environmental Mesonet", Legend.Reflectivity, "US NEXRAD mosaic")
    }

    suspend fun station(site: NexradSite, product: RadarProduct): FrameSet {
        val times = parseScans(Http.text(listUrl(site.id, product.id, 80))).takeLast(12)
        val frames = times.map { RadarFrame(it, ridgeUrl(site.id, product.id, it)) }
        val legend = if (product.id.startsWith("N0S") || product.id.startsWith("N0U") || product.id.endsWith("V")) Legend.Velocity else Legend.Reflectivity
        return FrameSet(RadarSource.Station, frames, 256, 12f, "NWS NEXRAD ${site.callSign} via Iowa Environmental Mesonet", legend, "${site.callSign} ${product.name}")
    }

    suspend fun products(site: NexradSite): List<RadarProduct> {
        val body = Http.text(listUrl(site.id, "N0B", 120).replace("operation=list", "operation=products").replace("&product=N0B", ""))
        return parseProducts(body).ifEmpty { listOf(RadarProduct("N0B", "Base Reflectivity (Super Res)")) }
    }

    fun parseProducts(body: String): List<RadarProduct> {
        val a = JSONObject(body).optJSONArray("products") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { RadarProduct(it.optString("id"), it.optString("name")) }
            .sortedBy { order.indexOf(it.id).let { i -> if (i < 0) 99 else i } }
    }

    private val order = listOf("N0B", "N0S", "N0U", "N0Q", "N0C", "N0X", "N0H", "EET", "NET", "DAA", "DTA")

    // ---- Station list, cached for a week ----

    private var sitesCache: List<NexradSite>? = null

    suspend fun sites(context: Context): List<NexradSite> {
        sitesCache?.let { return it }
        val file = File(context.filesDir, "nexrad.geojson")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7L * 86_400_000
        val body = if (fresh) {
            withContext(Dispatchers.IO) { file.readText() }
        } else {
            runCatching { Http.text("$IEM/geojson/network/NEXRAD.geojson") }
                .onSuccess { b -> withContext(Dispatchers.IO) { file.writeText(b) } }
                .getOrElse { if (file.exists()) withContext(Dispatchers.IO) { file.readText() } else throw it }
        }
        return parseSites(body).also { sitesCache = it }
    }

    fun parseSites(body: String): List<NexradSite> {
        val f = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until f.length()).mapNotNull { i ->
            val feat = f.getJSONObject(i)
            val p = feat.optJSONObject("properties") ?: return@mapNotNull null
            if (p.has("online") && !p.optBoolean("online", true)) return@mapNotNull null
            val c = feat.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val id = p.optString("sid").ifBlank { feat.optString("id") }
            NexradSite(id, p.optString("sname"), p.optString("state"), c.optDouble(1), c.optDouble(0))
        }
    }

    fun nearest(sites: List<NexradSite>, p: LatLon): NexradSite? = sites.minByOrNull { Geo.distanceKm(it.point, p) }

    /** "KOKX" (NWS) → "OKX" (IEM). */
    fun iemId(nwsRadar: String): String = if (nwsRadar.length == 4) nwsRadar.substring(1) else nwsRadar
}

enum class SatelliteMode(val label: String) { Off("Off"), Infrared("Infrared"), GeoColor("GeoColor") }

/** NASA GIBS imagery, through its WMS so any zoom level works. */
object Gibs {
    private const val WMS = "https://gibs.earthdata.nasa.gov/wms/epsg3857/best/wms.cgi"
    private val isoMinute = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:00'Z'", Locale.US).withZone(ZoneOffset.UTC)

    fun layers(mode: SatelliteMode): List<String> = when (mode) {
        SatelliteMode.Off -> emptyList()
        SatelliteMode.Infrared -> listOf(
            "Himawari_AHI_Band13_Clean_Infrared",
            "GOES-West_ABI_Band13_Clean_Infrared",
            "GOES-East_ABI_Band13_Clean_Infrared",
        )
        SatelliteMode.GeoColor -> listOf("GOES-West_ABI_GeoColor", "GOES-East_ABI_GeoColor")
    }

    /** Geostationary images arrive 40+ minutes late; ask for one that is surely there. */
    fun latestGeoTime(now: Long = System.currentTimeMillis(), lagMinutes: Int = 50): String {
        val t = (now - lagMinutes * 60_000L) / 600_000L * 600_000L
        return isoMinute.format(Instant.ofEpochMilli(t))
    }

    /** Infrared shows up about 50 minutes behind real time; GeoColor takes about an hour. */
    fun geoTime(mode: SatelliteMode, now: Long = System.currentTimeMillis()): String =
        latestGeoTime(now, if (mode == SatelliteMode.GeoColor) 70 else 50)

    fun wms(layer: String, time: String): String =
        "$WMS?SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1&LAYERS=$layer&STYLES=&FORMAT=image/png&TRANSPARENT=TRUE" +
            "&SRS=EPSG:3857&WIDTH=256&HEIGHT=256&BBOX={bbox-epsg-3857}&TIME=$time"

    const val SST_ANOMALY = "GHRSST_L4_MUR_Sea_Surface_Temperature_Anomalies"

    /** The daily sea-surface temperature analysis lags a day or two. */
    fun sstDate(daysBack: Long = 2): String = LocalDate.now(ZoneOffset.UTC).minusDays(daysBack).toString()

    /** A plain lat/lon picture of the SST anomalies for one box (used on the El Niño page). */
    fun sstImage(west: Double, south: Double, east: Double, north: Double, width: Int, height: Int, date: String = sstDate()): String =
        "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1" +
            "&LAYERS=$SST_ANOMALY&STYLES=&FORMAT=image/png&TRANSPARENT=TRUE&SRS=EPSG:4326" +
            "&BBOX=$west,$south,$east,$north&WIDTH=$width&HEIGHT=$height&TIME=$date"
}

/** Current NWS watches and warnings, drawn by NOAA's map server. */
object NwsWarningsLayer {
    fun url(now: Long = System.currentTimeMillis()): String =
        "https://mapservices.weather.noaa.gov/eventdriven/rest/services/WWA/watch_warn_adv/MapServer/export" +
            "?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256&dpi=96&format=png32&transparent=true" +
            "&layers=show:1&f=image&_t=${now / 300_000}"
}
