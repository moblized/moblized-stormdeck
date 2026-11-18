package com.moblized.stormdeck

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Realistic sample answers from each data source, shaped like the live ones. */
object Samples {
    /** An Open-Meteo answer for "now" in New York (UTC−5), with 72 hours and 10 days. */
    fun openMeteo(nowSec: Long = System.currentTimeMillis() / 1000): String {
        val offset = -5 * 3600
        val zone = ZoneOffset.ofTotalSeconds(offset)
        val hourStart = nowSec / 3600 * 3600
        fun t(sec: Long) = LocalDateTime.ofEpochSecond(sec, 0, zone).toString().take(16)
        val times = JSONArray()
        val temp = JSONArray()
        val feels = JSONArray()
        val pop = JSONArray()
        val prcp = JSONArray()
        val code = JSONArray()
        val wind = JSONArray()
        val dir = JSONArray()
        val gust = JSONArray()
        val dew = JSONArray()
        val rh = JSONArray()
        val cape = JSONArray()
        val vis = JSONArray()
        val cloud = JSONArray()
        val pres = JSONArray()
        val uv = JSONArray()
        val day = JSONArray()
        val frz = JSONArray()
        val snow = JSONArray()
        val start = hourStart - 6 * 3600
        for (i in 0 until 78) {
            val sec = start + i * 3600L
            val local = LocalDateTime.ofEpochSecond(sec, 0, zone)
            val h = local.hour
            times.put(t(sec))
            val tt = 52 + 9 * sin((h - 9) / 24.0 * 2 * PI) + i * 0.05
            temp.put(Math.round(tt * 10) / 10.0)
            feels.put(Math.round((tt - 3) * 10) / 10.0)
            val wet = i in 14..24 || i in 50..56
            pop.put(if (wet) 70 + (i % 3) * 10 else (i % 5) * 4)
            prcp.put(if (wet) 0.06 else 0.0)
            code.put(if (wet) (if (i in 18..20) 95 else 63) else if (h in 7..17) 2 else 1)
            wind.put(8 + (i % 7))
            dir.put(200 + i)
            gust.put(14 + (i % 9))
            dew.put(41.0)
            rh.put(62)
            cape.put(if (wet) 900 else 120)
            vis.put(52800.0)
            cloud.put(if (wet) 95 else 35)
            pres.put(1016.0 - i * 0.08)
            uv.put(if (h in 9..15) 3.5 else 0.0)
            day.put(if (h in 7..17) 1 else 0)
            frz.put(9800.0)
            snow.put(0.0)
        }
        val hourly = JSONObject()
            .put("time", times).put("temperature_2m", temp).put("apparent_temperature", feels)
            .put("precipitation_probability", pop).put("precipitation", prcp).put("snowfall", snow).put("weather_code", code)
            .put("wind_speed_10m", wind).put("wind_direction_10m", dir).put("wind_gusts_10m", gust)
            .put("dew_point_2m", dew).put("relative_humidity_2m", rh).put("cape", cape).put("visibility", vis)
            .put("cloud_cover", cloud).put("pressure_msl", pres).put("uv_index", uv).put("is_day", day)
            .put("freezing_level_height", frz)

        val firstDay = LocalDateTime.ofEpochSecond(hourStart, 0, zone).toLocalDate()
        val dt = JSONArray()
        val dcode = JSONArray()
        val hi = JSONArray()
        val lo = JSONArray()
        val dsum = JSONArray()
        val dsnow = JSONArray()
        val dpop = JSONArray()
        val dwind = JSONArray()
        val dgust = JSONArray()
        val ddir = JSONArray()
        val rise = JSONArray()
        val set = JSONArray()
        val duv = JSONArray()
        val codes = listOf(63, 95, 2, 0, 1, 3, 61, 71, 2, 0)
        for (i in 0 until 10) {
            val d = firstDay.plusDays(i.toLong())
            dt.put(d.toString())
            dcode.put(codes[i])
            hi.put(58.0 + 6 * cos(i / 2.0))
            lo.put(41.0 + 4 * sin(i / 3.0))
            dsum.put(if (codes[i] >= 61) 0.42 else 0.0)
            dsnow.put(if (codes[i] == 71) 1.2 else 0.0)
            dpop.put(if (codes[i] >= 61) 80 else 10)
            dwind.put(14)
            dgust.put(26)
            ddir.put(220)
            rise.put(d.toString() + "T06:52")
            set.put(d.toString() + "T16:34")
            duv.put(3.1)
        }
        val daily = JSONObject()
            .put("time", dt).put("weather_code", dcode).put("temperature_2m_max", hi).put("temperature_2m_min", lo)
            .put("precipitation_sum", dsum).put("snowfall_sum", dsnow).put("precipitation_probability_max", dpop)
            .put("wind_speed_10m_max", dwind).put("wind_gusts_10m_max", dgust).put("wind_direction_10m_dominant", ddir)
            .put("sunrise", rise).put("sunset", set).put("uv_index_max", duv)

        val m15t = JSONArray()
        val m15p = JSONArray()
        val q = nowSec / 900 * 900
        for (i in -1 until 12) {
            m15t.put(t(q + i * 900L))
            m15p.put(if (i >= 2) 0.02 else 0.0)
        }

        val current = JSONObject()
            .put("time", t(nowSec / 900 * 900)).put("interval", 900).put("temperature_2m", 54.3)
            .put("relative_humidity_2m", 61).put("apparent_temperature", 51.2).put("is_day", 1).put("precipitation", 0.0)
            .put("weather_code", 2).put("cloud_cover", 40).put("pressure_msl", 1015.8).put("wind_speed_10m", 11.2)
            .put("wind_direction_10m", 215).put("wind_gusts_10m", 21.0).put("dew_point_2m", 41.0)
            .put("visibility", 52800.0).put("uv_index", 2.4).put("cape", 340.0)

        return JSONObject()
            .put("latitude", 40.71).put("longitude", -74.0).put("generationtime_ms", 1.2)
            .put("utc_offset_seconds", offset).put("timezone", "America/New_York").put("timezone_abbreviation", "EST")
            .put("current", current)
            .put("hourly_units", JSONObject().put("visibility", "ft").put("freezing_level_height", "ft"))
            .put("hourly", hourly).put("daily", daily)
            .put("minutely_15", JSONObject().put("time", m15t).put("precipitation", m15p))
            .toString()
    }

