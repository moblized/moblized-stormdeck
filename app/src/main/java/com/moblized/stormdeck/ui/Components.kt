package com.moblized.stormdeck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moblized.stormdeck.data.AlertKind
import com.moblized.stormdeck.data.Legend
import com.moblized.stormdeck.data.Sky
import com.moblized.stormdeck.data.WeatherAlert

@Composable
fun WeatherIcon(sky: Sky, day: Boolean, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        drawIntoCanvas { WeatherIcons.draw(it.nativeCanvas, sky, day, this.size.minDimension) }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 16.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .background(LocalStorm.current.card)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(padding)
    Column(m, content = content)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = LocalStorm.current.dim,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(LocalStorm.current.cardHigh).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim, maxLines = 1)
    }
}

@Composable
fun LoadingRow(text: String = "Loading…") {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = LocalStorm.current.dim)
    }
}

@Composable
fun ErrorRow(message: String, onRetry: () -> Unit) {
    Panel {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Try again")
        }
    }
}

fun alertColor(kind: AlertKind, c: StormColors): Color = when (kind) {
    AlertKind.Warning -> c.warning
    AlertKind.Watch -> c.watch
    AlertKind.Advisory -> c.advisory
    AlertKind.Statement -> c.cool
}

@Composable
fun AlertChip(a: WeatherAlert, onClick: () -> Unit) {
    val color = alertColor(a.kind, LocalStorm.current)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.event, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (a.headline.isNotBlank()) {
                Text(a.headline, style = MaterialTheme.typography.labelMedium, color = LocalStorm.current.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Color ramps matching what each radar product's tiles use. */
object Ramps {
    val reflectivity = listOf(
        5 to Color(0xFF04E9E7), 10 to Color(0xFF019FF4), 15 to Color(0xFF0300F4), 20 to Color(0xFF02FD02),
        25 to Color(0xFF01C501), 30 to Color(0xFF008E00), 35 to Color(0xFFFDF802), 40 to Color(0xFFE5BC00),
        45 to Color(0xFFFD9500), 50 to Color(0xFFFD0000), 55 to Color(0xFFD40000), 60 to Color(0xFFBC0000),
        65 to Color(0xFFF800FD), 70 to Color(0xFF9854C6), 75 to Color(0xFFFDFDFD),
    )
    val velocity = listOf(
        -64 to Color(0xFF00E0FF), -50 to Color(0xFF00B4FF), -36 to Color(0xFF00FF00), -20 to Color(0xFF00A000),
        -10 to Color(0xFF326432), 0 to Color(0xFF777777), 10 to Color(0xFF643232), 20 to Color(0xFFA00000),
        36 to Color(0xFFFF0000), 50 to Color(0xFFFF8C00), 64 to Color(0xFFFFFF00),
    )
    val global = listOf(
        0 to Color(0xFF88DDEE), 1 to Color(0xFF0099CC), 2 to Color(0xFF0077AA), 3 to Color(0xFF005588),
        4 to Color(0xFFFFEE00), 5 to Color(0xFFFFAA00), 6 to Color(0xFFFF4400), 7 to Color(0xFFC10000), 8 to Color(0xFFFFAAFF),
    )
}

@Composable
fun LegendBar(legend: Legend, modifier: Modifier = Modifier) {
    val (stops, left, right) = when (legend) {
        Legend.Reflectivity -> Triple(Ramps.reflectivity.map { it.second }, "5 dBZ  light", "heavy · hail 65+")
        Legend.Velocity -> Triple(Ramps.velocity.map { it.second }, "toward radar", "away from radar")
        Legend.Global -> Triple(Ramps.global.map { it.second }, "light", "extreme")
    }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(stops)),
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(left, style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim, modifier = Modifier.weight(1f))
            Text(right, style = MaterialTheme.typography.labelSmall, color = LocalStorm.current.dim)
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
