package com.moblized.stormdeck

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.moblized.stormdeck.data.AppSettings
import com.moblized.stormdeck.data.EnsoSource
import com.moblized.stormdeck.data.Enso
import com.moblized.stormdeck.data.LatLon
import com.moblized.stormdeck.data.Nws
import com.moblized.stormdeck.data.NwsPeriod
import com.moblized.stormdeck.data.NwsPoint
import com.moblized.stormdeck.data.OniPoint
import com.moblized.stormdeck.data.OpenMeteo
import com.moblized.stormdeck.data.Outlook
import com.moblized.stormdeck.data.Place
import com.moblized.stormdeck.data.Tropics
import com.moblized.stormdeck.data.WeeklyNino
import com.moblized.stormdeck.ui.EnsoActions
import com.moblized.stormdeck.ui.EnsoContent
import com.moblized.stormdeck.ui.ForecastActions
import com.moblized.stormdeck.ui.ForecastContent
import com.moblized.stormdeck.ui.NwsInfo
import com.moblized.stormdeck.ui.Remote
import com.moblized.stormdeck.ui.SettingsActions
import com.moblized.stormdeck.ui.SettingsContent
import com.moblized.stormdeck.ui.StormTheme
import com.moblized.stormdeck.ui.TropicsActions
import com.moblized.stormdeck.ui.TropicsContent
import com.moblized.stormdeck.update.Updater
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.math.sin

/** Renders the phone screens with sample data, so the look can be checked from CI without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val place = Place(Place.CURRENT, "Brooklyn", "New York, US", 40.65, -73.95)

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            StormTheme(dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
        println("SHOT $name")
    }

    private fun nws(): NwsInfo = NwsInfo(
        NwsPoint("OKX", "KOKX", "", "Brooklyn", "NY"),
        listOf(
            NwsPeriod("Today", "Showers and thunderstorms likely after 1pm. Cloudy, with a high near 58. Southwest wind 10 to 15 mph, with gusts as high as 30 mph. Chance of precipitation is 80%.", true),
            NwsPeriod("Tonight", "Showers likely before 9pm. Mostly cloudy, with a low around 44.", false),
        ),
        "000\nFXUS61 KOKX 181021\nAFDOKX\n\nArea Forecast Discussion\nNational Weather Service New York NY\n521 AM EST Wed Nov 18 2026\n\n.SYNOPSIS...\nA cold front approaches from the west today and moves through this evening. High pressure builds in for the end of the week.\n\n&&\n\n.NEAR TERM /THROUGH TONIGHT/...\nA line of showers and embedded thunderstorms ahead of the front...",
    )

    @Test
    fun forecastDark() = shoot("01_forecast_dark", dark = true) {
        ForecastContent(place, Remote(OpenMeteo.parse(Samples.openMeteo())), Nws.parseAlerts(Samples.NWS_ALERTS), nws(), AppSettings(), ForecastActions())
    }

    @Test
    fun forecastLight() = shoot("02_forecast_light", dark = false) {
        ForecastContent(place, Remote(OpenMeteo.parse(Samples.openMeteo())), emptyList(), null, AppSettings(), ForecastActions())
    }

    @Test
    @Config(qualifiers = "w412dp-h2400dp-xxhdpi")
    fun forecastLong() = shoot("03_forecast_full", dark = true) {
        ForecastContent(place, Remote(OpenMeteo.parse(Samples.openMeteo())), emptyList(), nws(), AppSettings(), ForecastActions())
    }

    @Test
    fun tropics() {
        val storms = Tropics.merge(Tropics.parseGdacsList(Samples.GDACS_LIST), Tropics.parseNhc(Samples.NHC))
        val tracks = mapOf(storms.first { it.name == "Akvan" }.key to Tropics.parseGdacsGeometry(Samples.GDACS_GEOMETRY))
        shoot("04_tropics", dark = true) {
            TropicsContent(
                Remote(storms), tracks,
                listOf(Outlook("Atlantic", Tropics.preText(Samples.TWO_HTML)!!)),
                LatLon(25.8, -80.2), AppSettings(), storms.first().key, TropicsActions(),
            )
        }
    }

    @Test
    fun tropicsQuietLight() = shoot("05_tropics_quiet_light", dark = false) {
        TropicsContent(Remote(emptyList()), emptyMap(), emptyList(), null, AppSettings(), null, TropicsActions())
    }

    private fun enso(): Enso {
        val oni = buildList {
            val seasons = listOf("DJF", "JFM", "FMA", "MAM", "AMJ", "MJJ", "JJA", "JAS", "ASO", "SON", "OND", "NDJ")
            var i = 0
            for (y in 2021..2026) for (s in seasons) {
                if (y == 2026 && s == "ASO") break
                add(OniPoint(s, y, 1.6 * sin(i / 9.0) + if (y == 2026) i % 12 * 0.18 else 0.0))
                i++
            }
        }
        val start = LocalDate.of(2025, 11, 5)
        val weekly = (0 until 53).map { w ->
            val v = -0.6 + w * 0.06
            WeeklyNino(start.plusWeeks(w.toLong()), v + 0.4, v + 0.1, v, v * 0.4, 26.5 + v)
        }
        return Enso(EnsoSource.parseStatus(Samples.ENSO_HTML), oni, weekly)
    }

    @Test
    @Config(qualifiers = "w412dp-h1900dp-xxhdpi")
    fun ensoDark() = shoot("06_enso_dark", dark = true) {
        EnsoContent(Remote(enso()), EnsoActions(), showImages = false)
    }

    @Test
    fun ensoLight() = shoot("07_enso_light", dark = false) {
        EnsoContent(Remote(enso()), EnsoActions(), showImages = false)
    }

    @Test
    @Config(qualifiers = "w412dp-h1600dp-xxhdpi")
    fun settings() = shoot("08_settings", dark = true) {
        SettingsContent(AppSettings(), Updater.Status.UpToDate, "1.0.1", "Brooklyn", SettingsActions(onAllowNotifications = {}))
    }
}
