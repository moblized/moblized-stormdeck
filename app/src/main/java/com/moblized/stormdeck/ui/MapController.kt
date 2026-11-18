package com.moblized.stormdeck.ui

import android.graphics.PointF
import com.moblized.stormdeck.data.FrameSet
import com.moblized.stormdeck.data.Gibs
import com.moblized.stormdeck.data.LatLon
import com.moblized.stormdeck.data.NexradSite
import com.moblized.stormdeck.data.NwsWarningsLayer
import com.moblized.stormdeck.data.SatelliteMode
import com.moblized.stormdeck.data.Storm
import com.moblized.stormdeck.data.StormTrack
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * Owns the weather layers on the MapLibre map. Raster layers sit in fixed groups under the base map's labels
 * (sea temps, satellite, radar, warnings, bottom to top); storms, stations and "you" draw above everything.
 */
class MapController(
    private val map: MapLibreMap,
    private val style: Style,
    private val dark: Boolean,
    private val onSite: (String) -> Unit,
    private val onStorm: (String) -> Unit,
) {
    private val labels: String? = style.layers.firstOrNull { it is SymbolLayer }?.id

    private var frameKey: String? = null
    private var frameCount = 0
    private var shown = -1
    private var opacity = 0.8f
    private var satKey: String? = null
    private var satCount = 0
    private var warnKey: String? = null
    private var sstKey: String? = null

    private val click = MapLibreMap.OnMapClickListener { latLng ->
        val pt: PointF = map.projection.toScreenLocation(latLng)
        val r = 24f
        val box = android.graphics.RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
        val storm = map.queryRenderedFeatures(box, "storm-now", "storm-pts").firstOrNull()
        if (storm != null) {
            storm.getStringProperty("key")?.let(onStorm)
            return@OnMapClickListener true
        }
        val site = map.queryRenderedFeatures(box, "nexrad-dot").firstOrNull()
        if (site != null) {
            site.getStringProperty("id")?.let(onSite)
            return@OnMapClickListener true
        }
        false
    }

    init {
        map.uiSettings.isTiltGesturesEnabled = false
        for (id in listOf(SST, SAT, RADAR, WARN)) {
            addBelowLabels(BackgroundLayer(id).withProperties(PropertyFactory.backgroundOpacity(0f)))
        }

        val ink = if (dark) "#F8FAFC" else "#0F172A"
        val halo = if (dark) "#0B1220" else "#FFFFFF"

        style.addSource(GeoJsonSource("nino"))
        style.addLayer(
            LineLayer("nino-line", "nino").withProperties(
                PropertyFactory.lineColor("#FBBF24"),
                PropertyFactory.lineWidth(1.6f),
                PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
                PropertyFactory.visibility(Property.NONE),
            ),
        )

        style.addSource(GeoJsonSource("storms"))
        style.addLayer(
            FillLayer("storm-cone", "storms").withProperties(
                PropertyFactory.fillColor(ink),
                PropertyFactory.fillOpacity(0.12f),
            ).withFilter(Expression.eq(Expression.get("kind"), "cone")),
        )
        style.addLayer(
            LineLayer("storm-cone-edge", "storms").withProperties(
                PropertyFactory.lineColor(ink),
                PropertyFactory.lineOpacity(0.55f),
                PropertyFactory.lineWidth(1.2f),
            ).withFilter(Expression.eq(Expression.get("kind"), "cone")),
        )
        style.addLayer(
            LineLayer("storm-past", "storms").withProperties(
                PropertyFactory.lineColor(ink),
                PropertyFactory.lineOpacity(0.75f),
                PropertyFactory.lineWidth(2f),
            ).withFilter(Expression.eq(Expression.get("kind"), "past")),
        )
        style.addLayer(
            LineLayer("storm-fc", "storms").withProperties(
                PropertyFactory.lineColor(ink),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
            ).withFilter(Expression.eq(Expression.get("kind"), "forecast")),
        )
        style.addLayer(
            CircleLayer("storm-pts", "storms").withProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleRadius(4.5f),
                PropertyFactory.circleStrokeColor("#000000"),
                PropertyFactory.circleStrokeWidth(1f),
            ).withFilter(Expression.eq(Expression.get("kind"), "point")),
        )
        style.addLayer(
            CircleLayer("storm-now", "storms").withProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleStrokeColor("#000000"),
                PropertyFactory.circleStrokeWidth(2f),
            ).withFilter(Expression.eq(Expression.get("kind"), "now")),
        )
        style.addLayer(
            SymbolLayer("storm-label", "storms").withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(12f),
                PropertyFactory.textColor(ink),
                PropertyFactory.textHaloColor(halo),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textAllowOverlap(true),
            ).withFilter(Expression.eq(Expression.get("kind"), "now")),
        )

        style.addSource(GeoJsonSource("nexrad"))
        style.addLayer(
            CircleLayer("nexrad-dot", "nexrad").withProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleRadius(Expression.toNumber(Expression.get("r"))),
                PropertyFactory.circleStrokeColor(halo),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.visibility(Property.NONE),
            ),
        )
        style.addLayer(
            SymbolLayer("nexrad-label", "nexrad").withProperties(
                PropertyFactory.textField(Expression.get("id")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor(ink),
                PropertyFactory.textHaloColor(halo),
                PropertyFactory.textHaloWidth(1.4f),
                PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.visibility(Property.NONE),
            ).also { it.minZoom = 5f },
        )

        style.addSource(GeoJsonSource("me"))
        style.addLayer(
            CircleLayer("me-halo", "me").withProperties(
                PropertyFactory.circleColor("#38BDF8"),
                PropertyFactory.circleOpacity(0.25f),
                PropertyFactory.circleRadius(16f),
            ),
        )
        style.addLayer(
            CircleLayer("me-dot", "me").withProperties(
                PropertyFactory.circleColor("#0284C7"),
                PropertyFactory.circleRadius(6.5f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
        map.addOnMapClickListener(click)
    }

    fun dispose() {
        runCatching { map.removeOnMapClickListener(click) }
    }

    private fun addBelowLabels(layer: Layer) {
        if (labels != null) style.addLayerBelow(layer, labels) else style.addLayer(layer)
    }

    private fun addRaster(id: String, url: String, tileSize: Int, maxZoom: Float, group: String, opacity: Float, attribution: String? = null) {
        val ts = TileSet("2.2.0", url)
        ts.setMaxZoom(maxZoom)
        ts.setMinZoom(0f)
        if (attribution != null) ts.attribution = attribution
        style.addSource(RasterSource(id, ts, tileSize))
        style.addLayerBelow(
            RasterLayer(id, id).withProperties(
                PropertyFactory.rasterOpacity(opacity),
                PropertyFactory.rasterFadeDuration(0f),
            ),
            group,
        )
    }

    private fun removeRaster(id: String) {
        runCatching { style.removeLayer(id) }
        runCatching { style.removeSource(id) }
    }

    // ---- Radar loop ----

    fun setFrames(fs: FrameSet?, opacity: Float) {
        this.opacity = opacity
        val key = fs?.key
        if (key == frameKey) {
            setOpacity(opacity)
            return
        }
        for (i in 0 until frameCount) removeRaster("radar-$i")
        frameCount = 0
        shown = -1
        frameKey = key
        if (fs == null) return
        fs.frames.forEachIndexed { i, f ->
            addRaster("radar-$i", f.url, fs.tileSize, fs.maxZoom, RADAR, 0f, if (i == 0) fs.attribution else null)
        }
        frameCount = fs.frames.size
        show(frameCount - 1)
    }

    fun show(index: Int) {
        if (frameCount == 0) return
        val i = index.coerceIn(0, frameCount - 1)
        if (i == shown) return
        style.getLayer("radar-$i")?.setProperties(PropertyFactory.rasterOpacity(opacity))
        if (shown in 0 until frameCount) style.getLayer("radar-$shown")?.setProperties(PropertyFactory.rasterOpacity(0f))
        shown = i
    }

    private fun setOpacity(o: Float) {
        if (shown >= 0) style.getLayer("radar-$shown")?.setProperties(PropertyFactory.rasterOpacity(o))
    }

    // ---- Other raster overlays ----

    fun setSatellite(mode: SatelliteMode, time: String) {
        val key = "$mode@$time"
        if (key == satKey) return
        for (i in 0 until satCount) removeRaster("sat-$i")
        satKey = key
        val layers = Gibs.layers(mode)
        layers.forEachIndexed { i, l ->
            addRaster("sat-$i", Gibs.wms(l, time), 256, 18f, SAT, if (mode == SatelliteMode.GeoColor) 0.9f else 0.7f, if (i == 0) "Imagery: NASA GIBS / NOAA" else null)
        }
        satCount = layers.size
    }

    fun setWarnings(on: Boolean, now: Long = System.currentTimeMillis()) {
        val key = if (on) NwsWarningsLayer.url(now) else null
        if (key == warnKey) return
        removeRaster("warn")
        warnKey = key
        if (key != null) addRaster("warn", key, 256, 18f, WARN, 0.7f, "Warnings: NWS")
    }

    fun setSst(on: Boolean, date: String) {
        val key = if (on) date else null
        if (key == sstKey) return
        removeRaster("sst")
        sstKey = key
        if (key != null) {
            addRaster("sst", Gibs.wms(Gibs.SST_ANOMALY, date), 256, 10f, SST, 0.85f, "Sea temps: NASA GHRSST MUR")
        }
        style.getLayer("nino-line")?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
        if (on) {
            style.getSourceAs<GeoJsonSource>("nino")?.setGeoJson(
                """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[-170,-5],[-120,-5],[-120,5],[-170,5],[-170,-5]]}}""",
            )
        }
    }

    // ---- Vector overlays ----

    fun setStorms(storms: List<Storm>, tracks: Map<String, StormTrack>, visible: Boolean) {
        val src = style.getSourceAs<GeoJsonSource>("storms") ?: return
        if (!visible) {
            src.setGeoJson(EMPTY)
            return
        }
        val features = JSONArray()
        for (st in storms) {
            val color = hex(st.category.color)
            tracks[st.key]?.let { t ->
                t.cone.forEach { ring -> features.put(feature("cone", st.key, color, polygon(ring))) }
                t.past.forEach { line -> if (line.size > 1) features.put(feature("past", st.key, color, lineString(line))) }
                t.forecast.forEach { line -> if (line.size > 1) features.put(feature("forecast", st.key, color, lineString(line))) }
                t.points.filter { it.forecast }.forEach { p ->
                    val c = labelColor(p.label) ?: color
                    features.put(feature("point", st.key, c, point(LatLon(p.lat, p.lon))))
                }
            }
            features.put(feature("now", st.key, color, point(st.point)).also { it.getJSONObject("properties").put("label", "${st.name} · ${st.category.short}") })
        }
        src.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
    }

    fun setStations(sites: List<NexradSite>, selected: String?, visible: Boolean) {
        val v = if (visible) Property.VISIBLE else Property.NONE
        style.getLayer("nexrad-dot")?.setProperties(PropertyFactory.visibility(v))
        style.getLayer("nexrad-label")?.setProperties(PropertyFactory.visibility(v))
        val src = style.getSourceAs<GeoJsonSource>("nexrad") ?: return
        if (!visible) {
            src.setGeoJson(EMPTY)
            return
        }
        val features = JSONArray()
        sites.forEach { s ->
            val sel = s.id == selected
            val f = JSONObject().put("type", "Feature")
                .put("properties", JSONObject().put("id", s.id).put("color", if (sel) "#38BDF8" else "#A5B4FC").put("r", if (sel) 8 else 5))
                .put("geometry", point(s.point))
            features.put(f)
        }
        src.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
    }

    fun setMe(p: LatLon?) {
        val src = style.getSourceAs<GeoJsonSource>("me") ?: return
        src.setGeoJson(if (p == null) EMPTY else JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", point(p)).toString())
    }

    fun fly(p: LatLon, zoom: Double, animate: Boolean = true) {
        val u = CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), zoom)
        if (animate) map.animateCamera(u, 900) else map.moveCamera(u)
    }

    fun center(): LatLon? = map.cameraPosition.target?.let { LatLon(it.latitude, it.longitude) }

    private fun feature(kind: String, key: String, color: String, geometry: JSONObject) = JSONObject()
        .put("type", "Feature")
        .put("properties", JSONObject().put("kind", kind).put("key", key).put("color", color))
        .put("geometry", geometry)

    private fun point(p: LatLon) = JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat))

    private fun lineString(l: List<LatLon>) = JSONObject().put("type", "LineString").put("coordinates", coords(l))

    private fun polygon(ring: List<LatLon>): JSONObject {
        val closed = if (ring.first() != ring.last()) ring + ring.first() else ring
        return JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(coords(closed)))
    }

    private fun coords(l: List<LatLon>): JSONArray {
        val a = JSONArray()
        // Keep lines continuous across the date line by unwrapping longitudes.
        var prev: Double? = null
        l.forEach { p ->
            var lon = p.lon
            prev?.let { pv ->
                while (lon - pv > 180) lon -= 360
                while (pv - lon > 180) lon += 360
            }
            prev = lon
            a.put(JSONArray().put(lon).put(p.lat))
        }
        return a
    }

    private fun hex(argb: Long): String = String.format("#%06X", argb and 0xFFFFFFL)

    /** Category colors for GDACS point labels like "TS" or "Cat. 3". */
    private fun labelColor(label: String): String? {
        val l = label.lowercase()
        val cat = when {
            l.contains("5") -> com.moblized.stormdeck.data.StormCategory.C5
            l.contains("4") -> com.moblized.stormdeck.data.StormCategory.C4
            l.contains("3") -> com.moblized.stormdeck.data.StormCategory.C3
            l.contains("2") -> com.moblized.stormdeck.data.StormCategory.C2
            l.contains("1") -> com.moblized.stormdeck.data.StormCategory.C1
            l.startsWith("ts") || l.contains("storm") -> com.moblized.stormdeck.data.StormCategory.TS
            l.startsWith("td") || l.contains("depression") -> com.moblized.stormdeck.data.StormCategory.TD
            else -> return null
        }
        return hex(cat.color)
    }

    companion object {
        private const val SST = "group-sst"
        private const val SAT = "group-sat"
        private const val RADAR = "group-radar"
        private const val WARN = "group-warn"
        private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

        fun styleUrl(dark: Boolean) = if (dark) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/positron"
    }
}
