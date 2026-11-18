package com.moblized.stormdeck.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** What the sky is doing, from the WMO weather code. */
enum class Sky { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, HeavyRain, Freezing, Snow, Thunder }

object WeatherCode {
    fun text(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45 -> "Fog"
        48 -> "Freezing fog"
        51 -> "Light drizzle"
        53 -> "Drizzle"
        55 -> "Heavy drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Violent showers"
        85 -> "Snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorms"
        96, 99 -> "Thunderstorms with hail"
        else -> "—"
    }

    fun sky(code: Int): Sky = when (code) {
        0, 1 -> Sky.Clear
        2 -> Sky.PartlyCloudy
        3 -> Sky.Cloudy
        45, 48 -> Sky.Fog
        51, 53, 55 -> Sky.Drizzle
        61, 63, 80, 81 -> Sky.Rain
        65, 82 -> Sky.HeavyRain
        56, 57, 66, 67 -> Sky.Freezing
        71, 73, 75, 77, 85, 86 -> Sky.Snow
        95, 96, 99 -> Sky.Thunder
        else -> Sky.Cloudy
    }
}

data class Current(
    val time: Long,
    val temp: Double,
    val feelsLike: Double,
    val humidity: Double,
    val dewPoint: Double,
    val isDay: Boolean,
    val precip: Double,
    val code: Int,
    val cloudCover: Double,
    val pressure: Double,
    val windSpeed: Double,
    val windDir: Double,
    val windGust: Double,
    val visibility: Double,
    val uv: Double,
    val cape: Double,
)

data class Hour(
    val time: Long,
    val temp: Double,
    val feelsLike: Double,
    val precipChance: Double,
    val precip: Double,
    val snowfall: Double,
    val code: Int,
    val windSpeed: Double,
    val windDir: Double,
    val windGust: Double,
    val dewPoint: Double,
    val humidity: Double,
    val cape: Double,
    val visibility: Double,
    val cloudCover: Double,
    val pressure: Double,
    val uv: Double,
    val isDay: Boolean,
    val freezingLevel: Double,
)

data class Day(
    val date: LocalDate,
    val time: Long,
    val code: Int,
    val high: Double,
    val low: Double,
    val precip: Double,
    val snowfall: Double,
    val precipChance: Double,
    val windMax: Double,
    val gustMax: Double,
    val windDir: Double,
    val sunrise: Long,
    val sunset: Long,
    val uvMax: Double,
)

data class Minute15(val time: Long, val precip: Double)

data class Forecast(
    val lat: Double,
    val lon: Double,
    val offsetSec: Int,
    val timezone: String,
    val current: Current,
    val hourly: List<Hour>,
    val daily: List<Day>,
    val minutely: List<Minute15>,
    val visibilityUnit: String,
    val heightUnit: String,
    val model: ForecastModel,
    val fetchedAt: Long,
) {
    /** Hours from the current one on. */
    fun upcoming(): List<Hour> {
        val now = current.time - 3600
        return hourly.filter { it.time >= now }
    }

    /** "Rain starting in about 25 min", from the 15-minute forecast, or null if it stays dry. */
    fun nowcast(): String? {
        if (minutely.isEmpty()) return null
        val now = System.currentTimeMillis() / 1000
        val ahead = minutely.filter { it.time >= now - 900 }.take(8)
        if (ahead.isEmpty()) return null
        val wetNow = ahead.first().precip > 0.0
        return if (wetNow) {
            val dry = ahead.indexOfFirst { it.precip <= 0.0 }
            if (dry < 0) "Precipitation continuing for the next 2 hours" else "Precipitation ending in about ${dry * 15} min"
        } else {
            val wet = ahead.indexOfFirst { it.precip > 0.0 }
            if (wet < 0) null else "Precipitation starting in about ${wet * 15} min"
        }
    }
}

object OpenMeteo {
    private const val CURRENT = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code," +
        "cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m,dew_point_2m,visibility,uv_index,cape"
    private const val HOURLY = "temperature_2m,apparent_temperature,precipitation_probability,precipitation,snowfall,weather_code," +
        "wind_speed_10m,wind_direction_10m,wind_gusts_10m,dew_point_2m,relative_humidity_2m,cape,visibility,cloud_cover," +
        "pressure_msl,uv_index,is_day,freezing_level_height"
    private const val DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,snowfall_sum," +
        "precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant,sunrise,sunset,uv_index_max"

    fun url(lat: Double, lon: Double, s: AppSettings, model: ForecastModel = s.model): String {
        val sb = StringBuilder("https://api.open-meteo.com/v1/forecast")
        sb.append("?latitude=").append(String.format(Locale.US, "%.4f", lat))
        sb.append("&longitude=").append(String.format(Locale.US, "%.4f", lon))
        sb.append("&current=").append(CURRENT)
        sb.append("&hourly=").append(HOURLY)
        sb.append("&daily=").append(DAILY)
        sb.append("&temperature_unit=").append(s.temp.api)
        sb.append("&wind_speed_unit=").append(s.wind.api)
        sb.append("&precipitation_unit=").append(s.precip.api)
        sb.append("&timezone=auto&forecast_days=10")
        if (model == ForecastModel.Best) {
            sb.append("&minutely_15=precipitation&forecast_minutely_15=12&past_minutely_15=1")
        } else {
            sb.append("&models=").append(model.api)
        }
        return sb.toString()
    }

