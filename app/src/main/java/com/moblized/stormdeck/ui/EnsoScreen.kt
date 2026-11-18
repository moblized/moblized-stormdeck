package com.moblized.stormdeck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.moblized.stormdeck.data.Enso
import com.moblized.stormdeck.data.EnsoPhase
import com.moblized.stormdeck.data.Fmt
import com.moblized.stormdeck.data.Gibs

data class EnsoActions(val onRefresh: () -> Unit = {}, val onShowOnMap: () -> Unit = {})

@Composable
fun EnsoContent(enso: Remote<Enso>, actions: EnsoActions, contentPadding: PaddingValues = PaddingValues(), showImages: Boolean = true) {
    val e = enso.value
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
                    Text("El Niño / La Niña", style = MaterialTheme.typography.headlineMedium)
                    Text("The tropical Pacific's warm and cool swings (ENSO)", style = MaterialTheme.typography.labelMedium, color = LocalStorm.current.dim)
                }
                IconButton(onClick = actions.onRefresh, enabled = !enso.loading) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            }
        }
        if (e == null) {
            item { if (enso.error != null) ErrorRow(enso.error, actions.onRefresh) else LoadingRow("Loading from NOAA CPC…") }
            return@LazyColumn
        }
        item { StatusCard(e) }
        e.latestWeek?.let { w ->
            item {
                SectionTitle("This week · ${w.date}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Niño 3.4", Fmt.signed(w.nino34) + "°C", Modifier.weight(1f), "key region")
                    Stat("Niño 1+2", Fmt.signed(w.nino12) + "°C", Modifier.weight(1f), "off Peru")
                    Stat("Niño 3", Fmt.signed(w.nino3) + "°C", Modifier.weight(1f))
                    Stat("Niño 4", Fmt.signed(w.nino4) + "°C", Modifier.weight(1f), "central")
                }
            }
        }
        item {
            SectionTitle("Sea temperature vs normal")
            Panel(padding = 10.dp) {
                if (showImages) SstMap()
                Spacer(Modifier.height(6.dp))
                SstLegend()
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tropical Pacific, 120°E to 80°W. Dashed box: Niño 3.4 (5°N–5°S, 170°W–120°W), the region NOAA uses to call El Niño and La Niña.",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalStorm.current.dim,
                )
                OutlinedButton(onClick = actions.onShowOnMap, modifier = Modifier.padding(top = 6.dp)) { Text("Open on the map") }
            }
        }
        if (e.weekly.isNotEmpty()) {
            item {
                SectionTitle("Weekly Niño anomalies · past year")
                Panel(padding = 12.dp) {
                    WeeklyChart(e.weekly.takeLast(56))
                    Text(
                        "Bold: Niño 3.4 · blue: Niño 4 · grey: Niño 1+2. Shaded: El Niño (above +0.5 °C) and La Niña (below −0.5 °C) ranges.",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalStorm.current.dim,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        if (e.oni.isNotEmpty()) {
            item {
                val last = e.latestOni!!
                SectionTitle("Oceanic Niño Index · ${last.label} ${Fmt.signed(last.anomaly)}°C")
                Panel(padding = 12.dp) {
                    OniChart(e.oni.takeLast(60))
                    Text(
                        "Three-month averages of Niño 3.4. Five overlapping seasons in a row at ±0.5 °C make an official El Niño or La Niña episode.",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalStorm.current.dim,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item { PastEvents(e) }
        }
        item { Impacts(e) }
        item {
            Text(
                "Data: NOAA Climate Prediction Center (ENSO discussion, ONI, weekly Niño indices) and NASA GHRSST MUR sea-surface temperatures via GIBS.",
                style = MaterialTheme.typography.labelSmall,
                color = LocalStorm.current.dim,
            )
        }
    }
}

@Composable
private fun StatusCard(e: Enso) {
    val week = e.latestWeek?.nino34 ?: e.latestOni?.anomaly ?: 0.0
    val phase = Enso.phase(week)
    val color = when (phase) {
        EnsoPhase.ElNino -> LocalStorm.current.warning
        EnsoPhase.LaNina -> LocalStorm.current.cool
        EnsoPhase.Neutral -> LocalStorm.current.good
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.28f), LocalStorm.current.card)))
            .padding(20.dp),
    ) {
        Column {
            e.status?.let { Pill(it.alert, color) }
            Spacer(Modifier.height(10.dp))
            Text(
                if (phase == EnsoPhase.Neutral) "ENSO-neutral" else "${Enso.strength(week)} ${phase.label}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("Niño 3.4 is ${Fmt.signed(week)} °C from normal", color = MaterialTheme.colorScheme.onSurfaceVariant)
            e.status?.synopsis?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            e.status?.issued?.takeIf { it.isNotBlank() }?.let {
                Text("NOAA CPC discussion, $it", style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Two GIBS pictures side by side, 120°E→180° and 180°→80°W, with the Niño 3.4 box drawn over them. */
@Composable
private fun SstMap() {
    val date = Gibs.sstDate()
    Box(Modifier.fillMaxWidth().aspectRatio(160f / 60f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B1220))) {
        Row(Modifier.fillMaxSize()) {
            AsyncImage(
                model = Gibs.sstImage(120.0, -30.0, 180.0, 30.0, 360, 360, date),
                contentDescription = "Sea temperature anomalies, western Pacific",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.weight(60f).fillMaxHeight(),
            )
            AsyncImage(
                model = Gibs.sstImage(-180.0, -30.0, -80.0, 30.0, 600, 360, date),
                contentDescription = "Sea temperature anomalies, eastern Pacific",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.weight(100f).fillMaxHeight(),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            // 120°E..280°E across the width, 30°N..30°S down the height.
            fun x(lonE: Double) = ((lonE - 120.0) / 160.0 * size.width).toFloat()
            fun y(lat: Double) = ((30.0 - lat) / 60.0 * size.height).toFloat()
            val left = x(190.0)
            val right = x(240.0)
            drawRect(
                Color(0xFFFBBF24),
                topLeft = Offset(left, y(5.0)),
                size = Size(right - left, y(-5.0) - y(5.0)),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
            )
            drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1f)
        }
    }
}

@Composable
private fun SstLegend() {
    Column {
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF3B0F70), Color(0xFF2B6CB0), Color(0xFF90CDF4), Color(0xFFF7FAFC), Color(0xFFFBD38D), Color(0xFFE53E3E), Color(0xFF7B1D1D)),
                ),
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text("colder", style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim, modifier = Modifier.weight(1f))
            Text("normal", style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim, modifier = Modifier.weight(1f))
            Text("warmer", style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim)
        }
    }
}

/** The strongest past episodes in the record, for scale. */
@Composable
private fun PastEvents(e: Enso) {
    val peaksWarm = e.oni.groupBy { if (it.season == "NDJ" || it.season == "DJF") it.year - (if (it.season == "DJF") 1 else 0) else -1 }
        .filterKeys { it > 0 }
        .mapValues { (_, v) -> v.maxOf { it.anomaly } }
        .entries.sortedByDescending { it.value }.take(4)
    val peaksCold = e.oni.groupBy { if (it.season == "NDJ" || it.season == "DJF") it.year - (if (it.season == "DJF") 1 else 0) else -1 }
        .filterKeys { it > 0 }
        .mapValues { (_, v) -> v.minOf { it.anomaly } }
        .entries.sortedBy { it.value }.take(4)
    if (peaksWarm.isEmpty()) return
    Column {
        SectionTitle("Strongest winters on record (since 1950)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Panel(Modifier.weight(1f), padding = 12.dp) {
                Text("El Niño", color = LocalStorm.current.warning, fontWeight = FontWeight.SemiBold)
                peaksWarm.forEach { (y, v) -> Text("$y–${"%02d".format((y + 1) % 100)}  ${Fmt.signed(v)}", style = MaterialTheme.typography.bodyMedium) }
            }
            Panel(Modifier.weight(1f), padding = 12.dp) {
                Text("La Niña", color = LocalStorm.current.cool, fontWeight = FontWeight.SemiBold)
                peaksCold.forEach { (y, v) -> Text("$y–${"%02d".format((y + 1) % 100)}  ${Fmt.signed(v)}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun Impacts(e: Enso) {
    val phase = Enso.phase(e.latestWeek?.nino34 ?: e.latestOni?.anomaly ?: 0.0)
    Column {
        SectionTitle("What it usually means")
        Panel {
            val lines = when (phase) {
                EnsoPhase.ElNino -> listOf(
                    "US winter: wetter and stormier across the southern tier (California to Florida), milder and often drier across the northern states.",
                    "Hurricanes: stronger wind shear tends to suppress Atlantic hurricanes; the eastern and central Pacific tend to be busier.",
                    "Elsewhere: drier in Indonesia, Australia and southern Africa; wetter in Peru and Ecuador.",
                    "Global temperatures tend to run warmer during and just after a strong El Niño.",
                )
                EnsoPhase.LaNina -> listOf(
                    "US winter: drier and warmer across the southern tier, colder and snowier in the northern Plains and Northwest.",
                    "Hurricanes: weaker shear tends to favor a busier Atlantic hurricane season.",
                    "Elsewhere: wetter in Indonesia and Australia; drought risk in East Africa and parts of South America.",
                )
                EnsoPhase.Neutral -> listOf(
                    "Neither El Niño nor La Niña is steering the pattern, so other influences (the jet stream, the Arctic, the Madden–Julian Oscillation) matter more.",
                    "Seasonal outlooks are less certain in neutral years.",
                )
            }
            lines.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp)) }
            Text("These are typical tendencies, not guarantees for any one place or season.", style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim)
        }
    }
}
