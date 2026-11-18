package com.moblized.stormdeck.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moblized.stormdeck.data.AppSettings
import com.moblized.stormdeck.data.Fmt
import com.moblized.stormdeck.data.Geo
import com.moblized.stormdeck.data.LatLon
import com.moblized.stormdeck.data.Outlook
import com.moblized.stormdeck.data.Storm
import com.moblized.stormdeck.data.StormCategory
import com.moblized.stormdeck.data.StormTrack

data class TropicsActions(
    val onRefresh: () -> Unit = {},
    val onShowOnMap: (Storm) -> Unit = {},
)

@Composable
fun TropicsContent(
    storms: Remote<List<Storm>>,
    tracks: Map<String, StormTrack>,
    outlooks: List<Outlook>,
    me: LatLon?,
    s: AppSettings,
    selected: String?,
    actions: TropicsActions,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val list = storms.value.orEmpty()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tropics", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        when {
                            storms.value == null && storms.loading -> "Checking every ocean basin…"
                            list.isEmpty() -> "No active tropical cyclones worldwide"
                            list.size == 1 -> "1 active tropical cyclone"
                            else -> "${list.size} active tropical cyclones"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalStorm.current.dim,
                    )
                }
                IconButton(onClick = actions.onRefresh, enabled = !storms.loading) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            }
        }
        if (storms.error != null && storms.value == null) item { ErrorRow(storms.error, actions.onRefresh) }
        if (storms.value == null && storms.loading) item { LoadingRow("Loading storms…") }
        items(list, key = { it.key }) { st -> StormCard(st, tracks[st.key], me, s, st.key == selected, actions) }
        if (list.isEmpty() && storms.value != null) {
            item {
                Panel {
                    Text("All quiet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "When a tropical depression, storm, hurricane, typhoon or cyclone forms anywhere, it shows up here with its track and forecast cone.",
                        color = LocalStorm.current.dim,
                    )
                }
            }
        }
        item { CategoryKey() }
        outlooks.forEach { o -> item(key = "outlook-" + o.basin) { OutlookCard(o) } }
        item {
            Text(
                "Data: NOAA National Hurricane Center (Atlantic, East & Central Pacific) and GDACS (all basins, from JTWC and regional centers). " +
                    "Always follow your local officials' instructions.",
                style = MaterialTheme.typography.labelSmall,
                color = LocalStorm.current.dim,
            )
        }
    }
}

@Composable
private fun StormCard(st: Storm, track: StormTrack?, me: LatLon?, s: AppSettings, highlighted: Boolean, actions: TropicsActions) {
    val context = LocalContext.current
    val color = Color(st.category.color)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (st.category) {
                        StormCategory.TD -> "TD"
                        StormCategory.TS -> "TS"
                        else -> st.category.short.removePrefix("Cat ")
                    },
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${st.type} ${st.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${st.basin.label} · ${st.source}",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalStorm.current.dim,
                )
            }
            st.alertLevel?.let { lvl ->
                val c = when (lvl.lowercase()) {
                    "red" -> LocalStorm.current.warning
                    "orange" -> LocalStorm.current.warm
                    else -> LocalStorm.current.good
                }
                Pill(lvl, c)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Winds", Fmt.knots(st.windKt, s), Modifier.weight(1f), "sustained")
            Stat("Pressure", st.pressureMb?.let { "$it mb" } ?: "--", Modifier.weight(1f))
            Stat("Moving", st.movement ?: "--", Modifier.weight(1.3f))
        }
        Spacer(Modifier.height(8.dp))
        val position = "%.1f°%s %.1f°%s".format(
            kotlin.math.abs(st.lat), if (st.lat >= 0) "N" else "S", kotlin.math.abs(st.lon), if (st.lon >= 0) "E" else "W",
        )
        Text(
            listOfNotNull(
                position,
                me?.let { "${Fmt.km(Geo.distanceKm(it, st.point), s)} from you" },
                st.countries.takeIf { it.isNotBlank() }?.let { "Near $it" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (me != null && track != null && !track.isEmpty) {
            val km = track.closestKm(me, st.point)
            val text = when {
                km == 0.0 -> "You are inside the forecast cone."
                km < 800 -> "Forecast track comes within ${Fmt.km(km, s)} of you."
                else -> null
            }
            if (text != null) {
                Spacer(Modifier.height(8.dp))
                Pill(text, if (km == 0.0) LocalStorm.current.warning else LocalStorm.current.watch)
            }
        }
        if (track != null) {
            val upcoming = track.points.filter { it.forecast && it.timeMillis > 0 }
            if (upcoming.isNotEmpty() && highlighted) {
                Spacer(Modifier.height(8.dp))
                upcoming.take(8).forEach { p ->
                    Text(
                        "${Fmt.localStamp(p.timeMillis)} · ${p.label} · %.1f, %.1f".format(p.lat, p.lon),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalStorm.current.dim,
                    )
                }
            }
        }
        if (st.updated > 0) {
            Text("Updated ${Fmt.ago(st.updated)}", style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { actions.onShowOnMap(st) }) { Text("Track on map") }
            st.links.firstOrNull()?.let { (label, url) ->
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text(label) }
            }
        }
    }
}

@Composable
private fun CategoryKey() {
    Column {
        SectionTitle("Saffir–Simpson scale")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StormCategory.entries.forEach { c ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(c.color)))
                    Text(c.short, style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim)
                }
            }
        }
    }
}

@Composable
private fun OutlookCard(o: Outlook) {
    var open by remember { mutableStateOf(false) }
    Column {
        SectionTitle("${o.basin} outlook (NHC)") {
            IconButton(onClick = { open = !open }) {
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
        }
        Panel(onClick = { open = !open }) {
            val body = o.text.lines().dropWhile { it.isNotBlank() }.joinToString("\n").trim().ifBlank { o.text }
            Text(
                if (open) body else body.take(420) + if (body.length > 420) "…" else "",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
