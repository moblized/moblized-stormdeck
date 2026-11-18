package com.moblized.stormdeck.data

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { System("Match phone"), Light("Light"), Dark("Dark") }

enum class TempUnit(val label: String, val api: String, val symbol: String) {
    F("°F", "fahrenheit", "°F"), C("°C", "celsius", "°C"),
}

enum class WindUnit(val label: String, val api: String) {
    Mph("mph", "mph"), Kmh("km/h", "kmh"), Kt("knots", "kn"), Ms("m/s", "ms"),
}

enum class PrecipUnit(val label: String, val api: String) { In("inches", "inch"), Mm("mm", "mm") }

/** Which NWS alerts for your place should buzz your phone. */
enum class AlertLevel(val label: String) {
    Off("Off"),
    Warnings("Warnings only"),
    WarningsWatches("Warnings and watches"),
    All("Everything (incl. advisories)"),
}

/** Which newly formed tropical cyclones get a notification. */
enum class NewStormScope(val label: String) {
    Off("Off"),
    Atlantic("Atlantic & East Pacific (NHC)"),
    Everywhere("Anywhere in the world"),
}

/** Forecast models from Open-Meteo. "best_match" blends the best local models for your spot. */
enum class ForecastModel(val api: String, val label: String) {
    Nbm("ncep_nbm_conus", "NOAA National Blend (US)"),
    Best("best_match", "Best match (auto)"),
    Hrrr("gfs_hrrr", "NOAA HRRR (US, 3 km, 48 h)"),
    Gfs("gfs_seamless", "NOAA GFS"),
    Ecmwf("ecmwf_ifs025", "ECMWF IFS"),
    Icon("icon_seamless", "DWD ICON"),
    Gem("gem_seamless", "Canada GEM"),
    Ukmo("ukmo_seamless", "UK Met Office"),
    Jma("jma_seamless", "Japan JMA"),
    MeteoFrance("meteofrance_seamless", "Météo-France"),
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val temp: TempUnit = TempUnit.F,
    val wind: WindUnit = WindUnit.Mph,
    val precip: PrecipUnit = PrecipUnit.In,
    val model: ForecastModel = ForecastModel.Nbm,
    val alerts: AlertLevel = AlertLevel.Warnings,
    val newStorms: NewStormScope = NewStormScope.Atlantic,
    val stormNearby: Boolean = true,
    /** How close a storm's forecast track has to come before you're told, in miles. */
    val stormNearbyMiles: Int = 500,
    val radarOpacity: Float = 0.8f,
    val frameMillis: Int = 450,
    val autoUpdate: Boolean = true,
    val wifiOnly: Boolean = true,
    val autoInstall: Boolean = true,
)

fun AppSettings.isDark(context: Context): Boolean = when (theme) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.System ->
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val value: AppSettings get() = _settings.value

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = enumOf("theme", d.theme),
            temp = enumOf("temp", d.temp),
            wind = enumOf("wind", d.wind),
            precip = enumOf("precip", d.precip),
            // The National Blend became the default after the first release; move everyone onto it once.
            model = if (prefs.getBoolean("nbmDefault", false)) enumOf("model", d.model) else d.model,
            alerts = enumOf("alerts", d.alerts),
            newStorms = enumOf("newStorms", d.newStorms),
            stormNearby = prefs.getBoolean("stormNearby", d.stormNearby),
            stormNearbyMiles = prefs.getInt("stormNearbyMiles", d.stormNearbyMiles),
            radarOpacity = prefs.getFloat("radarOpacity", d.radarOpacity),
            frameMillis = prefs.getInt("frameMillis", d.frameMillis),
            autoUpdate = prefs.getBoolean("autoUpdate", d.autoUpdate),
            wifiOnly = prefs.getBoolean("wifiOnly", d.wifiOnly),
            autoInstall = prefs.getBoolean("autoInstall", d.autoInstall),
        )
    }

    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(_settings.value)
        prefs.edit()
            .putString("theme", s.theme.name)
            .putString("temp", s.temp.name)
            .putString("wind", s.wind.name)
            .putString("precip", s.precip.name)
            .putString("model", s.model.name)
            .putBoolean("nbmDefault", true)
            .putString("alerts", s.alerts.name)
            .putString("newStorms", s.newStorms.name)
            .putBoolean("stormNearby", s.stormNearby)
            .putInt("stormNearbyMiles", s.stormNearbyMiles)
            .putFloat("radarOpacity", s.radarOpacity)
            .putInt("frameMillis", s.frameMillis)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putBoolean("autoInstall", s.autoInstall)
            .apply()
        _settings.value = s
    }
}
