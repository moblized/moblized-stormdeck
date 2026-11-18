package com.moblized.stormdeck.data

import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.Locale

enum class AlertKind { Warning, Watch, Advisory, Statement }

data class WeatherAlert(
    val id: String,
    val event: String,
    val headline: String,
    val description: String,
    val instruction: String,
    val severity: String,
    val urgency: String,
    val areas: String,
    val sender: String,
    val onset: Long,
    val expires: Long,
) {
    val kind: AlertKind
        get() = when {
            event.endsWith("Warning", true) || event.contains("Emergency", true) -> AlertKind.Warning
            event.endsWith("Watch", true) -> AlertKind.Watch
            event.endsWith("Advisory", true) -> AlertKind.Advisory
            else -> AlertKind.Statement
        }

    fun matches(level: AlertLevel): Boolean = when (level) {
        AlertLevel.Off -> false
        AlertLevel.Warnings -> kind == AlertKind.Warning
        AlertLevel.WarningsWatches -> kind == AlertKind.Warning || kind == AlertKind.Watch
        AlertLevel.All -> true
    }
}

/** A text forecast period from the local NWS office ("Tonight: Rain likely, mainly after 9pm…"). */
data class NwsPeriod(val name: String, val detail: String, val isDay: Boolean)

/** The grid point's metadata: which office covers it and its nearest radar. */
data class NwsPoint(val office: String, val radar: String, val forecastUrl: String, val city: String, val state: String)

object Nws {
    private const val BASE = "https://api.weather.gov"
    private const val GEO = "application/geo+json"

    private fun coord(lat: Double, lon: Double) = String.format(Locale.US, "%.4f,%.4f", lat, lon)

    suspend fun alerts(lat: Double, lon: Double): List<WeatherAlert> =
        parseAlerts(Http.text("$BASE/alerts/active?point=${coord(lat, lon)}", GEO))

    fun parseAlerts(body: String): List<WeatherAlert> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val p = features.getJSONObject(i).optJSONObject("properties") ?: return@mapNotNull null
            if (p.optString("status") != "Actual") return@mapNotNull null
            WeatherAlert(
                id = p.optString("id").ifBlank { features.getJSONObject(i).optString("id") },
                event = p.optString("event"),
                headline = p.str("headline"),
                description = p.str("description"),
                instruction = p.str("instruction"),
                severity = p.optString("severity", "Unknown"),
                urgency = p.optString("urgency", "Unknown"),
                areas = p.str("areaDesc"),
                sender = p.str("senderName"),
                onset = time(p.str("onset").ifBlank { p.str("effective") }),
                expires = time(p.str("ends").ifBlank { p.str("expires") }),
            )
        }.sortedWith(compareBy<WeatherAlert> { it.kind.ordinal }.thenBy { severityRank(it.severity) })
    }

    private fun severityRank(s: String) = when (s) {
        "Extreme" -> 0
        "Severe" -> 1
        "Moderate" -> 2
        "Minor" -> 3
        else -> 4
    }

    suspend fun point(lat: Double, lon: Double): NwsPoint = parsePoint(Http.text("$BASE/points/${coord(lat, lon)}", GEO))

    fun parsePoint(body: String): NwsPoint {
        val p = JSONObject(body).getJSONObject("properties")
        val rel = p.optJSONObject("relativeLocation")?.optJSONObject("properties")
        return NwsPoint(
            office = p.optString("cwa"),
            radar = p.optString("radarStation"),
            forecastUrl = p.optString("forecast"),
            city = rel?.optString("city").orEmpty(),
            state = rel?.optString("state").orEmpty(),
        )
    }

    suspend fun periods(point: NwsPoint): List<NwsPeriod> {
        if (point.forecastUrl.isBlank()) return emptyList()
        return parsePeriods(Http.text(point.forecastUrl, GEO))
    }

    fun parsePeriods(body: String): List<NwsPeriod> {
        val a = JSONObject(body).optJSONObject("properties")?.optJSONArray("periods") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            NwsPeriod(o.optString("name"), o.optString("detailedForecast"), o.optBoolean("isDaytime", true))
        }
    }

    /** The latest Area Forecast Discussion: the forecasters' own reasoning, in plain text. */
    suspend fun discussion(office: String): String? {
        if (office.isBlank()) return null
        val list = JSONObject(Http.text("$BASE/products/types/AFD/locations/$office", "application/ld+json"))
        val graph = list.optJSONArray("@graph") ?: return null
        if (graph.length() == 0) return null
        val first = graph.getJSONObject(0)
        val url = first.optString("@id").ifBlank { "$BASE/products/" + first.optString("id") }
        val product = JSONObject(Http.text(url, "application/ld+json"))
        return product.optString("productText").takeIf { it.isNotBlank() }
    }

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

    private fun time(s: String): Long = if (s.isBlank()) 0L else runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrDefault(0L)
}
