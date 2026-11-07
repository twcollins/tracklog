package com.tomcollins.tracklog.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomcollins.tracklog.app
import com.tomcollins.tracklog.data.ActivityType
import com.tomcollins.tracklog.data.TrackEntity
import com.tomcollins.tracklog.recording.Recorder
import com.tomcollins.tracklog.recording.RecordingService
import kotlinx.coroutines.launch

sealed interface Route {
    data object List : Route
    data object Record : Route
    data class Detail(val id: Long) : Route
    data object Settings : Route
}

@Composable
fun TrackLogRoot() {
    val stack = remember { mutableStateListOf<Route>(Route.List) }
    LaunchedEffect(Unit) {
        if (Recorder.state.value.active && stack.last() == Route.List) stack.add(Route.Record)
    }
    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val r = stack.last()) {
        Route.List -> ListScreen(
            onOpen = { push(Route.Detail(it)) },
            onRecording = { push(Route.Record) },
            onSettings = { push(Route.Settings) },
        )
        Route.Record -> RecordScreen(
            onBack = { pop() },
            onFinished = { id ->
                pop()
                if (id != null) push(Route.Detail(id))
            },
        )
        is Route.Detail -> DetailScreen(r.id, onBack = { pop() })
        Route.Settings -> SettingsScreen(onBack = { pop() })
    }
}

