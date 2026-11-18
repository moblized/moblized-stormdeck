package com.moblized.stormdeck.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Formatting of values already in the units the user picked (Open-Meteo answers in those units). */
object Fmt {
    fun temp(v: Double?, s: AppSettings): String = if (v == null || v.isNaN()) "--" else "${v.roundToInt()}°"

    fun tempFull(v: Double?, s: AppSettings): String = if (v == null || v.isNaN()) "--" else "${v.roundToInt()}${s.temp.symbol}"

    fun wind(v: Double?, s: AppSettings): String = if (v == null || v.isNaN()) "--" else "${v.roundToInt()} ${s.wind.label}"

    fun precip(v: Double?, s: AppSettings): String = when {
        v == null || v.isNaN() -> "--"
        s.precip == PrecipUnit.In -> String.format(Locale.US, "%.2f in", v)
        else -> String.format(Locale.US, "%.1f mm", v)
    }

    fun pressure(hpa: Double?, s: AppSettings): String = when {
        hpa == null || hpa.isNaN() -> "--"
        s.temp == TempUnit.F -> String.format(Locale.US, "%.2f inHg", hpa * 0.0295300)
        else -> "${hpa.roundToInt()} hPa"
    }

    /** [value] in the unit Open-Meteo reported ("m" or "ft"). */
    fun distance(value: Double?, unit: String, s: AppSettings): String {
        if (value == null || value.isNaN()) return "--"
        val meters = if (unit.startsWith("ft")) value * 0.3048 else value
        return if (s.temp == TempUnit.F) {
            val mi = meters / 1609.344
            if (mi >= 10) "${mi.roundToInt()} mi" else String.format(Locale.US, "%.1f mi", mi)
        } else {
            val km = meters / 1000
            if (km >= 10) "${km.roundToInt()} km" else String.format(Locale.US, "%.1f km", km)
        }
    }

    fun height(value: Double?, unit: String, s: AppSettings): String {
        if (value == null || value.isNaN()) return "--"
        val meters = if (unit.startsWith("ft")) value * 0.3048 else value
        return if (s.temp == TempUnit.F) "${(meters / 0.3048).roundToInt()} ft" else "${meters.roundToInt()} m"
    }

    /** Knots to the user's wind unit, for hurricane winds. */
    fun knots(kt: Double, s: AppSettings): String = when (s.wind) {
        WindUnit.Kt -> "${kt.roundToInt()} kt"
        WindUnit.Mph -> "${(kt * 1.15078).roundToInt()} mph"
        WindUnit.Kmh -> "${(kt * 1.852).roundToInt()} km/h"
        WindUnit.Ms -> "${(kt * 0.514444).roundToInt()} m/s"
    }

    fun km(km: Double, s: AppSettings): String =
        if (s.temp == TempUnit.F) "${(km / 1.609344).roundToInt()} mi" else "${km.roundToInt()} km"

    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val hourOnly = DateTimeFormatter.ofPattern("h a", Locale.US)
    private val dayName = DateTimeFormatter.ofPattern("EEE", Locale.US)
    private val dayDate = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
    private val stamp = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

    fun clock(epochSec: Long, offsetSec: Int): String = clock.format(Instant.ofEpochSecond(epochSec).atOffset(ZoneOffset.ofTotalSeconds(offsetSec)))

    fun hour(epochSec: Long, offsetSec: Int): String = hourOnly.format(Instant.ofEpochSecond(epochSec).atOffset(ZoneOffset.ofTotalSeconds(offsetSec)))

    fun dayName(epochSec: Long, offsetSec: Int): String = dayName.format(Instant.ofEpochSecond(epochSec).atOffset(ZoneOffset.ofTotalSeconds(offsetSec)))

    fun dayDate(epochSec: Long, offsetSec: Int): String = dayDate.format(Instant.ofEpochSecond(epochSec).atOffset(ZoneOffset.ofTotalSeconds(offsetSec)))

    /** A time in the phone's own time zone. */
    fun localClock(millis: Long): String = clock.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun localStamp(millis: Long): String = stamp.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        val min = ((now - millis) / 60_000).toInt()
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ${min % 60} min ago"
            else -> "${min / 1440} days ago"
        }
    }

    fun signed(v: Double, digits: Int = 1): String = (if (v > 0) "+" else "") + String.format(Locale.US, "%.${digits}f", v)
}
