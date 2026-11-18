package com.moblized.stormdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moblized.stormdeck.data.AppSettings
import com.moblized.stormdeck.data.Day
import com.moblized.stormdeck.data.Fmt
import com.moblized.stormdeck.data.Forecast
import com.moblized.stormdeck.data.ForecastModel
import com.moblized.stormdeck.data.Geo
import com.moblized.stormdeck.data.Place
import com.moblized.stormdeck.data.WeatherAlert
import com.moblized.stormdeck.data.WeatherCode
import kotlin.math.roundToInt

data class ForecastActions(
    val onPlaces: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onModel: (ForecastModel) -> Unit = {},
)

@Composable
fun ForecastContent(
    place: Place?,
    forecast: Remote<Forecast>,
    alerts: List<WeatherAlert>,
    nws: NwsInfo?,
    s: AppSettings,
    actions: ForecastActions,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var openAlert by remember { mutableStateOf<WeatherAlert?>(null) }
    val f = forecast.value
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(place, f, forecast.loading, actions) }
        if (f == null) {
            item {
                when {
                    forecast.error != null -> ErrorRow(forecast.error, actions.onRefresh)
                    place == null -> Panel {
                        Text("Where are you?", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("Allow location, or pick a place, to see your forecast.", color = LocalStorm.current.dim)
                        TextButton(onClick = actions.onPlaces) { Text("Choose a place") }
                    }
                    else -> LoadingRow("Loading forecast…")
                }
            }
            return@LazyColumn
        }
        if (forecast.error != null) item { Text(forecast.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
        item { Hero(f, s) }
        if (alerts.isNotEmpty()) {
            items(alerts, key = { it.id }) { a -> AlertChip(a) { openAlert = a } }
        }
        item {
            SectionTitle("Next 48 hours")
            Panel(padding = 12.dp) {
                val hours = f.upcoming().take(48)
                HourlyChart(hours, f.offsetSec)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(hours.take(36)) { h ->
                        Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(Fmt.hour(h.time, f.offsetSec), style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim)
                            WeatherIcon(WeatherCode.sky(h.code), h.isDay, 30.dp, Modifier.padding(vertical = 2.dp))
                            Text(Fmt.temp(h.temp, s), fontWeight = FontWeight.SemiBold)
                            Text(
                                if (h.precipChance.isNaN()) " " else "${h.precipChance.roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalStorm.current.cool,
                            )
                            Text(
                                "${h.windSpeed.roundToInt()}${if (h.windGust > h.windSpeed + 8) "g${h.windGust.roundToInt()}" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalStorm.current.dim,
                            )
                        }
                    }
                }
            }
        }
        item { Details(f, s) }
        item {
            SectionTitle("10 days")
            Panel(padding = 8.dp) {
                val lo = f.daily.minOfOrNull { it.low } ?: 0.0
                val hi = f.daily.maxOfOrNull { it.high } ?: 1.0
                f.daily.forEach { d -> DayRow(d, f, s, lo, hi) }
            }
        }
        item { ModelPicker(s.model, actions.onModel) }
        if (nws != null && nws.periods.isNotEmpty()) {
            item {
                SectionTitle("National Weather Service · ${nws.point.office}")
                Panel {
                    nws.periods.take(6).forEachIndexed { i, p ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Text(p.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        nws?.discussion?.let { afd -> item { Discussion(afd) } }
        item {
            Text(
                when {
                    f.model == ForecastModel.Best -> "Forecast: Open-Meteo best match."
                    f.modelCoversHere -> "Forecast: ${f.model.label} via Open-Meteo; best match fills any hours it doesn't cover."
                    else -> "${f.model.label} doesn't cover this place, so this is Open-Meteo best match."
                } + " Alerts, text forecast and discussion: NOAA National Weather Service.",
                style = MaterialTheme.typography.labelSmall,
                color = LocalStorm.current.dim,
            )
        }
    }
    openAlert?.let { a -> AlertDialogView(a) { openAlert = null } }
}

@Composable
private fun Header(place: Place?, f: Forecast?, loading: Boolean, actions: ForecastActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = actions.onPlaces)
                .padding(vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (place?.isCurrent != false) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(place?.name ?: "Choose a place", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Change place", tint = LocalStorm.current.dim)
            }
            val sub = listOfNotNull(
                place?.region?.takeIf { it.isNotBlank() },
                f?.let { "Updated ${Fmt.ago(it.fetchedAt)}" },
            ).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelMedium, color = LocalStorm.current.dim)
        }
        IconButton(onClick = actions.onRefresh, enabled = !loading) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
        }
    }
}

