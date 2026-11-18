package com.moblized.stormdeck.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

enum class Basin(val label: String, val nhc: Boolean) {
    Atlantic("North Atlantic", true),
    EastPacific("East Pacific", true),
    CentralPacific("Central Pacific", true),
    WestPacific("West Pacific", false),
    NorthIndian("North Indian", false),
    SouthIndian("South Indian", false),
    SouthPacific("South Pacific / Australia", false),
    ;

    companion object {
        fun of(lat: Double, lon: Double): Basin {
            val l = if (lon > 180) lon - 360 else lon
            return if (lat >= 0) {
                when {
                    l in -100.0..0.0 && !(l < -83 && lat < 16) -> Atlantic
                    l in -140.0..-75.0 -> EastPacific
                    l in -180.0..-140.0 -> CentralPacific
                    l in 100.0..180.0 -> WestPacific
                    l in 30.0..100.0 -> NorthIndian
                    else -> Atlantic
                }
            } else {
                if (l in 20.0..135.0) SouthIndian else SouthPacific
            }
        }
    }
}

/** Saffir–Simpson style category from 1-minute sustained wind in knots. */
enum class StormCategory(val short: String, val color: Long) {
    TD("TD", 0xFF5EBAFF),
    TS("TS", 0xFF00FAF4),
    C1("Cat 1", 0xFFFFF795),
    C2("Cat 2", 0xFFFFD821),
    C3("Cat 3", 0xFFFF8F20),
    C4("Cat 4", 0xFFFF6060),
    C5("Cat 5", 0xFFC464D9),
    ;

    val major get() = ordinal >= C3.ordinal

    companion object {
        fun of(kt: Double): StormCategory = when {
            kt < 34 -> TD
            kt < 64 -> TS
            kt < 83 -> C1
            kt < 96 -> C2
            kt < 113 -> C3
            kt < 137 -> C4
            else -> C5
        }
    }
}

data class Storm(
    val key: String,
    val name: String,
    val windKt: Double,
    val pressureMb: Int?,
    val lat: Double,
    val lon: Double,
    val basin: Basin,
    val source: String,
    /** "WNW at 12 mph", when the agency gives it. */
    val movement: String?,
    val updated: Long,
    val alertLevel: String?,
    val countries: String,
    val gdacsGeometry: String?,
    val links: List<Pair<String, String>>,
    val nhcCone: String? = null,
    val nhcTrack: String? = null,
    val nhcBestTrack: String? = null,
    /** NHC's own classification code (TD, TS, HU, PTC…) when known. */
    val nhcClass: String? = null,
) {
    val point get() = LatLon(lat, lon)
    val category get() = StormCategory.of(windKt)

    val type: String
        get() {
            if (nhcClass == "PTC") return "Potential Tropical Cyclone"
            if (nhcClass == "STD") return "Subtropical Depression"
            if (nhcClass == "STS") return "Subtropical Storm"
            if (nhcClass == "PC") return "Post-Tropical Cyclone"
            return when (category) {
                StormCategory.TD -> "Tropical Depression"
                StormCategory.TS -> "Tropical Storm"
                else -> when (basin) {
                    Basin.WestPacific -> if (windKt >= 130) "Super Typhoon" else "Typhoon"
                    Basin.NorthIndian, Basin.SouthIndian, Basin.SouthPacific -> "Cyclone"
                    else -> if (category.major) "Major Hurricane" else "Hurricane"
                }
            }
        }
}

data class TrackPoint(val lat: Double, val lon: Double, val label: String, val timeMillis: Long, val forecast: Boolean)

