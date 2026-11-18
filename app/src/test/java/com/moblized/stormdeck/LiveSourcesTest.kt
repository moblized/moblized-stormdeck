package com.moblized.stormdeck

import com.moblized.stormdeck.data.AppSettings
import com.moblized.stormdeck.data.EnsoSource
import com.moblized.stormdeck.data.ForecastModel
import com.moblized.stormdeck.data.Gibs
import com.moblized.stormdeck.data.Http
import com.moblized.stormdeck.data.Nws
import com.moblized.stormdeck.data.NwsWarningsLayer
import com.moblized.stormdeck.data.OpenMeteo
import com.moblized.stormdeck.data.PlaceSearch
import com.moblized.stormdeck.data.Radar
import com.moblized.stormdeck.data.RadarProduct
import com.moblized.stormdeck.data.SatelliteMode
import com.moblized.stormdeck.data.Tropics
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the app's own fetchers and parsers against the real servers (CI passes -Plive=1).
 * Prints one "LIVE OK" / "LIVE FAIL" line per source rather than failing the build, since outside servers come and go.
 */
class LiveSourcesTest {
    private val results = ArrayList<String>()

    private fun check(name: String, block: suspend () -> String) {
        val line = runCatching { runBlocking { block() } }
            .fold({ "LIVE OK   $name: $it" }, { "LIVE FAIL $name: ${it.javaClass.simpleName}: ${it.message}" })
        println(line)
        results += line
    }

    /** Fetches a tile or image and checks it really is a PNG or JPEG. */
    private fun image(url: String): String {
        val b = Http.bytesBlocking(url) ?: error("no answer / HTTP error for $url")
        val png = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
        val jpg = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        check(png || jpg) { "not an image (${b.size} bytes): " + String(b.copyOf(minOf(200, b.size))) }
        return "${if (png) "png" else "jpeg"} ${b.size} bytes"
    }