private enum class SortOrder(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first"), LONGEST("Longest first"), NAME("Name") }

fun hasLocationPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun gpsEnabled(ctx: Context) =
    (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)

/** Runs [action] once location permission is granted and GPS is switched on. */
class LocationGate(
    private val ctx: Context,
    private val request: () -> Unit,
    private val showGpsOff: () -> Unit,
) {
    var pending: (() -> Unit)? = null
    fun run(action: () -> Unit) {
        if (!hasLocationPermission(ctx)) {
            pending = action; request(); return
        }
        if (!gpsEnabled(ctx)) {
            showGpsOff(); return
        }
        action()
    }
}

@Composable
fun rememberLocationGate(): Pair<LocationGate, @Composable () -> Unit> {
    val ctx = LocalContext.current
    var gpsOff by remember { mutableStateOf(false) }
    var gate: LocationGate? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            gate?.pending?.let { gate?.pending = null; gate?.run(it) }
        } else {
            Toast.makeText(ctx, "Precise location permission is needed to record tracks.", Toast.LENGTH_LONG).show()
        }
    }
    val g = remember {
        LocationGate(
            ctx,
            request = {
                val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(perms.toTypedArray())
            },
            showGpsOff = { gpsOff = true },
        )
    }
    gate = g
    val dialog: @Composable () -> Unit = {
        if (gpsOff) {
            AlertDialog(
                onDismissRequest = { gpsOff = false },
                title = { Text("GPS is switched off") },
                text = { Text("Turn on Location in your phone's settings to record a track.") },
                confirmButton = {
                    TextButton(onClick = {
                        gpsOff = false
                        ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) { Text("Open settings") }
                },
                dismissButton = { TextButton(onClick = { gpsOff = false }) { Text("Cancel") } },
            )
        }
    }
    return g to dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListScreen(onOpen: (Long) -> Unit, onRecording: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.app.repository
    val scope = rememberCoroutineScope()
    val settings by ctx.app.settings.state.collectAsStateWithLifecycle()
    val tracks by repo.tracks().collectAsStateWithLifecycle(emptyList())
    val unfinished by repo.unfinishedTrack().collectAsStateWithLifecycle(null)
    val live by Recorder.state.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<ActivityType?>(null) }
    var sort by remember { mutableStateOf(SortOrder.NEWEST) }
    var sortMenu by remember { mutableStateOf(false) }
    var chooseActivity by remember { mutableStateOf(false) }
    val (gate, gateDialog) = rememberLocationGate()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { repo.importGpx(uri) }
                .onSuccess { onOpen(it) }
                .onFailure { Toast.makeText(ctx, "Couldn't import: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    val visible = remember(tracks, query, filter, sort) {
        tracks.filter { !it.recording }
            .filter { filter == null || it.activityType == filter }
            .filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) }
            .let { list ->
                when (sort) {
                    SortOrder.NEWEST -> list.sortedByDescending { it.startTime }
                    SortOrder.OLDEST -> list.sortedBy { it.startTime }
                    SortOrder.LONGEST -> list.sortedByDescending { it.distanceM }
                    SortOrder.NAME -> list.sortedBy { it.name.lowercase() }
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TrackLog") },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Filled.Search, "Search") }
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortOrder.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label, fontWeight = if (s == sort) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { sort = s; sortMenu = false },
                            )
                        }
                    }
                    IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FileOpen, "Import GPX") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            if (!live.active) {
                ExtendedFloatingActionButton(
                    onClick = { gate.run { chooseActivity = true } },
                    icon = { Icon(Icons.Filled.FiberManualRecord, null, tint = Color(0xFFE03131)) },
                    text = { Text("Record") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (live.active) {
                item {
                    Card(
                        onClick = onRecording,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.FiberManualRecord, null, tint = Color(0xFFE03131))
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(if (live.status == com.tomcollins.tracklog.recording.RecStatus.PAUSED) "Recording paused" else "Recording…", fontWeight = FontWeight.SemiBold)
                                Text(Format.distance(live.stats.distanceM, settings.units), style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Open")
                        }
                    }
                }
            } else unfinished?.let { t ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Unfinished recording", fontWeight = FontWeight.SemiBold)
                            Text(
                                "\"${t.name}\" was still recording when the app closed (${Format.distance(t.distanceM, settings.units)}).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    gate.run {
                                        RecordingService.send(ctx, RecordingService.ACTION_CONTINUE)
                                        onRecording()
                                    }
                                }) { Text("Continue") }
                                OutlinedButton(onClick = { scope.launch { repo.finish(t.id, t.totalTimeMs) } }) { Text("Finish") }
                            }
                        }
                    }
                }
            }

            if (searching) {
                item {
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth(),
                        placeholder = { Text("Search names and notes") }, singleLine = true,
                    )
                }
            }

            val types = tracks.map { it.activityType }.distinct()
            if (types.size > 1) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") }) }
                        items(ActivityType.entries.filter { it in types }) { a ->
                            FilterChip(
                                selected = filter == a,
                                onClick = { filter = if (filter == a) null else a },
                                label = { Text(a.label) },
                                leadingIcon = { Icon(a.icon(), null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }

            if (visible.isNotEmpty()) {
                item {
                    val dist = visible.sumOf { it.distanceM }
                    val time = visible.sumOf { it.totalTimeMs }
                    val gain = visible.sumOf { it.elevationGainM }
                    Text(
                        "${visible.size} tracks · ${Format.distance(dist, settings.units)} · ${Format.duration(time)} · ↑${Format.elevation(gain, settings.units)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (tracks.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No tracks yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap Record to log a walk or hike, or import a GPX file.\nEverything is stored only on this phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(visible, key = { it.id }) { t ->
                TrackRow(t, settings.units) { onOpen(t.id) }
                HorizontalDivider()
            }
        }
    }

    gateDialog()

    if (chooseActivity) {
        var activity by remember { mutableStateOf(settings.defaultActivity) }
        AlertDialog(
            onDismissRequest = { chooseActivity = false },
            title = { Text("Start recording") },
            text = { ActivityChooser(activity) { activity = it } },
            confirmButton = {
                Button(onClick = {
                    chooseActivity = false
                    RecordingService.send(ctx, RecordingService.ACTION_START) {
                        putExtra(RecordingService.EXTRA_ACTIVITY, activity.name)
                    }
                    onRecording()
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { chooseActivity = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrackRow(t: TrackEntity, units: com.tomcollins.tracklog.data.Units, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(t.activityType.icon(), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Format.date(t.startTime)} · ${Format.time(t.startTime)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Format.distance(t.distanceM, units), fontWeight = FontWeight.SemiBold)
            Text(
                Format.duration(t.totalTimeMs) + if (t.elevationGainM >= 1) " · ↑${Format.elevation(t.elevationGainM, units)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
