package com.moblized.stormdeck.ui

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moblized.stormdeck.Graph
import com.moblized.stormdeck.data.Fmt
import com.moblized.stormdeck.data.Gibs
import com.moblized.stormdeck.data.RadarSource
import com.moblized.stormdeck.data.SatelliteMode
import kotlinx.coroutines.delay
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
private fun rememberMapView(): MapView {
    val context = LocalContext.current
    val view = remember {
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, view) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }
    return view
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(vm: AppViewModel, dark: Boolean, visible: Boolean, contentPadding: PaddingValues, onPlaces: () -> Unit) {
    val mapView = rememberMapView()
    val settings by Graph.settings.settings.collectAsState()
    var controller by remember { mutableStateOf<MapController?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    var showLayers by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val topPx = with(density) { (contentPadding.calculateTopPadding() + 64.dp).roundToPx() }
    val sidePx = with(density) { 12.dp.roundToPx() }

    // (Re)load the base map whenever light/dark changes; the weather layers are re-added on top.
    LaunchedEffect(dark) {
        controller?.dispose()
        controller = null
        mapView.getMapAsync { map ->
            map.uiSettings.setAttributionGravity(Gravity.TOP or Gravity.END)
            map.uiSettings.setAttributionMargins(0, topPx, sidePx, 0)
            map.uiSettings.setLogoGravity(Gravity.TOP or Gravity.START)
            map.uiSettings.setLogoMargins(sidePx, topPx, 0, 0)
            map.uiSettings.setCompassGravity(Gravity.TOP or Gravity.END)
            map.uiSettings.setCompassMargins(0, topPx + sidePx * 3, sidePx, 0)
            val first = map.cameraPosition.zoom < 1.0
            map.setStyle(Style.Builder().fromUri(MapController.styleUrl(dark))) { style ->
                val c = MapController(
                    map, style, dark,
                    onSite = { id -> vm.sites.firstOrNull { it.id == id }?.let(vm::selectSite) },
                    onStorm = { key -> vm.selectedStorm = key },
                )
                if (first) {
                    val p = vm.place?.point
                    if (p != null) c.fly(p, 6.5, animate = false) else c.fly(com.moblized.stormdeck.data.LatLon(38.5, -96.0), 3.2, animate = false)
                }
                controller = c
            }
        }
    }

    val frames = vm.frames.value
    LaunchedEffect(controller, frames?.key, settings.radarOpacity) {
        controller?.setFrames(frames, settings.radarOpacity)
        frame = (frames?.frames?.size ?: 1) - 1
    }
    // The satellite picture moves on every 10 minutes.
    LaunchedEffect(controller, vm.satellite) {
        while (true) {
            controller?.setSatellite(vm.satellite, Gibs.latestGeoTime())
            delay(5 * 60_000L)
        }
    }
    LaunchedEffect(controller, vm.showWarnings) {
        while (true) {
            controller?.setWarnings(vm.showWarnings)
            delay(5 * 60_000L)
        }
    }
    LaunchedEffect(controller, vm.showSst) { controller?.setSst(vm.showSst, Gibs.sstDate()) }
    LaunchedEffect(controller, vm.storms.value, vm.tracks, vm.showStorms) {
        controller?.setStorms(vm.storms.value.orEmpty(), vm.tracks, vm.showStorms)
    }
    LaunchedEffect(controller, vm.radarSource, vm.sites, vm.site) {
        controller?.setStations(vm.sites, vm.site?.id, vm.radarSource == RadarSource.Station)
    }
    LaunchedEffect(controller, vm.place) { controller?.setMe(vm.place?.point) }
    LaunchedEffect(controller, vm.focus) { vm.focus?.let { f -> controller?.fly(f.point, f.zoom) } }
    LaunchedEffect(Unit) { vm.keepFramesFresh() }

    // The animation.
    val count = frames?.frames?.size ?: 0
    LaunchedEffect(controller, frames?.key, vm.playing, visible, settings.frameMillis) {
        val c = controller ?: return@LaunchedEffect
        if (count == 0) return@LaunchedEffect
        if (!vm.playing || !visible) {
            c.show(frame.coerceIn(0, count - 1))
            return@LaunchedEffect
        }
        while (true) {
            c.show(frame)
            delay(if (frame == count - 1) 1500L else settings.frameMillis.toLong())
            frame = (frame + 1) % count
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Top bar: place and layer buttons.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = contentPadding.calculateTopPadding() + 8.dp, start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
                    .clickable(onClick = onPlaces)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                val f = vm.forecast.value
                Text(
                    (vm.place?.name ?: "Choose a place") + (f?.let { "  ${Fmt.temp(it.current.temp, settings)}" } ?: ""),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            RoundButton(onClick = {
                vm.place?.let { vm.focusOn(it.point, 7.0) }
                vm.locate()
            }) { Icon(Icons.Filled.MyLocation, contentDescription = "My location") }
            Spacer(Modifier.width(8.dp))
            RoundButton(onClick = { showLayers = true }) { Icon(Icons.Filled.Layers, contentDescription = "Layers") }
        }

        // Bottom: storm card and the timeline.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = contentPadding.calculateBottomPadding() + 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val st = vm.storms.value?.firstOrNull { it.key == vm.selectedStorm }
            if (st != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f))
                        .clickable { vm.tab = Tab.Tropics }
                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill(st.category.short, androidx.compose.ui.graphics.Color(st.category.color))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${st.type} ${st.name}", fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(Fmt.knots(st.windKt, settings), st.pressureMb?.let { "$it mb" }, st.movement).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalStorm.current.dim,
                        )
                    }
                    IconButton(onClick = { vm.selectedStorm = null }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
            }
            if (vm.radarSource == RadarSource.Station && vm.products.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.products.forEach { p ->
                        FilterChip(
                            selected = p.id == vm.product.id,
                            onClick = { vm.selectProduct(p) },
                            label = { Text(productShort(p.id, p.name)) },
                        )
                    }
                }
            }
            Timeline(vm, frame, count, onSeek = { frame = it; controller?.show(it) })
        }
    }

    if (showLayers) {
        ModalBottomSheet(onDismissRequest = { showLayers = false }) { LayersSheet(vm) }
    }
}