    suspend fun fetch(lat: Double, lon: Double, s: AppSettings): Forecast {
        val body = Http.text(url(lat, lon, s))
        return parse(body, s.model)
    }

    fun parse(body: String, model: ForecastModel = ForecastModel.Best, fetchedAt: Long = System.currentTimeMillis()): Forecast {
        val o = JSONObject(body)
        if (o.optBoolean("error")) throw IllegalStateException(o.optString("reason", "Forecast unavailable"))
        val offset = o.optInt("utc_offset_seconds")
        val zone = ZoneOffset.ofTotalSeconds(offset)
        fun epoch(t: String): Long = runCatching { LocalDateTime.parse(t).toEpochSecond(zone) }.getOrDefault(0L)

        val c = o.getJSONObject("current")
        val current = Current(
            time = epoch(c.optString("time")),
            temp = c.num("temperature_2m"),
            feelsLike = c.num("apparent_temperature"),
            humidity = c.num("relative_humidity_2m"),
            dewPoint = c.num("dew_point_2m"),
            isDay = c.optInt("is_day", 1) == 1,
            precip = c.num("precipitation"),
            code = c.optInt("weather_code", 3),
            cloudCover = c.num("cloud_cover"),
            pressure = c.num("pressure_msl"),
            windSpeed = c.num("wind_speed_10m"),
            windDir = c.num("wind_direction_10m"),
            windGust = c.num("wind_gusts_10m"),
            visibility = c.num("visibility"),
            uv = c.num("uv_index"),
            cape = c.num("cape"),
        )

        val h = o.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hourly = (0 until ht.length()).map { i ->
            Hour(
                time = epoch(ht.getString(i)),
                temp = h.at("temperature_2m", i),
                feelsLike = h.at("apparent_temperature", i),
                precipChance = h.at("precipitation_probability", i),
                precip = h.at("precipitation", i),
                snowfall = h.at("snowfall", i),
                code = h.at("weather_code", i).let { if (it.isNaN()) 3 else it.toInt() },
                windSpeed = h.at("wind_speed_10m", i),
                windDir = h.at("wind_direction_10m", i),
                windGust = h.at("wind_gusts_10m", i),
                dewPoint = h.at("dew_point_2m", i),
                humidity = h.at("relative_humidity_2m", i),
                cape = h.at("cape", i),
                visibility = h.at("visibility", i),
                cloudCover = h.at("cloud_cover", i),
                pressure = h.at("pressure_msl", i),
                uv = h.at("uv_index", i),
                isDay = h.at("is_day", i).let { it.isNaN() || it >= 0.5 },
                freezingLevel = h.at("freezing_level_height", i),
            )
        }.filter { !it.temp.isNaN() }

        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val daily = (0 until dt.length()).map { i ->
            val date = LocalDate.parse(dt.getString(i))
            Day(
                date = date,
                time = date.atStartOfDay().toEpochSecond(zone),
                code = d.at("weather_code", i).let { if (it.isNaN()) 3 else it.toInt() },
                high = d.at("temperature_2m_max", i),
                low = d.at("temperature_2m_min", i),
                precip = d.at("precipitation_sum", i),
                snowfall = d.at("snowfall_sum", i),
                precipChance = d.at("precipitation_probability_max", i),
                windMax = d.at("wind_speed_10m_max", i),
                gustMax = d.at("wind_gusts_10m_max", i),
                windDir = d.at("wind_direction_10m_dominant", i),
                sunrise = d.optJSONArray("sunrise")?.optString(i)?.let { epoch(it) } ?: 0L,
                sunset = d.optJSONArray("sunset")?.optString(i)?.let { epoch(it) } ?: 0L,
                uvMax = d.at("uv_index_max", i),
            )
        }.filter { !it.high.isNaN() }

        val minutely = o.optJSONObject("minutely_15")?.let { m ->
            val mt = m.optJSONArray("time") ?: JSONArray()
            (0 until mt.length()).map { i -> Minute15(epoch(mt.getString(i)), m.at("precipitation", i)) }
                .filter { !it.precip.isNaN() }
        } ?: emptyList()

        val units = o.optJSONObject("hourly_units")
        return Forecast(
            lat = o.optDouble("latitude"),
            lon = o.optDouble("longitude"),
            offsetSec = offset,
            timezone = o.optString("timezone"),
            current = current,
            hourly = hourly,
            daily = daily,
            minutely = minutely,
            visibilityUnit = units?.optString("visibility", "m") ?: "m",
            heightUnit = units?.optString("freezing_level_height", "m") ?: "m",
            model = model,
            fetchedAt = fetchedAt,
        )
    }
}

internal fun JSONObject.num(key: String): Double =
    if (isNull(key) || !has(key)) Double.NaN else optDouble(key, Double.NaN)

internal fun JSONObject.at(key: String, i: Int): Double {
    val a = optJSONArray(key) ?: return Double.NaN
    return if (i >= a.length() || a.isNull(i)) Double.NaN else a.optDouble(i, Double.NaN)
}