data class StormTrack(
    val past: List<List<LatLon>>,
    val forecast: List<List<LatLon>>,
    val cone: List<List<LatLon>>,
    val points: List<TrackPoint>,
) {
    val isEmpty get() = past.isEmpty() && forecast.isEmpty() && cone.isEmpty() && points.isEmpty()

    /** 0 when you're inside the cone, otherwise how close the forecast track gets to you. */
    fun closestKm(p: LatLon, now: LatLon? = null): Double {
        if (cone.any { Geo.inside(p, it) }) return 0.0
        var best = now?.let { Geo.distanceKm(p, it) } ?: Double.MAX_VALUE
        forecast.forEach { best = minOf(best, Geo.distanceToLineKm(p, it)) }
        points.filter { it.forecast }.forEach { best = minOf(best, Geo.distanceKm(p, LatLon(it.lat, it.lon))) }
        return best
    }

    companion object {
        val EMPTY = StormTrack(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

data class Outlook(val basin: String, val text: String)

object Tropics {
    private const val GDACS = "https://www.gdacs.org/gdacsapi/api"

    suspend fun active(): List<Storm> = coroutineScope {
        val g = async { runCatching { gdacs() } }
        val n = async { runCatching { nhc() } }
        val gd = g.await()
        val nh = n.await()
        if (gd.isFailure && nh.isFailure) throw gd.exceptionOrNull()!!
        merge(gd.getOrDefault(emptyList()), nh.getOrDefault(emptyList()))
    }

    fun merge(gdacs: List<Storm>, nhc: List<Storm>): List<Storm> {
        val out = ArrayList<Storm>()
        val used = HashSet<String>()
        for (n in nhc) {
            val match = gdacs.firstOrNull { it.name.equals(n.name, true) && Geo.distanceKm(it.point, n.point) < 800 }
            if (match != null) {
                used += match.key
                out += n.copy(
                    alertLevel = match.alertLevel,
                    countries = match.countries,
                    gdacsGeometry = match.gdacsGeometry,
                    links = n.links + match.links,
                )
            } else {
                out += n
            }
        }
        out += gdacs.filter { it.key !in used }
        return out.sortedByDescending { it.windKt }
    }

    // ---- GDACS: every basin, with JTWC / NOAA tracks and cones ----

    suspend fun gdacs(): List<Storm> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val url = "$GDACS/events/geteventlist/SEARCH?eventlist=TC&fromdate=${today.minusDays(21)}&todate=${today.plusDays(1)}"
        return parseGdacsList(Http.text(url))
    }

    fun parseGdacsList(body: String): List<Storm> {
        val f = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until f.length()).mapNotNull { i ->
            val feat = f.getJSONObject(i)
            val p = feat.optJSONObject("properties") ?: return@mapNotNull null
            if (p.optString("iscurrent").lowercase(Locale.US) != "true") return@mapNotNull null
            val c = feat.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val lon = c.optDouble(0)
            val lat = c.optDouble(1)
            val sev = p.optJSONObject("severitydata")
            val kmh = sev?.optDouble("severity", 0.0) ?: 0.0
            val unit = sev?.optString("severityunit").orEmpty()
            val kt = when {
                unit.contains("km", true) -> kmh / 1.852
                unit.contains("kt", true) || unit.contains("knot", true) -> kmh
                unit.contains("mph", true) -> kmh / 1.15078
                else -> kmh / 1.852
            }
            val rawName = p.optString("eventname").ifBlank { p.optString("name") }
            val urls = p.optJSONObject("url")
            val report = urls?.optString("report").orEmpty()
            Storm(
                key = "gdacs-" + p.optString("eventid"),
                name = prettyName(rawName),
                windKt = kt,
                pressureMb = null,
                lat = lat,
                lon = lon,
                basin = Basin.of(lat, lon),
                source = p.optString("source").ifBlank { "GDACS" },
                movement = null,
                updated = parseLocal(p.optString("datemodified")),
                alertLevel = p.optString("alertlevel").takeIf { it.isNotBlank() },
                countries = affected(p),
                gdacsGeometry = urls?.optString("geometry")?.takeIf { it.startsWith("http") },
                links = if (report.startsWith("http")) listOf("GDACS report" to report) else emptyList(),
            )
        }
    }

    private fun affected(p: JSONObject): String {
        val a = p.optJSONArray("affectedcountries") ?: return p.optString("country")
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("countryname") }
            .filter { it.isNotBlank() }.distinct().joinToString(", ")
    }

    /** "AKVAN-26" → "Akvan"; "ninety-five" style numbers stay readable. */
    fun prettyName(raw: String): String {
        val base = raw.substringBeforeLast('-').ifBlank { raw }.replace('_', ' ').trim()
        return base.lowercase(Locale.US).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }
    }

    private fun parseLocal(s: String): Long = runCatching {
        LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrDefault(0L)

    fun parseGdacsGeometry(body: String): StormTrack {
        val f = JSONObject(body).optJSONArray("features") ?: return StormTrack.EMPTY
        data class Seg(val time: Long, val coords: List<LatLon>, val label: String, val forecast: Boolean)
        val segs = ArrayList<Seg>()
        val cone = ArrayList<List<LatLon>>()
        for (i in 0 until f.length()) {
            val feat = f.getJSONObject(i)
            val p = feat.optJSONObject("properties") ?: continue
            val g = feat.optJSONObject("geometry") ?: continue
            val cls = p.optString("Class")
            when {
                cls.startsWith("Line_Line") && g.optString("type") == "LineString" -> {
                    val forecast = p.opt("forecast").toString().lowercase(Locale.US) == "true"
                    segs += Seg(parseLocal(p.optString("polygondate")), ring(g.optJSONArray("coordinates")), p.optString("polygonlabel"), forecast)
                }
                cls == "Poly_Cones" -> cone += polygons(g)
            }
        }
        segs.sortBy { it.time }
        val points = ArrayList<TrackPoint>()
        segs.forEach { s -> s.coords.firstOrNull()?.let { points += TrackPoint(it.lat, it.lon, s.label, s.time, s.forecast) } }
        segs.lastOrNull()?.let { s -> s.coords.lastOrNull()?.let { points += TrackPoint(it.lat, it.lon, s.label, 0L, s.forecast) } }
        return StormTrack(
            past = joinRuns(segs.filter { !it.forecast }.map { it.coords }),
            forecast = joinRuns(segs.filter { it.forecast }.map { it.coords }),
            cone = cone,
            points = points,
        )
    }

    /** Glues consecutive segments into one line each. */
    private fun joinRuns(lines: List<List<LatLon>>): List<List<LatLon>> {
        if (lines.isEmpty()) return emptyList()
        val joined = ArrayList<LatLon>()
        lines.forEach { l -> l.forEach { if (joined.lastOrNull() != it) joined += it } }
        return listOf(joined)
    }

    private fun ring(a: JSONArray?): List<LatLon> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { LatLon(it.optDouble(1), it.optDouble(0)) }
    }

    private fun polygons(g: JSONObject): List<List<LatLon>> {
        val c = g.optJSONArray("coordinates") ?: return emptyList()
        return when (g.optString("type")) {
            "Polygon" -> listOfNotNull(ring(c.optJSONArray(0)).takeIf { it.size > 2 })
            "MultiPolygon" -> (0 until c.length()).mapNotNull { ring(c.optJSONArray(it)?.optJSONArray(0)).takeIf { r -> r.size > 2 } }
            else -> emptyList()
        }
    }

    // ---- NHC: Atlantic, East & Central Pacific, with the official cone ----

    suspend fun nhc(): List<Storm> = parseNhc(Http.text("https://www.nhc.noaa.gov/CurrentStorms.json"))

    fun parseNhc(body: String): List<Storm> {
        val a = JSONObject(body).optJSONArray("activeStorms") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { s ->
            val lat = s.optDouble("latitudeNumeric", s.optDouble("latitude_numeric", 0.0))
            val lon = s.optDouble("longitudeNumeric", s.optDouble("longitude_numeric", 0.0))
            val dir = s.optDouble("movementDir", Double.NaN)
            val speed = s.optDouble("movementSpeed", Double.NaN)
            val movement = if (!dir.isNaN() && !speed.isNaN()) {
                if (speed < 1) "Stationary" else "${Geo.compass(dir)} at ${speed.toInt()} mph"
            } else {
                null
            }
            val id = s.optString("id")
            val links = buildList {
                s.optJSONObject("publicAdvisory")?.optString("url")?.takeIf { it.startsWith("http") }?.let { add("NHC public advisory" to it) }
                s.optJSONObject("forecastDiscussion")?.optString("url")?.takeIf { it.startsWith("http") }?.let { add("NHC discussion" to it) }
                s.optJSONObject("forecastGraphics")?.optString("url")?.takeIf { it.startsWith("http") }?.let { add("NHC graphics" to it) }
            }
            Storm(
                key = "nhc-$id",
                name = s.optString("name"),
                windKt = s.optString("intensity").toDoubleOrNull() ?: 0.0,
                pressureMb = s.optString("pressure").toIntOrNull(),
                lat = lat,
                lon = lon,
                basin = when {
                    id.startsWith("al") -> Basin.Atlantic
                    id.startsWith("cp") -> Basin.CentralPacific
                    else -> Basin.EastPacific
                },
                source = "NHC",
                movement = movement,
                updated = runCatching { OffsetDateTime.parse(s.optString("lastUpdate")).toInstant().toEpochMilli() }.getOrDefault(0L),
                alertLevel = null,
                countries = "",
                gdacsGeometry = null,
                links = links,
                nhcCone = s.optJSONObject("trackCone")?.optString("kmzFile")?.takeIf { it.startsWith("http") },
                nhcTrack = s.optJSONObject("forecastTrack")?.optString("kmzFile")?.takeIf { it.startsWith("http") },
                nhcBestTrack = s.optJSONObject("bestTrackGIS")?.optString("kmzFile")?.takeIf { it.startsWith("http") },
                nhcClass = s.optString("classification").takeIf { it.isNotBlank() },
            )
        }
    }

    /** Cone, past and forecast track for one storm: NHC's own files when it has them, GDACS otherwise. */
    suspend fun track(storm: Storm): StormTrack = coroutineScope {
        if (storm.nhcCone != null || storm.nhcTrack != null) {
            val cone = async { storm.nhcCone?.let { runCatching { Kml.parse(Kml.unzip(Http.bytes(it))) }.getOrNull() } }
            val fc = async { storm.nhcTrack?.let { runCatching { Kml.parse(Kml.unzip(Http.bytes(it))) }.getOrNull() } }
            val best = async { storm.nhcBestTrack?.let { runCatching { Kml.parse(Kml.unzip(Http.bytes(it))) }.getOrNull() } }
            val c = cone.await()
            val f = fc.await()
            val b = best.await()
            val t = StormTrack(
                past = b?.lines.orEmpty(),
                forecast = f?.lines.orEmpty().ifEmpty { f?.points?.map { LatLon(it.lat, it.lon) }?.let { listOf(it) }.orEmpty() },
                cone = c?.polygons.orEmpty(),
                points = f?.points.orEmpty().map { it.copy(forecast = true) },
            )
            if (!t.isEmpty) return@coroutineScope t
        }
        val g = storm.gdacsGeometry ?: return@coroutineScope StormTrack.EMPTY
        parseGdacsGeometry(Http.text(g))
    }

    suspend fun outlooks(): List<Outlook> = coroutineScope {
        val at = async { runCatching { outlook("Atlantic", "https://www.nhc.noaa.gov/text/MIATWOAT.shtml") }.getOrNull() }
        val ep = async { runCatching { outlook("East Pacific", "https://www.nhc.noaa.gov/text/MIATWOEP.shtml") }.getOrNull() }
        listOfNotNull(at.await(), ep.await())
    }

    private suspend fun outlook(basin: String, url: String): Outlook? {
        val text = preText(Http.text(url)) ?: return null
        return Outlook(basin, text)
    }

    /** The text inside an NWS page's <pre> block, without tags. */
    fun preText(html: String): String? {
        val start = html.indexOf("<pre", ignoreCase = true)
        if (start < 0) return null
        val open = html.indexOf('>', start)
        val end = html.indexOf("</pre>", open, ignoreCase = true)
        if (open < 0 || end < 0) return null
        return html.substring(open + 1, end)
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .trim()
    }
}

