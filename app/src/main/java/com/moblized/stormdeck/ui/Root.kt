package com.moblized.stormdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moblized.stormdeck.BuildConfig
import com.moblized.stormdeck.Graph
import com.moblized.stormdeck.data.Place
import com.moblized.stormdeck.update.Updater
import com.moblized.stormdeck.update.UpdateWorker
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private fun Tab.icon(): ImageVector = when (this) {
    Tab.Radar -> Icons.Filled.Radar
    Tab.Forecast -> Icons.Filled.Cloud
    Tab.Tropics -> AppIcons.Hurricane
    Tab.Enso -> Icons.Filled.Waves
    Tab.Settings -> Icons.Filled.Settings
}

data class Permissions(
    val needLocation: Boolean,
    val needNotifications: Boolean,
    val askLocation: () -> Unit,
    val askNotifications: () -> Unit,
)

@Composable
fun Root(vm: AppViewModel, dark: Boolean, perms: Permissions) {
    val settings by Graph.settings.settings.collectAsState()
    val update by Updater.status.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var placesOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = vm.tab == t,
                        onClick = { vm.tab = t },
                        icon = {
                            if (t == Tab.Settings && update is Updater.Status.Ready) {
                                BadgedBox(badge = { Badge() }) { Icon(t.icon(), contentDescription = null) }
                            } else if (t == Tab.Forecast && vm.alerts.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text("${vm.alerts.size}") } }) { Icon(t.icon(), contentDescription = null) }
                            } else {
                                Icon(t.icon(), contentDescription = null)
                            }
                        },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { inner ->
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val pad = PaddingValues(top = top, bottom = inner.calculateBottomPadding())
        Box(Modifier.fillMaxSize()) {
            // The map stays alive under the other tabs, so coming back to it is instant.
            RadarScreen(vm, dark, visible = vm.tab == Tab.Radar, contentPadding = pad, onPlaces = { placesOpen = true })
            if (vm.tab != Tab.Radar) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when (vm.tab) {
                        Tab.Forecast -> ForecastContent(
                            place = vm.place,
                            forecast = vm.forecast,
                            alerts = vm.alerts,
                            nws = vm.nws,
                            s = settings,
                            actions = ForecastActions(
                                onPlaces = { placesOpen = true },
                                onRefresh = { vm.refreshForecast() },
                                onModel = { m -> Graph.settings.update { it.copy(model = m) } },
                            ),
                            contentPadding = pad,
                        )
                        Tab.Tropics -> TropicsContent(
                            storms = vm.storms,
                            tracks = vm.tracks,
                            outlooks = vm.outlooks,
                            me = vm.place?.point,
                            s = settings,
                            selected = vm.selectedStorm,
                            actions = TropicsActions(onRefresh = { vm.loadTropics() }, onShowOnMap = { vm.showStormOnMap(it) }),
                            contentPadding = pad,
                        )
                        Tab.Enso -> EnsoContent(
                            enso = vm.enso,
                            actions = EnsoActions(
                                onRefresh = { vm.loadEnso() },
                                onShowOnMap = {
                                    vm.toggleSst(true)
                                    vm.tab = Tab.Radar
                                },
                            ),
                            contentPadding = pad,
                        )
                        Tab.Settings -> SettingsContent(
                            s = settings,
                            update = update,
                            version = BuildConfig.VERSION_NAME,
                            alertPlace = vm.place?.name,
                            actions = SettingsActions(
                                onChange = { change -> Graph.settings.update(change) },
                                onCheckUpdate = {
                                    scope.launch {
                                        runCatching {
                                            val mayDownload = !(settings.wifiOnly && Updater.onMeteredNetwork(context))
                                            Updater.check(context, download = mayDownload)
                                        }
                                    }
                                },
                                onInstall = { Updater.install(context) },
                                onAllowNotifications = if (perms.needNotifications) perms.askNotifications else null,
                                onAllowLocation = if (perms.needLocation) perms.askLocation else null,
                            ),
                            contentPadding = pad,
                        )
                        Tab.Radar -> Unit
                    }
                }
            }
        }
    }

    if (placesOpen) PlacesSheet(vm, perms) { placesOpen = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacesSheet(vm: AppViewModel, perms: Permissions, onClose: () -> Unit) {
    val saved by Graph.places.saved.collectAsState()
    val selected by Graph.places.selectedId.collectAsState()
    val current by Graph.places.current.collectAsState()
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Places", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.search(it)
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search a city or town") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = { if (vm.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
            )
            Spacer(Modifier.height(8.dp))
            if (query.length >= 2) {
                vm.searchResults.forEach { p ->
                    PlaceRow(p.name, p.region, Icons.Filled.Place, false, onClick = {
                        vm.choose(p)
                        onClose()
                    })
                }
                if (!vm.searching && vm.searchResults.isEmpty()) Text("No matches", color = LocalStorm.current.dim, modifier = Modifier.padding(8.dp))
            } else {
                PlaceRow(
                    current?.name?.let { "Current location · $it" } ?: "Current location",
                    if (perms.needLocation) "Tap to allow location" else current?.region.orEmpty(),
                    Icons.Filled.MyLocation,
                    selected == Place.CURRENT,
                    onClick = {
                        if (perms.needLocation) perms.askLocation()
                        vm.choose(Place(Place.CURRENT, "", "", 0.0, 0.0))
                        vm.locate()
                        onClose()
                    },
                )
                saved.forEach { p ->
                    PlaceRow(p.name, p.region, Icons.Filled.Place, selected == p.id, onClick = {
                        vm.choose(p)
                        onClose()
                    }, onDelete = { vm.removePlace(p) })
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(title: String, sub: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelMedium, color = LocalStorm.current.dim)
        }
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
    }
}