@Composable
private fun Hero(f: Forecast, s: AppSettings) {
    val c = f.current
    val today = f.daily.firstOrNull()
    val sky = WeatherCode.sky(c.code)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    if (c.isDay) listOf(MaterialTheme.colorScheme.primaryContainer, LocalStorm.current.card)
                    else listOf(LocalStorm.current.cardHigh, LocalStorm.current.card),
                ),
            )
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.temp(c.temp, s), style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.weight(1f))
                WeatherIcon(sky, c.isDay, 96.dp)
            }
            Text(WeatherCode.text(c.code), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    "Feels like ${Fmt.temp(c.feelsLike, s)}",
                    today?.let { "H ${Fmt.temp(it.high, s)} · L ${Fmt.temp(it.low, s)}" },
                ).joinToString("  ·  "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            f.nowcast()?.let {
                Spacer(Modifier.height(10.dp))
                Pill(it, LocalStorm.current.cool)
            }
        }
    }
}

@Composable
private fun Details(f: Forecast, s: AppSettings) {
    val c = f.current
    val today = f.daily.firstOrNull()
    val freezing = f.upcoming().firstOrNull()?.freezingLevel
    val tiles = listOf(
        Triple("Wind", "${Geo.compass(c.windDir)} ${Fmt.wind(c.windSpeed, s)}", "Gusts ${Fmt.wind(c.windGust, s)}"),
        Triple("Humidity", "${c.humidity.roundToInt()}%", "Dew point ${Fmt.temp(c.dewPoint, s)}"),
        Triple("Pressure", Fmt.pressure(c.pressure, s), pressureTrend(f)),
        Triple("Visibility", Fmt.distance(c.visibility, f.visibilityUnit, s), "Clouds ${c.cloudCover.roundToInt()}%"),
        Triple("UV index", if (c.uv.isNaN()) "--" else "%.1f".format(c.uv), today?.let { "Max ${"%.0f".format(it.uvMax)} today" }),
        Triple("Instability", if (c.cape.isNaN()) "--" else "${c.cape.roundToInt()} J/kg", capeWords(c.cape)),
        Triple("Freezing level", Fmt.height(freezing, f.heightUnit, s), null),
        Triple(
            "Sun",
            today?.let { Fmt.clock(it.sunrise, f.offsetSec) } ?: "--",
            today?.let { "Sets ${Fmt.clock(it.sunset, f.offsetSec)}" },
        ),
        Triple("Precip today", Fmt.precip(today?.precip, s), today?.let { if (it.snowfall > 0) "Snow ${"%.1f".format(it.snowfall)}" else null }),
    )
    Column {
        SectionTitle("Right now")
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value, sub) -> Stat(label, value, Modifier.weight(1f), sub) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun capeWords(cape: Double): String? = when {
    cape.isNaN() -> null
    cape < 300 -> "Stable"
    cape < 1000 -> "Marginal"
    cape < 2500 -> "Moderate"
    cape < 4000 -> "Strong"
    else -> "Extreme"
}

private fun pressureTrend(f: Forecast): String? {
    val now = f.current.time
    val past = f.hourly.lastOrNull { it.time <= now - 3 * 3600 } ?: return null
    val d = f.current.pressure - past.pressure
    if (d.isNaN()) return null
    return when {
        d > 1.0 -> "Rising"
        d < -1.0 -> "Falling"
        else -> "Steady"
    }
}

@Composable
private fun DayRow(d: Day, f: Forecast, s: AppSettings, lo: Double, hi: Double) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (d.date == f.daily.firstOrNull()?.date) "Today" else Fmt.dayName(d.time + 43200, f.offsetSec),
                modifier = Modifier.width(52.dp),
                fontWeight = FontWeight.Medium,
            )
            WeatherIcon(WeatherCode.sky(d.code), true, 28.dp)
            Text(
                if (d.precipChance.isNaN() || d.precipChance < 5) "" else "${d.precipChance.roundToInt()}%",
                modifier = Modifier.width(44.dp).padding(start = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = LocalStorm.current.cool,
            )
            Text(Fmt.temp(d.low, s), modifier = Modifier.width(36.dp), color = LocalStorm.current.dim)
            RangeBar(d.low, d.high, lo, hi, Modifier.weight(1f))
            Text(Fmt.temp(d.high, s), modifier = Modifier.width(40.dp).padding(start = 6.dp), fontWeight = FontWeight.SemiBold)
        }
        if (open) {
            Text(
                "${WeatherCode.text(d.code)} · Precip ${Fmt.precip(d.precip, s)}" +
                    (if (d.snowfall > 0) " · Snow ${"%.1f".format(d.snowfall)}" else "") +
                    " · Wind ${Geo.compass(d.windDir)} ${Fmt.wind(d.windMax, s)}, gusts ${Fmt.wind(d.gustMax, s)}" +
                    " · UV ${"%.0f".format(d.uvMax)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun RangeBar(low: Double, high: Double, lo: Double, hi: Double, modifier: Modifier) {
    val span = (hi - lo).coerceAtLeast(1.0)
    val a = ((low - lo) / span).toFloat().coerceIn(0f, 1f)
    val b = ((high - lo) / span).toFloat().coerceIn(0f, 1f)
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Row(Modifier.fillMaxSize()) {
            if (a > 0f) Spacer(Modifier.weight(a))
            Box(
                Modifier
                    .weight((b - a).coerceAtLeast(0.02f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(LocalStorm.current.cool, LocalStorm.current.warm))),
            )
            if (b < 1f) Spacer(Modifier.weight(1f - b))
        }
    }
}