    const val GDACS_LIST = """{"type":"FeatureCollection","features":[
      {"type":"Feature","bbox":[86.9,13.1,86.9,13.1],"geometry":{"type":"Point","coordinates":[86.9,13.1]},
       "properties":{"eventtype":"TC","eventid":1001351,"episodeid":7,"eventname":"AKVAN-26","name":"Tropical Cyclone AKVAN-26",
       "url":{"geometry":"https://www.gdacs.org/gdacsapi/api/polygons/getgeometry?eventtype=TC&eventid=1001351&episodeid=7",
              "report":"https://www.gdacs.org/report.aspx?eventid=1001351&episodeid=7&eventtype=TC",
              "details":"https://www.gdacs.org/gdacsapi/api/events/geteventdata?eventtype=TC&eventid=1001351"},
       "alertlevel":"Orange","alertscore":2,"iscurrent":"true","country":"India","fromdate":"2026-11-16T18:00:00",
       "todate":"2026-11-18T06:00:00","datemodified":"2026-11-18T10:35:39","source":"JTWC",
       "affectedcountries":[{"iso2":"IN","iso3":"IND","countryname":"India"}],
       "severitydata":{"severity":83.3328,"severitytext":"Tropical Storm (maximum wind speed of 83 km/h)","severityunit":"km/h"}}},
      {"type":"Feature","geometry":{"type":"Point","coordinates":[131.2,9.4]},
       "properties":{"eventtype":"TC","eventid":1001340,"episodeid":12,"eventname":"MURASU-26","iscurrent":"false",
       "severitydata":{"severity":120.0,"severityunit":"km/h"}}},
      {"type":"Feature","geometry":{"type":"Point","coordinates":[-72.4,24.6]},
       "properties":{"eventtype":"TC","eventid":1001360,"episodeid":3,"eventname":"MELISSA-26","iscurrent":"true","alertlevel":"Red",
       "source":"NOAA","datemodified":"2026-11-18T09:00:00",
       "severitydata":{"severity":231.5,"severitytext":"Category 4","severityunit":"km/h"}}}
    ]}"""

