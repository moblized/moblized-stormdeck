package com.moblized.stormdeck.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.abs

/** One 3-month Oceanic Niño Index value, e.g. JAS 2026 = +2.16 °C. */
data class OniPoint(val season: String, val year: Int, val anomaly: Double) {
    val label get() = "$season $year"
}

/** One week of Niño-region sea-surface temperature anomalies (°C), from CPC. */
data class WeeklyNino(val date: LocalDate, val nino12: Double, val nino3: Double, val nino34: Double, val nino4: Double, val sst34: Double)

data class EnsoStatus(val alert: String, val synopsis: String, val issued: String)

enum class EnsoPhase(val label: String) { ElNino("El Niño"), LaNina("La Niña"), Neutral("Neutral") }

data class Enso(
    val status: EnsoStatus?,
    val oni: List<OniPoint>,
    val weekly: List<WeeklyNino>,
) {
    val latestWeek get() = weekly.lastOrNull()
    val latestOni get() = oni.lastOrNull()

    companion object {
        fun phase(anomaly: Double): EnsoPhase = when {
            anomaly >= 0.5 -> EnsoPhase.ElNino
            anomaly <= -0.5 -> EnsoPhase.LaNina
            else -> EnsoPhase.Neutral
        }

        /** NOAA's customary strength words for a given anomaly. */
        fun strength(anomaly: Double): String {
            val a = abs(anomaly)
            return when {
                a < 0.5 -> "Neutral"
                a < 1.0 -> "Weak"
                a < 1.5 -> "Moderate"
                a < 2.0 -> "Strong"
                else -> "Very strong"
            }
        }
    }
}

object EnsoSource {
    private const val CPC = "https://www.cpc.ncep.noaa.gov"

    suspend fun load(): Enso = coroutineScope {
        val st = async { runCatching { parseStatus(Http.text("$CPC/products/analysis_monitoring/enso_advisory/ensodisc.shtml")) }.getOrNull() }
        val oni = async { runCatching { parseOni(Http.text("$CPC/data/indices/oni.ascii.txt")) }.getOrDefault(emptyList()) }
        val wk = async { runCatching { parseWeekly(Http.text("$CPC/data/indices/wksst9120.for")) }.getOrDefault(emptyList()) }
        val e = Enso(st.await(), oni.await(), wk.await())
        if (e.status == null && e.oni.isEmpty() && e.weekly.isEmpty()) throw IllegalStateException("Couldn't reach NOAA's Climate Prediction Center.")
        e
    }

    fun parseOni(text: String): List<OniPoint> = text.lineSequence().mapNotNull { line ->
        val p = line.trim().split(Regex("\\s+"))
        if (p.size < 4) return@mapNotNull null
        val year = p[1].toIntOrNull() ?: return@mapNotNull null
        val anom = p[3].toDoubleOrNull() ?: return@mapNotNull null
        if (p[0].length != 3) return@mapNotNull null
        OniPoint(p[0], year, anom)
    }.toList()

    private val weekDate = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("ddMMMyyyy").toFormatter(Locale.US)
    private val pair = Regex("(\\d{1,2}\\.\\d)\\s*(-?\\d{1,2}\\.\\d)")

    /**
     * Lines look like ` 05NOV2025     22.1 0.4     25.3-0.1     27.1 0.9     29.1 0.6`:
     * date, then SST and anomaly for Niño 1+2, 3, 3.4 and 4 (anomalies can touch the SST).
     */
    fun parseWeekly(text: String): List<WeeklyNino> = text.lineSequence().mapNotNull { line ->
        val t = line.trim()
        if (t.length < 9) return@mapNotNull null
        val date = runCatching { LocalDate.parse(t.substring(0, 9), weekDate) }.getOrNull() ?: return@mapNotNull null
        val vals = pair.findAll(t.substring(9)).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        if (vals.size < 4) return@mapNotNull null
        WeeklyNino(date, vals[0].second, vals[1].second, vals[2].second, vals[3].second, vals[2].first)
    }.toList()

    /** Pulls the alert status, synopsis and issue date out of CPC's ENSO diagnostic discussion page. */
    fun parseStatus(html: String): EnsoStatus? {
        val text = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&ntilde;", "ñ").replace("&#241;", "ñ")
            .replace(Regex("\\s+"), " ")
        // The statuses CPC uses: El Niño/La Niña Watch or Advisory, a Final Advisory, or Not Active.
        val known = Regex(
            "ENSO Alert System Status:\\s*((?:Final\\s+)?(?:El Ni.{1,3}o|La Ni.{1,3}a)\\s+(?:Advisory|Watch)|Not Active)",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.get(1)
        val alert = known ?: Regex("ENSO Alert System Status:\\s*(.{3,40}?)\\s*(Synopsis:|issued|$)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim()?.trim('*', ' ', '[', ']')
        val synopsis = Regex("Synopsis:\\s*(.+?\\.)(\\s|$)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        if (alert == null && synopsis == null) return null
        val months = "January|February|March|April|May|June|July|August|September|October|November|December"
        val issued = Regex("(\\d{1,2} ($months) \\d{4})").find(text)?.groupValues?.get(1).orEmpty()
        return EnsoStatus(alert?.let(::tidyNino)?.take(80) ?: "Unknown", synopsis?.let(::tidyNino).orEmpty(), issued)
    }

    /** Repairs "Niño"/"Niña" when the page's accents arrive mangled. */
    private fun tidyNino(s: String): String = s
        .replace(Regex("El Ni.{1,3}o\\b")) { "El Niño" }
        .replace(Regex("La Ni.{1,3}a\\b")) { "La Niña" }
}