    private suspend fun textRetry(url: String): String {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return Http.text(url)
            } catch (e: java.io.IOException) {
                last = e
                println("LIVE note: attempt ${attempt + 1} for ${url.substringBefore('?')} failed (${e.message}); retrying on a fresh connection")
                Http.client.connectionPool.evictAll()
                kotlinx.coroutines.delay(3000L)
            }
        }
        throw last!!
    }

    private fun tile(template: String, z: Int, x: Int, y: Int) =
        template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")

    // Central US at zoom 5, and its Web-Mercator box for WMS layers.
    private val bboxUs = "-11271098,3757033,-10018754,5009377"
    private val bboxPacific = "-17532819,-1252344,-15028131,1252344"
    private val bboxAsia = "13775786,1252344,15028131,2504689"
    private val bboxWestUs = "-15028131,2273031,-12245143,5621521"

    @Test
    fun sources() {
        assumeTrue(System.getProperty("live") == "1")
        val s = AppSettings()

        check("Geocoding") { PlaceSearch.search("Tulsa").joinToString { it.name + "/" + it.region }.take(120) }

        check("NWS alerts") { "${Nws.alerts(40.71, -74.0).size} active" }
        check("NWS point + periods + AFD") {
            val p = Nws.point(40.71, -74.0)
            val periods = Nws.periods(p)
            val afd = Nws.discussion(p.office)
            "office ${p.office}, radar ${p.radar}, ${periods.size} periods, AFD ${afd?.length ?: 0} chars"
        }

        check("RainViewer global") {
            val fs = Radar.global()
            "${fs.frames.size} frames; tile " + image(tile(fs.frames.last().url, 3, 2, 3))
        }
        check("IEM US mosaic") {
            val fs = Radar.usMosaic()
            "${fs.frames.size} frames (${fs.frames.first().url.substringAfter("1.0.0/").substringBefore("/")}…); tile " + image(tile(fs.frames.last().url, 5, 8, 12))
        }
        check("IEM NEXRAD stations") { "${Radar.parseSites(Http.text("https://mesonet.agron.iastate.edu/geojson/network/NEXRAD.geojson")).size} sites" }
        check("IEM station DMX products + loop") {
            val site = com.moblized.stormdeck.data.NexradSite("DMX", "Des Moines", "IA", 41.73, -93.72)
            val prods = Radar.products(site)
            val vel = prods.firstOrNull { it.id == "N0S" } ?: RadarProduct("N0B", "Reflectivity")
            val fs = Radar.station(site, vel)
            "products ${prods.map { it.id }}; ${vel.id} ${fs.frames.size} frames; tile " + image(tile(fs.frames.last().url, 7, 30, 47))
        }

        for ((mode, layer) in Gibs.layers(SatelliteMode.Infrared).map { SatelliteMode.Infrared to it } + Gibs.layers(SatelliteMode.GeoColor).map { SatelliteMode.GeoColor to it }) {
            val geo = runBlocking { Gibs.newestAvailable(layer, mode) }
            val box = when {
                layer.startsWith("Himawari") -> bboxAsia
                layer.startsWith("GOES-West") -> bboxWestUs
                else -> bboxUs
            }
            check("GIBS $layer @$geo") {
                val img = image(Gibs.wms(layer, geo).replace("{bbox-epsg-3857}", box))
                check(!img.contains(" 334 bytes")) { "blank image: $img" }
                img
            }
        }
        check("GIBS SST anomaly map tiles @${Gibs.sstDate()}") { image(Gibs.wms(Gibs.SST_ANOMALY, Gibs.sstDate()).replace("{bbox-epsg-3857}", bboxPacific)) }
        check("GIBS SST anomaly picture") { image(Gibs.sstImage(-180.0, -30.0, -80.0, 30.0, 600, 360)) }
        check("NWS warnings layer") { image(NwsWarningsLayer.url().replace("{bbox-epsg-3857}", bboxUs)) }

        check("Tropics (NHC + GDACS)") {
            val storms = Tropics.active()
            val first = storms.firstOrNull()
            val track = first?.let { Tropics.track(it) }
            "${storms.size} storms: " + storms.joinToString { "${it.name} ${it.basin} ${it.windKt.toInt()}kt" } +
                (track?.let { " | ${first.name} track: past ${it.past.sumOf { l -> l.size }} pts, forecast ${it.forecast.sumOf { l -> l.size }} pts, cone ${it.cone.size}" } ?: "")
        }
        check("NHC outlooks") { Tropics.outlooks().joinToString { "${it.basin} ${it.text.length} chars" } }

        check("ENSO (CPC)") {
            val e = EnsoSource.load()
            "status=${e.status?.alert} | synopsis=${e.status?.synopsis?.take(60)} | issued ${e.status?.issued} | ONI ${e.latestOni?.label} ${e.latestOni?.anomaly} | week ${e.latestWeek?.date} Niño3.4 ${e.latestWeek?.nino34}"
        }

        check("OpenFreeMap dark style") { Http.text("https://tiles.openfreemap.org/styles/dark").let { "${it.length} chars, glyphs=" + Regex("\"glyphs\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) + ", fonts=" + Regex("Noto Sans [A-Za-z]+").findAll(it).map { m -> m.value }.toSet() } }
        check("OpenFreeMap positron style") { "${Http.text("https://tiles.openfreemap.org/styles/positron").length} chars" }
        check("CARTO car tiles") { image("https://basemaps.cartocdn.com/dark_nolabels/5/8/12@2x.png") + " / " + image("https://basemaps.cartocdn.com/dark_only_labels/5/8/12@2x.png") }

        check("Open-Meteo best match") { OpenMeteo.parse(textRetry(OpenMeteo.url(40.71, -74.0, s))).let { "now ${it.current.temp}°F, ${it.hourly.size} h, ${it.daily.size} d, ${it.minutely.size} x15min, cape ${it.current.cape}, vis ${it.current.visibility}${it.visibilityUnit}" } }
        check("Open-Meteo National Blend (NYC)") {
            val body = textRetry(OpenMeteo.url(40.71, -74.0, s, ForecastModel.Nbm))
            val o = org.json.JSONObject(body)
            val nbmKeys = o.getJSONObject("hourly").keys().asSequence().filter { it.endsWith("_ncep_nbm_conus") }.toList()
            val f = OpenMeteo.parse(body, ForecastModel.Nbm)
            val nbmHours = o.getJSONObject("hourly").optJSONArray("temperature_2m_ncep_nbm_conus")?.let { a -> (0 until a.length()).count { !a.isNull(it) } }
            check(f.modelCoversHere) { "Blend has no current temperature for NYC" }
            "covers=${f.modelCoversHere}, now ${f.current.temp}, Blend hours $nbmHours of ${f.hourly.size}, Blend vars ${nbmKeys.map { it.removeSuffix("_ncep_nbm_conus") }}, ${f.minutely.size} x15min"
        }
        check("Open-Meteo National Blend outside US (London)") {
            val f = OpenMeteo.parse(textRetry(OpenMeteo.url(51.5, -0.12, s, ForecastModel.Nbm)), ForecastModel.Nbm)
            "covers=${f.modelCoversHere}, now ${f.current.temp}, ${f.hourly.size} h, ${f.daily.size} d"
        }
        for (m in ForecastModel.entries.filter { it != ForecastModel.Best && it != ForecastModel.Nbm }) {
            check("Open-Meteo ${m.api}") { OpenMeteo.parse(textRetry(OpenMeteo.url(40.71, -74.0, s, m)), m).let { "${it.hourly.size} h, ${it.daily.size} d, now ${it.current.temp}" } }
        }
        println("LIVE SUMMARY ${results.count { it.startsWith("LIVE OK") }} ok, ${results.count { it.startsWith("LIVE FAIL") }} failed")
    }
}