    const val GDACS_GEOMETRY = """{"type":"FeatureCollection","features":[
      {"type":"Feature","geometry":{"type":"Point","coordinates":[86.9,13.1]},"properties":{"Class":"Point_Centroid","polygonlabel":"Centroid"}},
      {"type":"Feature","geometry":{"type":"LineString","coordinates":[[88.0,11.0],[87.5,12.0]]},
       "properties":{"Class":"Line_Line_0","polygonlabel":"TD","polygondate":"2026-11-17T06:00:00","forecast":false}},
      {"type":"Feature","geometry":{"type":"LineString","coordinates":[[87.5,12.0],[86.9,13.1]]},
       "properties":{"Class":"Line_Line_1","polygonlabel":"TS","polygondate":"2026-11-17T18:00:00","forecast":false}},
      {"type":"Feature","geometry":{"type":"LineString","coordinates":[[86.9,13.1],[86.2,14.5]]},
       "properties":{"Class":"Line_Line_2","polygonlabel":"TS","polygondate":"2026-11-18T06:00:00","forecast":true}},
      {"type":"Feature","geometry":{"type":"LineString","coordinates":[[86.2,14.5],[85.4,16.0]]},
       "properties":{"Class":"Line_Line_3","polygonlabel":"Cat. 1","polygondate":"2026-11-18T18:00:00","forecast":"true"}},
      {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[86.6,12.8],[87.4,13.2],[86.8,16.6],[84.0,16.4],[86.6,12.8]]]},
       "properties":{"Class":"Poly_Cones","polygonlabel":"Cone"}},
      {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[86.9,13.1],[86.9,13.1],[86.9,13.1]]]},
       "properties":{"Class":"Poly_Green","polygonlabel":"18/11 06:00"}}
    ]}"""

    const val NHC = """{"activeStorms":[{"id":"al132026","binNumber":"AT3","name":"Melissa","classification":"HU","intensity":"120",
      "pressure":"948","latitude":"24.7N","longitude":"72.3W","latitudeNumeric":24.7,"longitudeNumeric":-72.3,"movementDir":300,
      "movementSpeed":12,"lastUpdate":"2026-11-18T09:00:00.000Z",
      "publicAdvisory":{"advNum":"021","issuance":"2026-11-18T09:00:00.000Z","url":"https://www.nhc.noaa.gov/text/refresh/MIATCPAT3+shtml/180852.shtml"},
      "forecastTrack":{"advNum":"021","zipFile":"https://www.nhc.noaa.gov/gis/forecast/archive/al132026_5day_021.zip","kmzFile":"https://www.nhc.noaa.gov/storm_graphics/api/AL132026_021adv_TRACK.kmz"},
      "trackCone":{"advNum":"021","zipFile":"https://www.nhc.noaa.gov/gis/forecast/archive/al132026_5day_021.zip","kmzFile":"https://www.nhc.noaa.gov/storm_graphics/api/AL132026_021adv_CONE.kmz"},
      "bestTrackGIS":{"zipFile":"https://www.nhc.noaa.gov/gis/best_track/al132026_best_track.zip","kmzFile":"https://www.nhc.noaa.gov/gis/best_track/al132026_best_track.kmz"},
      "windWatchesWarnings":null}]}"""

    const val CONE_KML = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>AL132026 cone</name>
<Placemark><name>Melissa cone</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
-72.3,24.7,0 -74.0,25.5,0 -78.5,29.0,0 -76.0,31.0,0 -71.0,26.0,0 -72.3,24.7,0
</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
<Placemark><name>Track</name><LineString><coordinates>-72.3,24.7,0 -74.2,26.1,0 -76.4,28.3,0</coordinates></LineString></Placemark>
<Placemark><name>Hurricane Melissa 5 AM EST</name><Point><coordinates>-72.3,24.7,0</coordinates></Point></Placemark>
</Document></kml>"""

    const val ONI = """ SEAS  YR   TOTAL   ANOM
  DJF 1950  24.72  -1.53
  JFM 1950  25.17  -1.34
  NDJ 1997  28.98   2.40
  DJF 1998  28.81   2.24
  JJA 2026  29.09   1.80
  JAS 2026  29.12   2.16
"""

    const val WEEKLY = """
 Weekly SST data starts week centered on 3Jan1990