/** Minimal KML reader for NHC's cone and track files. */
object Kml {
    data class Parsed(val polygons: List<List<LatLon>>, val lines: List<List<LatLon>>, val points: List<TrackPoint>)

    fun unzip(bytes: ByteArray): String {
        // A .kmz is a zip with a .kml inside; a plain .kml passes straight through.
        if (bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name.endsWith(".kml", true)) return zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            throw IllegalStateException("No KML inside the KMZ")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun parse(kml: String): Parsed {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val doc = f.newDocumentBuilder().parse(InputSource(StringReader(kml)))
        val polys = ArrayList<List<LatLon>>()
        val lines = ArrayList<List<LatLon>>()
        val points = ArrayList<TrackPoint>()
        val marks = doc.getElementsByTagName("Placemark")
        for (i in 0 until marks.length) {
            val pm = marks.item(i) as? Element ?: continue
            val name = pm.getElementsByTagName("name").item(0)?.textContent?.trim().orEmpty()
            pm.getElementsByTagName("Polygon").let { list ->
                for (j in 0 until list.length) {
                    val poly = list.item(j) as Element
                    val outer = (poly.getElementsByTagName("outerBoundaryIs").item(0) as? Element) ?: poly
                    val c = outer.getElementsByTagName("coordinates").item(0)?.textContent
                    coords(c).takeIf { it.size > 2 }?.let { polys += it }
                }
            }
            pm.getElementsByTagName("LineString").let { list ->
                for (j in 0 until list.length) {
                    val c = (list.item(j) as Element).getElementsByTagName("coordinates").item(0)?.textContent
                    coords(c).takeIf { it.size > 1 }?.let { lines += it }
                }
            }
            pm.getElementsByTagName("Point").let { list ->
                for (j in 0 until list.length) {
                    val c = (list.item(j) as Element).getElementsByTagName("coordinates").item(0)?.textContent
                    coords(c).firstOrNull()?.let { points += TrackPoint(it.lat, it.lon, name, 0L, false) }
                }
            }
        }
        return Parsed(polys, lines, points)
    }

    fun coords(text: String?): List<LatLon> {
        if (text.isNullOrBlank()) return emptyList()
        return text.trim().split(Regex("\\s+")).mapNotNull { t ->
            val parts = t.split(',')
            if (parts.size < 2) null else {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lon == null || lat == null) null else LatLon(lat, lon)
            }
        }
    }
}
