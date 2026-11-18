package com.moblized.stormdeck.car

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.moblized.stormdeck.Graph
import com.moblized.stormdeck.R
import com.moblized.stormdeck.data.AppSettings
import com.moblized.stormdeck.data.Fmt
import com.moblized.stormdeck.data.Forecast
import com.moblized.stormdeck.data.Geo
import com.moblized.stormdeck.data.LatLon
import com.moblized.stormdeck.data.Nws
import com.moblized.stormdeck.data.Place
import com.moblized.stormdeck.data.Radar
import com.moblized.stormdeck.data.Storm
import com.moblized.stormdeck.data.Tropics
import com.moblized.stormdeck.data.WeatherAlert
import com.moblized.stormdeck.data.WeatherCode
import com.moblized.stormdeck.data.WeatherRepo
import com.moblized.stormdeck.data.isDark
import com.moblized.stormdeck.ui.WeatherIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Android Auto's way in. One session per connection to the car. */
class StormDeckCarService : CarAppService() {
    // A sideloaded app for your own use: any Android Auto host may connect.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = StormDeckSession()
}

class StormDeckSession : Session() {
    private var renderer: CarMapRenderer? = null

    override fun onCreateScreen(intent: Intent): Screen {
        Graph.init(carContext)
        val r = CarMapRenderer().also { renderer = it }
        runCatching { carContext.getCarService(AppManager::class.java).setSurfaceCallback(r) }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                renderer?.release()
                renderer = null
            }
        })
        return RadarCarScreen(carContext, r)
    }
}

internal fun CarContext.icon(@DrawableRes res: Int, tinted: Boolean = true): CarIcon {
    val b = CarIcon.Builder(IconCompat.createWithResource(this, res))
    if (tinted) b.setTint(CarColor.DEFAULT)
    return b.build()
}

internal fun CarContext.apiLevel(): Int = runCatching { carAppApiLevel }.getOrDefault(1)

/** Shared state between the car screens: what was loaded last. */
internal object CarState {
    var place: Place? = null
    var forecast: Forecast? = null
    var alerts: List<WeatherAlert> = emptyList()
    var storms: List<Storm> = emptyList()
}

/** The main car screen: full-screen animated radar with conditions in the corner. */
class RadarCarScreen(carContext: CarContext, private val map: CarMapRenderer) : Screen(carContext) {
    private var status = "Loading…"
    private var detail = ""
    private var icon: CarIcon? = null
    private var panning = false

    private var job: kotlinx.coroutines.Job? = null

    init {
        // Start loading once the car shows the screen; refresh every five minutes while it's up.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (job?.isActive == true) return
                job = lifecycleScope.launch {
                    while (isActive) {
                        runCatching { load() }
                        delay(5 * 60_000L)
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                job?.cancel()
                job = null
            }
        })
    }

    private suspend fun load() {
        val s = Graph.settings.value
        map.dark = s.isDark(carContext)
        val place = runCatching { WeatherRepo.refreshCurrentPlace(carContext) }.getOrNull() ?: Graph.places.selected()
        CarState.place = place
        if (place == null) {
            status = "Location needed"
            detail = "Open StormDeck on your phone and allow location."
            invalidate()
            return
        }
        if (map.me == null) map.center = place.point
        map.me = place.point

        val f = runCatching { WeatherRepo.forecast(place, s) }.getOrNull() ?: WeatherRepo.cached(place, s)
        CarState.forecast = f
        if (f != null) {
            status = "${Fmt.temp(f.current.temp, s)} ${WeatherCode.text(f.current.code)}"
            detail = listOfNotNull(
                place.name,
                f.daily.firstOrNull()?.let { "H ${Fmt.temp(it.high, s)} L ${Fmt.temp(it.low, s)}" },
                f.nowcast(),
            ).joinToString(" · ")
            icon = CarIcon.Builder(
                IconCompat.createWithBitmap(WeatherIcons.bitmap(WeatherCode.sky(f.current.code), f.current.isDay, 128)),
            ).build()
        }
        invalidate()

        val inUs = Geo.inUsRadarArea(place.point)
        val frames = runCatching { if (inUs) Radar.usMosaic() else Radar.global() }.getOrNull()
            ?: runCatching { Radar.global() }.getOrNull()
        map.frames = frames

        if (inUs) {
            CarState.alerts = runCatching { Nws.alerts(place.lat, place.lon) }.getOrDefault(CarState.alerts)
            invalidate()
        }
        CarState.storms = runCatching { Tropics.active() }.getOrDefault(CarState.storms)
        map.storms = CarState.storms
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val alerts = CarState.alerts
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("Forecast").setIcon(carContext.icon(R.drawable.ic_cloud))
                    .setOnClickListener { screenManager.push(ForecastCarScreen(carContext)) }.build(),
            )
            .addAction(
                Action.Builder().setTitle(if (alerts.isEmpty()) "Alerts" else "Alerts (${alerts.size})")
                    .setIcon(carContext.icon(R.drawable.ic_warning))
                    .setOnClickListener { screenManager.push(AlertsCarScreen(carContext)) }.build(),
            )
            .addAction(
                Action.Builder().setTitle("Tropics").setIcon(carContext.icon(R.drawable.ic_hurricane))
                    .setOnClickListener { screenManager.push(StormsCarScreen(carContext)) }.build(),
            )
            .addAction(
                Action.Builder().setIcon(carContext.icon(if (map.playing) R.drawable.ic_pause else R.drawable.ic_play))
                    .setOnClickListener {
                        map.playing = !map.playing
                        invalidate()
                    }.build(),
            )
            .build()

        val b = NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setBackgroundColor(CarColor.createCustom(0xFF0E1626.toInt(), 0xFF0E1626.toInt()))
        if (carContext.apiLevel() >= 2) {
            b.setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_zoom_in)).setOnClickListener { map.zoomBy(1.0) }.build())
                    .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_zoom_out)).setOnClickListener { map.zoomBy(-1.0) }.build())
                    .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_my_location)).setOnClickListener { map.recenter() }.build())
                    .build(),
            )
            b.setPanModeListener { isInPanMode ->
                panning = isInPanMode
                invalidate()
            }
        }
        if (!panning) {
            val info = MessageInfo.Builder(status)
            if (detail.isNotBlank()) info.setText(detail)
            icon?.let { info.setImage(it) }
            b.setNavigationInfo(info.build())
        }
        return b.build()
    }
}

class ForecastCarScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val s: AppSettings = Graph.settings.value
        val f = CarState.forecast
        val list = ItemList.Builder()
        if (f == null) {
            list.setNoItemsMessage("No forecast yet")
        } else {
            // Car lists show about six rows: the next three hours, then three days.
            val nowcast = f.nowcast()
            f.upcoming().take(3).forEachIndexed { i, h ->
                list.addItem(
                    Row.Builder()
                        .setTitle("${Fmt.hour(h.time, f.offsetSec)} · ${Fmt.temp(h.temp, s)} ${WeatherCode.text(h.code)}")
                        .addText("Rain ${if (h.precipChance.isNaN()) "--" else h.precipChance.roundToInt()}% · Wind ${Fmt.wind(h.windSpeed, s)} gusts ${Fmt.wind(h.windGust, s)}")
                        .apply { if (i == 0 && nowcast != null) addText(nowcast) }
                        .setImage(CarIcon.Builder(IconCompat.createWithBitmap(WeatherIcons.bitmap(WeatherCode.sky(h.code), h.isDay, 96))).build())
                        .build(),
                )
            }
            f.daily.take(3).forEach { d ->
                list.addItem(
                    Row.Builder()
                        .setTitle("${Fmt.dayName(d.time + 43200, f.offsetSec)} · ${Fmt.temp(d.high, s)} / ${Fmt.temp(d.low, s)}")
                        .addText("${WeatherCode.text(d.code)} · Rain ${if (d.precipChance.isNaN()) "--" else d.precipChance.roundToInt()}%")
                        .setImage(CarIcon.Builder(IconCompat.createWithBitmap(WeatherIcons.bitmap(WeatherCode.sky(d.code), true, 96))).build())
                        .build(),
                )
            }
        }
        return ListTemplate.Builder()
            .setTitle(CarState.place?.name ?: "Forecast")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

class AlertsCarScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val alerts = CarState.alerts
        val list = ItemList.Builder()
        if (alerts.isEmpty()) list.setNoItemsMessage("No active NWS alerts for ${CarState.place?.name ?: "your area"}")
        alerts.take(6).forEach { a ->
            list.addItem(
                Row.Builder()
                    .setTitle(a.event)
                    .addText(a.headline.ifBlank { a.areas }.take(120))
                    .setOnClickListener { screenManager.push(AlertDetailCarScreen(carContext, a)) }
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle("Weather alerts")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

class AlertDetailCarScreen(carContext: CarContext, private val a: WeatherAlert) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder((a.headline.ifBlank { a.event } + "\n\n" + a.instruction.ifBlank { a.description }).take(480))
            .setTitle(a.event)
            .setHeaderAction(Action.BACK)
            .build()
}

class StormsCarScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val s = Graph.settings.value
        val me: LatLon? = CarState.place?.point
        val list = ItemList.Builder()
        val storms = CarState.storms
        if (storms.isEmpty()) list.setNoItemsMessage("No active tropical cyclones")
        storms.sortedBy { st -> me?.let { Geo.distanceKm(it, st.point) } ?: 0.0 }.take(6).forEach { st ->
            val dist = me?.let { " · ${Fmt.km(Geo.distanceKm(it, st.point), s)} away" }.orEmpty()
            list.addItem(
                Row.Builder()
                    .setTitle("${st.type} ${st.name}")
                    .addText("${st.category.short} · ${Fmt.knots(st.windKt, s)}${st.movement?.let { " · $it" }.orEmpty()}")
                    .addText(st.basin.label + dist)
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle("Tropics")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