                Nino1+2      Nino3        Nino34        Nino4
 Week          SST SSTA     SST SSTA     SST SSTA     SST SSTA
 03JAN1990     23.4-0.4     25.1-0.3     26.6 0.0     28.6 0.3
 29OCT2026     23.9 2.5     27.7 2.4     29.3 2.4     29.9 1.0
 05NOV2026     24.1 2.6     27.8 2.4     29.4 2.5     29.9 1.0
"""

    const val ENSO_HTML = """<html><head><title>ENSO</title><script>var x = "Synopsis: fake";</script></head><body>
<table><tr><td><b>ENSO Alert System Status: </b><a href="ensoalert_readme.shtml">El Ni&ntilde;o Advisory</a></td></tr>
<tr><td>issued by<br>CLIMATE PREDICTION CENTER/NCEP/NWS<br>12 November 2026</td></tr>
<tr><td><b>Synopsis:</b> <b>El Ni&ntilde;o continues to strengthen, with a strong-to-very strong El Ni&ntilde;o likely through January-March 2027 (greater than an 83% chance).</b><br><br>
During October, the El Ni&ntilde;o strengthened.</td></tr></table></body></html>"""

    const val NWS_ALERTS = """{"type":"FeatureCollection","features":[
      {"id":"https://api.weather.gov/alerts/urn:oid:2.49.0.1.840.0.abc","type":"Feature","geometry":null,"properties":{
        "id":"urn:oid:2.49.0.1.840.0.abc","areaDesc":"New York (Manhattan)","effective":"2026-11-18T05:10:00-05:00",
        "onset":"2026-11-18T05:10:00-05:00","expires":"2026-11-18T06:00:00-05:00","ends":"2026-11-18T06:00:00-05:00",
        "status":"Actual","messageType":"Alert","severity":"Severe","certainty":"Observed","urgency":"Immediate",
        "event":"Severe Thunderstorm Warning","senderName":"NWS Upton NY",
        "headline":"Severe Thunderstorm Warning issued November 18 at 5:10AM EST until November 18 at 6:00AM EST by NWS Upton NY",
        "description":"At 510 AM EST, a severe thunderstorm was located over Newark, moving east at 40 mph.\n\nHAZARD...60 mph wind gusts.",
        "instruction":"For your protection move to an interior room on the lowest floor of a building."}},
      {"id":"https://api.weather.gov/alerts/urn:oid:2.49.0.1.840.0.def","type":"Feature","geometry":null,"properties":{
        "id":"urn:oid:2.49.0.1.840.0.def","areaDesc":"New York (Manhattan)","effective":"2026-11-18T03:00:00-05:00",
        "expires":"2026-11-18T19:00:00-05:00","ends":null,"status":"Actual","severity":"Moderate","urgency":"Expected",
        "event":"Wind Advisory","senderName":"NWS Upton NY","headline":"Wind Advisory until 7 PM","description":"Southwest winds 20 to 30 mph.","instruction":null}},
      {"id":"x","type":"Feature","properties":{"id":"test1","status":"Test","event":"Test Message","severity":"Unknown"}}
    ]}"""

    const val RAINVIEWER = """{"version":"2.0","generated":1795420000,"host":"https://tilecache.rainviewer.com",
      "radar":{"past":[{"time":1795412800,"path":"/v2/radar/1795412800"},{"time":1795413400,"path":"/v2/radar/1795413400"},
      {"time":1795414000,"path":"/v2/radar/1795414000"},{"time":1795414600,"path":"/v2/radar/1795414600"},
      {"time":1795415200,"path":"/v2/radar/1795415200"},{"time":1795415800,"path":"/v2/radar/1795415800"},
      {"time":1795416400,"path":"/v2/radar/1795416400"},{"time":1795417000,"path":"/v2/radar/1795417000"},
      {"time":1795417600,"path":"/v2/radar/1795417600"}]}}"""

    const val IEM_SCANS = """{"scans": [{"ts": "2026-11-18T09:36Z"}, {"ts": "2026-11-18T09:43Z"}, {"ts": "2026-11-18T09:49Z"}], "generated_at": "2026-11-18T10:47:29Z"}"""
    const val IEM_PRODUCTS = """{"products": [{"id": "N0S", "name": "Storm Relative Radial Velocity"}, {"id": "N0B", "name": "Base Reflectivity (Super Res)"}]}"""
    const val NEXRAD_SITES = """{"type":"FeatureCollection","features":[
      {"type":"Feature","id":"OKX","properties":{"sname":"Upton","state":"NY","sid":"OKX","online":true},"geometry":{"type":"Point","coordinates":[-72.864,40.865]}},
      {"type":"Feature","id":"DIX","properties":{"sname":"Fort Dix","state":"NJ","sid":"DIX","online":true},"geometry":{"type":"Point","coordinates":[-74.411,39.947]}},
      {"type":"Feature","id":"OLD","properties":{"sname":"Retired","state":"XX","sid":"OLD","online":false},"geometry":{"type":"Point","coordinates":[-74.0,40.7]}}
    ],"count":3}"""

    const val TWO_HTML = """<html><body><pre>
000
ABNT20 KNHC 181130
TWOAT

Tropical Weather Outlook
NWS National Hurricane Center Miami FL
700 AM EST Wed Nov 18 2026

For the North Atlantic...Caribbean Sea and the Gulf of America:

Active Systems:
The National Hurricane Center is issuing advisories on Hurricane Melissa.
</pre></body></html>"""

    fun nowIso(): String = Instant.now().toString()
}