@Composable
private fun ModelPicker(current: ForecastModel, onModel: (ForecastModel) -> Unit) {
    Column {
        SectionTitle("Forecast model")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForecastModel.entries.forEach { m ->
                FilterChip(selected = m == current, onClick = { onModel(m) }, label = { Text(m.label) })
            }
        }
    }
}

@Composable
private fun Discussion(text: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        SectionTitle("Forecaster discussion (AFD)") {
            IconButton(onClick = { open = !open }) {
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand")
            }
        }
        Panel(onClick = { open = !open }) {
            val body = text.substringAfter("\n\n", text).trim()
            SelectionContainer {
                Text(
                    if (open) body else body.take(600) + if (body.length > 600) "…" else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun AlertDialogView(a: WeatherAlert, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text(a.event) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Pill(a.severity + " · " + a.urgency, alertColor(a.kind, LocalStorm.current))
                Spacer(Modifier.height(8.dp))
                if (a.expires > 0) Text("Until ${Fmt.localStamp(a.expires)}", style = MaterialTheme.typography.labelMedium, color = LocalStorm.current.dim)
                if (a.headline.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(a.headline, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(a.description, style = MaterialTheme.typography.bodyMedium)
                if (a.instruction.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("What to do", fontWeight = FontWeight.SemiBold)
                    Text(a.instruction, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text(a.sender + " · " + a.areas, style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim)
            }
        },
    )
}
