package com.moblized.stormdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moblized.stormdeck.data.AlertLevel
import com.moblized.stormdeck.data.AppSettings
import com.moblized.stormdeck.data.NewStormScope
import com.moblized.stormdeck.data.PrecipUnit
import com.moblized.stormdeck.data.TempUnit
import com.moblized.stormdeck.data.ThemeMode
import com.moblized.stormdeck.data.WindUnit
import com.moblized.stormdeck.update.Updater

data class SettingsActions(
    val onChange: ((AppSettings) -> AppSettings) -> Unit = {},
    val onCheckUpdate: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onAllowNotifications: (() -> Unit)? = null,
    val onAllowLocation: (() -> Unit)? = null,
)

@Composable
fun SettingsContent(
    s: AppSettings,
    update: Updater.Status,
    version: String,
    alertPlace: String?,
    actions: SettingsActions,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }

        if (actions.onAllowLocation != null || actions.onAllowNotifications != null) {
            item {
                Panel {
                    Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
                    actions.onAllowLocation?.let {
                        Text("Location gives you the forecast and radar for where you are.", color = LocalStorm.current.dim)
                        OutlinedButton(onClick = it, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) { Text("Allow location") }
                    }
                    actions.onAllowNotifications?.let {
                        Text("Notifications are needed for severe weather and storm alerts.", color = LocalStorm.current.dim)
                        OutlinedButton(onClick = it, modifier = Modifier.padding(top = 4.dp)) { Text("Allow notifications") }
                    }
                }
            }
        }

        item {
            SectionTitle("Appearance")
            Panel {
                Text("Theme", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Choice(ThemeMode.entries, s.theme, { it.label }) { v -> actions.onChange { it.copy(theme = v) } }
                Text(
                    "The app icon on your home screen switches to its dark version whenever the app is dark.",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalStorm.current.dim,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        item {
            SectionTitle("Units")
            Panel {
                Text("Temperature", fontWeight = FontWeight.Medium)
                Choice(TempUnit.entries, s.temp, { it.label }) { v -> actions.onChange { it.copy(temp = v) } }
                Spacer(Modifier.height(10.dp))
                Text("Wind", fontWeight = FontWeight.Medium)
                Choice(WindUnit.entries, s.wind, { it.label }) { v -> actions.onChange { it.copy(wind = v) } }
                Spacer(Modifier.height(10.dp))
                Text("Rain and snow", fontWeight = FontWeight.Medium)
                Choice(PrecipUnit.entries, s.precip, { it.label }) { v -> actions.onChange { it.copy(precip = v) } }
            }
        }

        item {
            SectionTitle("Severe weather alerts")
            Panel {
                Text(
                    "From the National Weather Service for ${alertPlace ?: "your place"}, checked every 15 minutes (US only).",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalStorm.current.dim,
                )
                Spacer(Modifier.height(6.dp))
                AlertLevel.entries.forEach { lvl ->
                    RadioRow(lvl.label, lvl == s.alerts) { actions.onChange { it.copy(alerts = lvl) } }
                }
            }
        }

        item {
            SectionTitle("Hurricane & tropical storm alerts")
            Panel {
                Text("When a new storm forms", fontWeight = FontWeight.Medium)
                NewStormScope.entries.forEach { sc ->
                    RadioRow(sc.label, sc == s.newStorms) { actions.onChange { it.copy(newStorms = sc) } }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    "When a storm heads your way",
                    "Its forecast cone covers you, or its track passes within ${s.stormNearbyMiles} miles",
                    s.stormNearby,
                ) { on -> actions.onChange { it.copy(stormNearby = on) } }
                if (s.stormNearby) {
                    Choice(listOf(200, 350, 500, 800), s.stormNearbyMiles, { "$it mi" }) { v -> actions.onChange { it.copy(stormNearbyMiles = v) } }
                }
            }
        }

        item {
            SectionTitle("App updates")
            Panel {
                Text("StormDeck $version", fontWeight = FontWeight.Medium)
                Text(
                    when (update) {
                        Updater.Status.Idle -> "Checks GitHub for new versions twice a day."
                        Updater.Status.Checking -> "Checking…"
                        Updater.Status.UpToDate -> "You have the latest version."
                        is Updater.Status.Available -> "Version ${update.release.versionName} is available; it downloads on Wi-Fi."
                        is Updater.Status.Downloading -> "Downloading ${update.release.versionName}… ${update.percent}%"
                        is Updater.Status.Ready -> update.note ?: "Version ${update.release.versionName} is ready to install."
                        Updater.Status.Installing -> "Installing…"
                        is Updater.Status.Failed -> update.message
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalStorm.current.dim,
                )
                if (update is Updater.Status.Downloading) {
                    LinearProgressIndicator(progress = { update.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (update is Updater.Status.Ready) {
                        Button(onClick = actions.onInstall) { Text("Install now") }
                    } else {
                        OutlinedButton(onClick = actions.onCheckUpdate) { Text("Check now") }
                    }
                }
                ToggleRow("Check automatically", null, s.autoUpdate) { on -> actions.onChange { it.copy(autoUpdate = on) } }
                ToggleRow("Download on Wi-Fi only", null, s.wifiOnly) { on -> actions.onChange { it.copy(wifiOnly = on) } }
                ToggleRow("Install automatically", "Shortly after you leave the app", s.autoInstall) { on -> actions.onChange { it.copy(autoInstall = on) } }
            }
        }

        item {
            SectionTitle("Data sources")
            Panel {
                listOf(
                    "Forecasts: Open-Meteo (HRRR, GFS, ECMWF, ICON, GEM and more)",
                    "US radar, alerts, text forecasts: NOAA National Weather Service; NEXRAD tiles via Iowa Environmental Mesonet",
                    "Global radar: RainViewer (personal use)",
                    "Satellite and sea temperatures: NASA GIBS (GOES, Himawari, GHRSST MUR)",
                    "Hurricanes: NOAA National Hurricane Center and GDACS (JTWC and regional centers)",
                    "El Niño: NOAA Climate Prediction Center",
                    "Base map: OpenFreeMap / OpenStreetMap contributors; car map: CARTO",
                ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onPick(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(o), maxLines = 1) }
        }
    }
}

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Text(text)
    }
}
