package com.moblized.stormdeck

import com.moblized.stormdeck.data.AlertKind
import com.moblized.stormdeck.data.AlertLevel
import com.moblized.stormdeck.data.Basin
import com.moblized.stormdeck.data.Enso
import com.moblized.stormdeck.data.EnsoPhase
import com.moblized.stormdeck.data.EnsoSource
import com.moblized.stormdeck.data.Geo
import com.moblized.stormdeck.data.Gibs
import com.moblized.stormdeck.data.Kml
import com.moblized.stormdeck.data.LatLon
import com.moblized.stormdeck.data.Nws
import com.moblized.stormdeck.data.OpenMeteo
import com.moblized.stormdeck.data.Radar
import com.moblized.stormdeck.data.RadarProduct
import com.moblized.stormdeck.data.NexradSite
import com.moblized.stormdeck.data.StormCategory
import com.moblized.stormdeck.data.Tropics
import com.moblized.stormdeck.data.AppSettings
import com.moblized.stormdeck.data.ForecastModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    @Test
    fun openMeteo() {
        val now = System.currentTimeMillis() / 1000
        val f = OpenMeteo.parse(Samples.openMeteo(now))
        assertEquals(-18000, f.offsetSec)
        assertEquals(54.3, f.current.temp, 0.01)
        assertEquals(78, f.hourly.size)
        assertEquals(10, f.daily.size)
        assertEquals("ft", f.visibilityUnit)
        assertTrue(f.upcoming().first().time <= now)
        assertTrue(f.upcoming().size in 70..74)
        assertEquals("Precipitation starting in about 30 min", f.nowcast())
    }

    @Test
    fun openMeteoUrl() {
        val u = OpenMeteo.url(40.7128, -74.006, AppSettings())
        assertTrue(u.contains("latitude=40.7128&longitude=-74.0060"))
        assertTrue(u.contains("temperature_unit=fahrenheit"))
        assertTrue(u.contains("minutely_15=precipitation"))
        // The National Blend is the default, with best match alongside to fill gaps.
        assertEquals(ForecastModel.Nbm, AppSettings().model)
        assertTrue(u, u.contains("&models=ncep_nbm_conus,best_match"))
        val m = OpenMeteo.url(40.0, -74.0, AppSettings(model = ForecastModel.Hrrr))
        assertTrue(m.contains("&models=gfs_hrrr,best_match"))
        val b = OpenMeteo.url(40.0, -74.0, AppSettings(model = ForecastModel.Best))
        assertFalse(b.contains("&models="))
    }

    /** Turns the single-model sample into a two-model answer: the Blend (+1°, ending after 24 h) and best match. */
    private fun twoModels(nbmCovers: Boolean): String {
        val o = org.json.JSONObject(Samples.openMeteo())
        for (block in listOf("current", "hourly", "daily", "minutely_15")) {
            val b = o.getJSONObject(block)
            for (k in b.keys().asSequence().toList()) {
                if (k == "time" || k == "interval") continue
                val v = b.get(k)
                b.remove(k)
                b.put(k + "_best_match", v)
                val nbm: Any = when {
                    !nbmCovers -> if (v is org.json.JSONArray) org.json.JSONArray().apply { repeat(v.length()) { put(org.json.JSONObject.NULL) } } else org.json.JSONObject.NULL
                    k == "temperature_2m" && v is Number -> v.toDouble() + 1
                    k == "temperature_2m" && v is org.json.JSONArray -> org.json.JSONArray().apply {
                        for (i in 0 until v.length()) put(if (i < 24) v.getDouble(i) + 1 else org.json.JSONObject.NULL)
                    }
                    k == "uv_index" -> org.json.JSONObject.NULL
                    else -> v
                }
                b.put(k + "_ncep_nbm_conus", nbm)
            }
        }
        return o.toString()
    }

    @Test
    fun openMeteoTwoModels() {
        val single = OpenMeteo.parse(Samples.openMeteo())
        val f = OpenMeteo.parse(twoModels(nbmCovers = true), ForecastModel.Nbm)
        assertTrue(f.modelCoversHere)
        assertEquals(single.current.temp + 1, f.current.temp, 0.001)
        // The Blend's value where it has one, best match's after it runs out.
        assertEquals(single.hourly[0].temp + 1, f.hourly[0].temp, 0.001)
        assertEquals(single.hourly[30].temp, f.hourly[30].temp, 0.001)
        assertEquals(single.hourly.size, f.hourly.size)
        // A variable the Blend lacks comes from best match.
        assertEquals(single.current.uv, f.current.uv, 0.001)
        assertEquals(single.daily.size, f.daily.size)
        assertEquals(single.daily[0].sunrise, f.daily[0].sunrise)
        assertEquals("Precipitation starting in about 30 min", f.nowcast())
        assertEquals("ft", f.visibilityUnit)
    }

    @Test
    fun openMeteoBlendOutsideUs() {
        val single = OpenMeteo.parse(Samples.openMeteo())
        val f = OpenMeteo.parse(twoModels(nbmCovers = false), ForecastModel.Nbm)
        assertFalse(f.modelCoversHere)
        assertEquals(single.current.temp, f.current.temp, 0.001)
        assertEquals(single.hourly.size, f.hourly.size)
        assertEquals(single.daily[3].high, f.daily[3].high, 0.001)
    }

    @Test
    fun openMeteoError() {
        val r = runCatching { OpenMeteo.parse("""{"error":true,"reason":"Cannot initialize WeatherVariable"}""") }
        assertTrue(r.isFailure)
        assertEquals("Cannot initialize WeatherVariable", r.exceptionOrNull()?.message)
    }

    @Test
    fun gdacsList() {
        val storms = Tropics.parseGdacsList(Samples.GDACS_LIST)
        assertEquals(2, storms.size)
        val a = storms.first { it.name == "Akvan" }
        assertEquals(45.0, a.windKt, 0.5)
        assertEquals(StormCategory.TS, a.category)
        assertEquals(Basin.NorthIndian, a.basin)
        assertEquals("India", a.countries)
        assertEquals("Orange", a.alertLevel)
        assertNotNull(a.gdacsGeometry)
        val m = storms.first { it.name == "Melissa" }
        assertEquals(Basin.Atlantic, m.basin)
        assertEquals(StormCategory.C4, m.category)
        assertEquals("Major Hurricane", m.type)
    }

    @Test
    fun gdacsGeometry() {
        val t = Tropics.parseGdacsGeometry(Samples.GDACS_GEOMETRY)
        assertEquals(1, t.past.size)
        assertEquals(3, t.past[0].size)
        assertEquals(1, t.forecast.size)
        assertEquals(3, t.forecast[0].size)
        assertEquals(1, t.cone.size)
        assertTrue(t.points.count { it.forecast } >= 2)
        // A spot inside the cone is "0 km" away.
        assertEquals(0.0, t.closestKm(LatLon(15.0, 86.0)), 0.0)
        assertTrue(t.closestKm(LatLon(40.7, -74.0)) > 5000)
    }

    @Test
    fun nhcAndMerge() {
        val nhc = Tropics.parseNhc(Samples.NHC)
        assertEquals(1, nhc.size)
        val m = nhc[0]
        assertEquals("Melissa", m.name)
        assertEquals(120.0, m.windKt, 0.0)
        assertEquals(948, m.pressureMb)
        assertEquals("WNW 12 mph", m.movement)
        assertEquals(Basin.Atlantic, m.basin)
        assertNotNull(m.nhcCone)
        val merged = Tropics.merge(Tropics.parseGdacsList(Samples.GDACS_LIST), nhc)
        assertEquals(2, merged.size)
        val mm = merged.first { it.name == "Melissa" }
        assertEquals("nhc-al132026", mm.key)
        assertEquals("Red", mm.alertLevel)
        assertEquals(948, mm.pressureMb)
    }

    @Test
    fun kml() {
        val k = Kml.parse(Samples.CONE_KML)
        assertEquals(1, k.polygons.size)
        assertEquals(6, k.polygons[0].size)
        assertEquals(1, k.lines.size)
        assertEquals(1, k.points.size)
        assertEquals(24.7, k.points[0].lat, 0.001)
        assertEquals("Hurricane Melissa 5 AM EST", k.points[0].label)
        assertEquals(Samples.CONE_KML, Kml.unzip(Samples.CONE_KML.toByteArray()))
    }

    @Test
    fun outlookText() {
        val t = Tropics.preText(Samples.TWO_HTML)
        assertNotNull(t)
        assertTrue(t!!.contains("Tropical Weather Outlook"))
        assertFalse(t.contains("<pre"))
    }

    @Test
    fun oni() {
        val o = EnsoSource.parseOni(Samples.ONI)
        assertEquals(6, o.size)
        assertEquals("JAS", o.last().season)
        assertEquals(2026, o.last().year)
        assertEquals(2.16, o.last().anomaly, 0.001)
        assertEquals(-1.53, o.first().anomaly, 0.001)
    }

    @Test
    fun weekly() {
        val w = EnsoSource.parseWeekly(Samples.WEEKLY)
        assertEquals(3, w.size)
        assertEquals(-0.4, w[0].nino12, 0.001)
        assertEquals(-0.3, w[0].nino3, 0.001)
        assertEquals(0.0, w[0].nino34, 0.001)
        assertEquals(0.3, w[0].nino4, 0.001)
        assertEquals(2.5, w.last().nino34, 0.001)
        assertEquals(29.4, w.last().sst34, 0.001)
        assertEquals(java.time.LocalDate.of(2026, 11, 5), w.last().date)
    }

    @Test
    fun ensoStatus() {
        val s = EnsoSource.parseStatus(Samples.ENSO_HTML)
        assertNotNull(s)
        assertEquals("El Niño Advisory", s!!.alert)
        assertTrue(s.synopsis, s.synopsis.startsWith("El Niño continues to strengthen"))
        assertTrue(s.synopsis.endsWith("chance)."))
        assertEquals("12 November 2026", s.issued)
        assertEquals(EnsoPhase.ElNino, Enso.phase(2.5))
        assertEquals("Very strong", Enso.strength(2.5))
        assertEquals(EnsoPhase.Neutral, Enso.phase(0.2))
        assertEquals(EnsoPhase.LaNina, Enso.phase(-1.1))
    }

    @Test
    fun nwsAlerts() {
        val a = Nws.parseAlerts(Samples.NWS_ALERTS)
        assertEquals(2, a.size)
        assertEquals("Severe Thunderstorm Warning", a[0].event)
        assertEquals(AlertKind.Warning, a[0].kind)
        assertEquals(AlertKind.Advisory, a[1].kind)
        assertTrue(a[0].expires > a[0].onset)
        assertTrue(a[0].matches(AlertLevel.Warnings))
        assertFalse(a[1].matches(AlertLevel.Warnings))
        assertTrue(a[1].matches(AlertLevel.All))
        assertEquals("", a[1].instruction)
    }

    @Test
    fun radar() {
        val g = Radar.parseRainViewer(Samples.RAINVIEWER)
        assertEquals(7, g.frames.size)
        assertEquals(1795417600_000L, g.frames.last().timeMillis)
        assertEquals("https://tilecache.rainviewer.com/v2/radar/1795417600/512/{z}/{x}/{y}/2/1_1.png", g.frames.last().url)
        assertEquals(512, g.tileSize)

        val scans = Radar.parseScans(Samples.IEM_SCANS)
        assertEquals(3, scans.size)
        assertEquals(
            "https://mesonet.agron.iastate.edu/c/tile.py/1.0.0/ridge::DMX-N0B-202611180949/{z}/{x}/{y}.png",
            Radar.ridgeUrl("DMX", "N0B", scans.last()),
        )
        val prods = Radar.parseProducts(Samples.IEM_PRODUCTS)
        assertEquals(listOf("N0B", "N0S"), prods.map { it.id })

        val sites = Radar.parseSites(Samples.NEXRAD_SITES)
        assertEquals(2, sites.size)
        assertEquals("DIX", Radar.nearest(sites, LatLon(40.2, -74.6))?.id)
        assertEquals("OKX", Radar.iemId("KOKX"))
        assertEquals("AHG", Radar.iemId("PAHG"))
    }

    @Test
    fun gibs() {
        val t = Gibs.latestGeoTime(java.time.Instant.parse("2026-11-18T10:47:12Z").toEpochMilli())
        assertEquals("2026-11-18T09:50:00Z", t)
        assertTrue(Gibs.wms("GOES-East_ABI_Band13_Clean_Infrared", t).contains("BBOX={bbox-epsg-3857}"))
    }

    @Test
    fun geo() {
        val nyc = LatLon(40.7128, -74.006)
        val la = LatLon(34.0522, -118.2437)
        assertEquals(3936.0, Geo.distanceKm(nyc, la), 20.0)
        assertEquals("SW", Geo.compass(225.0))
        assertEquals("N", Geo.compass(359.0))
        assertTrue(Geo.inUsRadarArea(nyc))
        assertFalse(Geo.inUsRadarArea(LatLon(51.5, -0.12)))
        val sq = listOf(LatLon(0.0, 0.0), LatLon(0.0, 10.0), LatLon(10.0, 10.0), LatLon(10.0, 0.0))
        assertTrue(Geo.inside(LatLon(5.0, 5.0), sq))
        assertFalse(Geo.inside(LatLon(15.0, 5.0), sq))
        // Mercator round trip.
        val x = Geo.lonToX(-74.0, 5.0)
        val y = Geo.latToY(40.7, 5.0)
        assertEquals(-74.0, Geo.xToLon(x, 5.0), 1e-6)
        assertEquals(40.7, Geo.yToLat(y, 5.0), 1e-6)
    }

    @Test
    fun basins() {
        assertEquals(Basin.Atlantic, Basin.of(25.0, -75.0))
        assertEquals(Basin.Atlantic, Basin.of(26.0, -90.0))
        assertEquals(Basin.EastPacific, Basin.of(15.0, -105.0))
        assertEquals(Basin.EastPacific, Basin.of(12.0, -90.0))
        assertEquals(Basin.CentralPacific, Basin.of(18.0, -155.0))
        assertEquals(Basin.WestPacific, Basin.of(15.0, 135.0))
        assertEquals(Basin.NorthIndian, Basin.of(13.0, 87.0))
        assertEquals(Basin.SouthIndian, Basin.of(-15.0, 60.0))
        assertEquals(Basin.SouthPacific, Basin.of(-18.0, 170.0))
    }

    @Test
    fun unused() {
        // Keeps imports honest for types used only by the app.
        assertNull(null as NexradSite?)
        assertNull(null as RadarProduct?)
    }
}
