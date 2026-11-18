package com.moblized.stormdeck.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.moblized.stormdeck.Graph
import com.moblized.stormdeck.MainActivity
import com.moblized.stormdeck.data.AppSettings
import com.moblized.stormdeck.data.Fmt
import com.moblized.stormdeck.data.Forecast
import com.moblized.stormdeck.data.Place
import com.moblized.stormdeck.data.WeatherCode
import com.moblized.stormdeck.data.WeatherRepo
import com.moblized.stormdeck.data.isDark
import com.moblized.stormdeck.ui.WeatherIcons
import kotlin.math.roundToInt

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()
}

/** Current conditions, the next few hours and (when there's room) the next days. */
class WeatherWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(110.dp, 50.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 220.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Graph.init(context)
        val s = Graph.settings.value
        val place = Graph.places.selected()
        val f = place?.let { WeatherRepo.cached(it, s) }
        val dark = s.isDark(context)
        provideContent { WidgetBody(place, f, s, dark) }
    }
}

private data class Palette(val bg: Color, val text: Color, val dim: Color, val accent: Color)

@Composable
private fun WidgetBody(place: Place?, f: Forecast?, s: AppSettings, dark: Boolean) {
    val pal = if (dark) {
        Palette(Color(0xF00B1220), Color(0xFFF1F5F9), Color(0xFF94A3B8), Color(0xFF38BDF8))
    } else {
        Palette(Color(0xF5FFFFFF), Color(0xFF0F172A), Color(0xFF475569), Color(0xFF0369A1))
    }
    val size = LocalSize.current
    val root = GlanceModifier.fillMaxSize().background(pal.bg).cornerRadius(20.dp).padding(12.dp)
        .clickable(actionStartActivity<MainActivity>())
    if (place == null || f == null) {
        Column(modifier = root, verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("StormDeck", style = TextStyle(color = ColorProvider(pal.text), fontWeight = FontWeight.Bold, fontSize = 16.sp))
            Text("Open the app to load your forecast", style = TextStyle(color = ColorProvider(pal.dim), fontSize = 12.sp))
        }
        return
    }
    val c = f.current
    Column(modifier = root) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(WeatherIcons.bitmap(WeatherCode.sky(c.code), c.isDay, 144)),
                contentDescription = WeatherCode.text(c.code),
                modifier = GlanceModifier.size(if (size.height < 100.dp) 40.dp else 52.dp),
            )
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Text(Fmt.temp(c.temp, s), style = TextStyle(color = ColorProvider(pal.text), fontWeight = FontWeight.Bold, fontSize = 30.sp))
                Text(place.name, style = TextStyle(color = ColorProvider(pal.dim), fontSize = 12.sp), maxLines = 1)
            }
            if (size.width >= 200.dp) {
                Spacer(GlanceModifier.defaultWeight())
                Column(horizontalAlignment = Alignment.End) {
                    Text(WeatherCode.text(c.code), style = TextStyle(color = ColorProvider(pal.text), fontSize = 13.sp), maxLines = 1)
                    f.daily.firstOrNull()?.let { d ->
                        Text("H ${Fmt.temp(d.high, s)}  L ${Fmt.temp(d.low, s)}", style = TextStyle(color = ColorProvider(pal.dim), fontSize = 12.sp))
                    }
                    Text("Feels ${Fmt.temp(c.feelsLike, s)}", style = TextStyle(color = ColorProvider(pal.dim), fontSize = 12.sp))
                }
            }
        }
        if (size.height >= 100.dp) {
            Spacer(GlanceModifier.height(8.dp))
            val hours = f.upcoming().take(if (size.width >= 300.dp) 6 else 5)
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                hours.forEach { h ->
                    Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Fmt.hour(h.time, f.offsetSec), style = TextStyle(color = ColorProvider(pal.dim), fontSize = 11.sp))
                        Image(
                            provider = ImageProvider(WeatherIcons.bitmap(WeatherCode.sky(h.code), h.isDay, 72)),
                            contentDescription = null,
                            modifier = GlanceModifier.size(24.dp),
                        )
                        Text(Fmt.temp(h.temp, s), style = TextStyle(color = ColorProvider(pal.text), fontSize = 13.sp, fontWeight = FontWeight.Medium))
                        Text(
                            if (h.precipChance.isNaN()) " " else "${h.precipChance.roundToInt()}%",
                            style = TextStyle(color = ColorProvider(pal.accent), fontSize = 10.sp),
                        )
                    }
                }
            }
        }
        if (size.height >= 200.dp) {
            Spacer(GlanceModifier.height(6.dp))
            f.daily.take(4).forEach { d ->
                Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Fmt.dayName(d.time + 12 * 3600, f.offsetSec),
                        style = TextStyle(color = ColorProvider(pal.text), fontSize = 13.sp),
                        modifier = GlanceModifier.width(44.dp),
                    )
                    Image(
                        provider = ImageProvider(WeatherIcons.bitmap(WeatherCode.sky(d.code), true, 72)),
                        contentDescription = null,
                        modifier = GlanceModifier.size(22.dp),
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        if (d.precipChance.isNaN()) "" else "${d.precipChance.roundToInt()}%",
                        style = TextStyle(color = ColorProvider(pal.accent), fontSize = 12.sp),
                        modifier = GlanceModifier.width(40.dp),
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    Text("${Fmt.temp(d.high, s)} / ${Fmt.temp(d.low, s)}", style = TextStyle(color = ColorProvider(pal.text), fontSize = 13.sp))
                }
            }
        }
    }
}