private fun productShort(id: String, name: String): String = when (id) {
    "N0B", "N0Q" -> "Reflectivity"
    "N0S" -> "Storm-relative velocity"
    "N0U" -> "Velocity"
    "N0C" -> "Correlation coef."
    "N0X" -> "Diff. reflectivity"
    "N0H" -> "Hydrometeors"
    "EET", "NET" -> "Echo tops"
    "DAA" -> "1-hr rain"
    "DTA" -> "Storm total rain"
    else -> name
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Timeline(vm: AppViewModel, frame: Int, count: Int, onSeek: (Int) -> Unit) {
    val fs = vm.frames.value
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (vm.frames.loading && fs == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.playing = !vm.playing }, enabled = count > 1) {
                Icon(if (vm.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (vm.playing) "Pause" else "Play")
            }
            Column(Modifier.weight(1f)) {
                val f = fs?.frames?.getOrNull(frame)
                Text(
                    when {
                        vm.radarSource == RadarSource.Off -> "Radar off"
                        f != null -> "${Fmt.localClock(f.timeMillis)}  ·  ${Fmt.ago(f.timeMillis)}"
                        vm.frames.error != null -> vm.frames.error!!
                        else -> "Loading radar…"
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    fs?.let { it.title + " · " + it.attribution } ?: vm.radarSource.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalStorm.current.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (count > 1) {
            Slider(
                value = frame.toFloat(),
                onValueChange = {
                    vm.playing = false
                    onSeek(it.toInt().coerceIn(0, count - 1))
                },
                valueRange = 0f..(count - 1).toFloat(),
                steps = (count - 2).coerceAtLeast(0),
                modifier = Modifier.height(28.dp),
            )
        }
        if (fs != null) LegendBar(fs.legend, Modifier.padding(top = 2.dp, bottom = 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayersSheet(vm: AppViewModel) {
    val settings by Graph.settings.settings.collectAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text("Map layers", style = MaterialTheme.typography.titleLarge)
        SectionTitle("Radar")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = RadarSource.entries
            options.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = vm.radarSource == s,
                    onClick = { vm.setSource(s) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(s.label, maxLines = 1) }
            }
        }
        Text(
            when (vm.radarSource) {
                RadarSource.UsMosaic -> "All US NEXRAD radars merged, every 5 minutes."
                RadarSource.Station -> "One NEXRAD radar at full resolution (250 m), including velocity. Tap a station dot on the map to switch."
                RadarSource.Global -> "Worldwide radar composite (coarser; detail stops at regional zoom)."
                RadarSource.Off -> "No radar."
            },
            style = MaterialTheme.typography.labelMedium,
            color = LocalStorm.current.dim,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (vm.radarSource == RadarSource.Station) {
            vm.site?.let { s ->
                Text("Station: ${s.callSign} · ${s.name}, ${s.state}", modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.Medium)
            }
            val here = vm.place?.point
            if (here != null && vm.sites.isNotEmpty()) {
                val near = vm.sites.sortedBy { com.moblized.stormdeck.data.Geo.distanceKm(it.point, here) }.take(5)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    near.forEach { s ->
                        FilterChip(selected = s.id == vm.site?.id, onClick = { vm.selectSite(s) }, label = { Text(s.callSign) })
                    }
                }
            }
        }

        SectionTitle("Radar opacity")
        Slider(
            value = settings.radarOpacity,
            onValueChange = { v -> Graph.settings.update { it.copy(radarOpacity = v) } },
            valueRange = 0.3f..1f,
        )
        SectionTitle("Loop speed")
        Slider(
            value = (900 - settings.frameMillis).toFloat(),
            onValueChange = { v -> Graph.settings.update { it.copy(frameMillis = (900 - v).toInt()) } },
            valueRange = 0f..700f,
        )

        SectionTitle("Satellite")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = SatelliteMode.entries
            options.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = vm.satellite == m,
                    onClick = { vm.chooseSatellite(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(m.label) }
            }
        }
        Text(
            "GOES-East, GOES-West and Himawari, about 40 minutes behind real time.",
            style = MaterialTheme.typography.labelMedium,
            color = LocalStorm.current.dim,
            modifier = Modifier.padding(top = 6.dp),
        )

        SectionTitle("Overlays")
        ToggleRow("NWS watches & warnings", "Tornado, severe storm, flood, winter and more (US)", vm.showWarnings, vm::toggleWarnings)
        ToggleRow("Hurricanes & tropical storms", "Positions, tracks and forecast cones worldwide", vm.showStorms, vm::toggleStorms)
        ToggleRow("Sea temperature anomaly", "The El Niño / La Niña view: warm (red) and cool (blue) oceans", vm.showSst, vm::toggleSst)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { vm.refreshFrames() }) { Text("Reload radar") }
    }
}

@Composable
fun ToggleRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelMedium, color = LocalStorm.current.dim)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
