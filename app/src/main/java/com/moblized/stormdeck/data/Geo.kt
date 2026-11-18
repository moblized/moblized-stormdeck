package com.moblized.stormdeck.data

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val EARTH_KM = 6371.0

    fun distanceKm(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_KM * asin(min(1.0, sqrt(h)))
    }

    /** Shortest distance from [p] to the segment a–b, on a local flat approximation (fine for a few hundred km). */
    fun distanceToSegmentKm(p: LatLon, a: LatLon, b: LatLon): Double {
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * k
        val ay = a.lat - p.lat
        val bx = (b.lon - p.lon) * k
        val by = b.lat - p.lat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy) * 111.195
    }

    fun distanceToLineKm(p: LatLon, line: List<LatLon>): Double {
        if (line.isEmpty()) return Double.MAX_VALUE
        if (line.size == 1) return distanceKm(p, line[0])
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) best = min(best, distanceToSegmentKm(p, line[i], line[i + 1]))
        return best
    }

    /** Ray casting; [ring] is a closed or open list of vertices. */
    fun inside(p: LatLon, ring: List<LatLon>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    fun compass(deg: Double): String {
        val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val i = (((deg % 360) + 360) % 360 / 22.5 + 0.5).toInt() % 16
        return names[i]
    }

    // Web Mercator helpers, in "world pixels" at a given zoom with 256-px tiles.
    fun lonToX(lon: Double, zoom: Double): Double = (lon + 180.0) / 360.0 * 256.0 * Math.pow(2.0, zoom)

    fun latToY(lat: Double, zoom: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val r = Math.toRadians(clamped)
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * 256.0 * Math.pow(2.0, zoom)
    }

    fun xToLon(x: Double, zoom: Double): Double = x / (256.0 * Math.pow(2.0, zoom)) * 360.0 - 180.0

    fun yToLat(y: Double, zoom: Double): Double {
        val n = PI - 2.0 * PI * y / (256.0 * Math.pow(2.0, zoom))
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    /** Rough box around the lower 48, Alaska, Hawaii and Puerto Rico, where NEXRAD radar exists. */
    fun inUsRadarArea(p: LatLon): Boolean =
        (p.lat in 24.0..50.0 && p.lon in -125.5..-66.0) ||
            (p.lat in 51.0..72.0 && p.lon in -170.0..-129.0) ||
            (p.lat in 18.5..22.5 && p.lon in -161.0..-154.0) ||
            (p.lat in 17.5..18.7 && p.lon in -67.5..-65.0) ||
            (p.lat in 13.0..15.0 && p.lon in 144.0..146.0)
}
