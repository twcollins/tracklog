package com.tomcollins.tracklog.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomcollins.tracklog.app
import com.tomcollins.tracklog.data.ActivityType
import com.tomcollins.tracklog.data.MarkerEntity
import com.tomcollins.tracklog.photo.Photos
import com.tomcollins.tracklog.recording.LatLon
import com.tomcollins.tracklog.recording.RecStatus
import com.tomcollins.tracklog.recording.Recorder
import com.tomcollins.tracklog.recording.RecordingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(onBack: () -> Unit, onFinished: (Long?) -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.app.repository
    val scope = rememberCoroutineScope()
    val settings by ctx.app.settings.state.collectAsStateWithLifecycle()
    val live by Recorder.state.collectAsStateWithLifecycle()
    val trackId = live.trackId
    val markers by remember(trackId) { if (trackId != null) repo.markers(trackId) else flowOf(emptyList()) }
        .collectAsState(emptyList())
    val track by remember(trackId) { if (trackId != null) repo.track(trackId) else flowOf(null) }.collectAsState(null)
    val activity = track?.activityType ?: ActivityType.WALK

    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(1000) }
    }

    // Recording stopped elsewhere (e.g. from the notification): move on to the track.
    var lastId by remember { mutableStateOf<Long?>(null) }
    var startedSeen by remember { mutableStateOf(false) }
    LaunchedEffect(live.status, live.trackId) {
        if (live.trackId != null) lastId = live.trackId
        if (live.active) startedSeen = true
        else if (startedSeen) onFinished(lastId)
    }

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    var confirmStop by remember { mutableStateOf(false) }
    var newMarker by remember { mutableStateOf(false) }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }

    fun currentPosition(): Triple<Double, Double, Double?>? {
        live.lastLocation?.let { return Triple(it.latitude, it.longitude, if (it.hasAltitude()) it.altitude else null) }
        live.segments.lastOrNull { it.isNotEmpty() }?.last()?.let { return Triple(it.lat, it.lon, null) }
        return null
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPhoto ?: return@rememberLauncherForActivityResult
        pendingPhoto = null
        val file = File(path)
        val id = Recorder.state.value.trackId
        val pos = currentPosition()
        if (!ok || id == null || !file.exists() || file.length() == 0L) {
            file.delete(); return@rememberLauncherForActivityResult
        }
        if (pos == null) {
            file.delete()
            Toast.makeText(ctx, "No GPS position yet, so the photo wasn't saved.", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            Photos.geotag(file, pos.first, pos.second, pos.third)
            repo.addMarker(
                MarkerEntity(
                    trackId = id, time = System.currentTimeMillis(), lat = pos.first, lon = pos.second, alt = pos.third,
                    name = "Photo ${markers.count { it.photoPath != null } + 1}", photoPath = file.absolutePath,
                ),
            )
        }
    }

    val takePhoto = {
        val f = Photos.newFile(ctx)
        pendingPhoto = f.absolutePath
        runCatching { camera.launch(Photos.uriFor(ctx, f)) }.onFailure {
            pendingPhoto = null
            Toast.makeText(ctx, "No camera app available.", Toast.LENGTH_SHORT).show()
        }
        Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (live.status == RecStatus.PAUSED) "Paused" else "Recording") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    val loc = live.lastLocation
                    val good = loc != null && loc.accuracy <= settings.maxAccuracyM
                    Icon(
                        if (good) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed, null,
                        tint = if (good) Color(0xFF2F9E44) else Color(0xFFF08C00),
                    )
                    Text(
                        if (loc == null) "Searching…" else "±${loc.accuracy.toInt()} m",
                        Modifier.padding(start = 4.dp, end = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        },
    ) { padding ->
        val current = live.lastLocation?.let { LatLon(it.latitude, it.longitude) }
        val s = live.stats
        val u = settings.units
        val speedNow = live.lastLocation?.takeIf { it.hasSpeed() }?.speed?.toDouble() ?: 0.0
        val pace = activity.usesPace
        val primary = listOf(
            "Time" to Format.duration(live.elapsedMs(now)),
            "Distance" to Format.distance(s.distanceM, u),
        )
        val secondary = listOf(
            (if (pace) "Pace" else "Speed") to (if (pace) Format.pace(speedNow, u) else Format.speed(speedNow, u)),
            (if (pace) "Avg pace" else "Avg speed") to (if (pace) Format.pace(s.avgMovingSpeedMps, u) else Format.speed(s.avgMovingSpeedMps, u)),
            "Moving" to Format.duration(s.movingTimeMs),
            "Altitude" to Format.elevation(live.lastLocation?.takeIf { it.hasAltitude() }?.altitude, u),
            "Climb ↑" to Format.elevation(s.elevationGainM, u),
            "Descent ↓" to Format.elevation(s.elevationLossM, u),
        )
        val map: @Composable (Modifier) -> Unit = { m ->
            TrackMap(
                segments = live.segments, markers = markers, current = current, settings = settings,
                follow = true, onMarkerTap = {}, modifier = m,
            )
        }
        val controls: @Composable () -> Unit = {
            RecordControls(
                status = live.status,
                notes = markers.size,
                onNote = { newMarker = true },
                onPhoto = takePhoto,
                onPause = { RecordingService.send(ctx, RecordingService.ACTION_PAUSE) },
                onResume = { RecordingService.send(ctx, RecordingService.ACTION_RESUME) },
                onStop = { confirmStop = true },
            )
        }

        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            if (maxWidth > maxHeight) {
                // Landscape: map on the left, stats and controls on the right.
                Row(Modifier.fillMaxSize()) {
                    map(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompactStats(primary, secondary, columns = 2)
                        controls()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    map(Modifier.weight(1f).fillMaxWidth())
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactStats(primary, secondary, columns = 3)
                        controls()
                    }
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop recording?") },
            text = { Text("The track will be saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    RecordingService.send(ctx, RecordingService.ACTION_STOP)
                }) { Text("Stop & save") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep recording") } },
        )
    }

    if (newMarker) {
        MarkerDialog(
            title = "Add a note here",
            initialName = "",
            initialNote = "",
            onDismiss = { newMarker = false },
            onSave = { name, note ->
                newMarker = false
                val id = live.trackId
                val pos = currentPosition()
                if (id == null || pos == null) {
                    Toast.makeText(ctx, "No GPS position yet.", Toast.LENGTH_SHORT).show()
                } else scope.launch {
                    repo.addMarker(MarkerEntity(trackId = id, time = System.currentTimeMillis(), lat = pos.first, lon = pos.second, alt = pos.third, name = name, note = note))
                }
            },
        )
    }
}

/** Two large figures plus a grid of smaller ones, without card chrome, to leave room for the map. */
@Composable
private fun CompactStats(primary: List<Pair<String, String>>, secondary: List<Pair<String, String>>, columns: Int) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                primary.forEach { (l, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(v, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            secondary.chunked(columns).forEach { row ->
                Row {
                    row.forEach { (l, v) ->
                        Column(Modifier.weight(1f)) {
                            Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(v, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RecordControls(
    status: RecStatus,
    notes: Int,
    onNote: () -> Unit,
    onPhoto: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val h = Modifier.height(48.dp)
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = onNote, modifier = h.weight(1f), contentPadding = pad) {
            Icon(Icons.Filled.AddLocationAlt, "Add note", Modifier.size(20.dp))
            if (notes > 0) Text(" $notes", style = MaterialTheme.typography.labelLarge)
        }
        FilledTonalButton(onClick = onPhoto, modifier = h.weight(1f), contentPadding = pad) {
            Icon(Icons.Filled.PhotoCamera, "Take photo", Modifier.size(20.dp))
        }
        if (status == RecStatus.PAUSED) {
            Button(onClick = onResume, modifier = h.weight(1.4f), contentPadding = pad) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp)); Text(" Resume", maxLines = 1)
            }
        } else {
            Button(onClick = onPause, enabled = status == RecStatus.RECORDING, modifier = h.weight(1.4f), contentPadding = pad) {
                Icon(Icons.Filled.Pause, null, Modifier.size(20.dp)); Text(" Pause", maxLines = 1)
            }
        }
        Button(
            onClick = onStop,
            enabled = status != RecStatus.IDLE,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = h.weight(1.4f), contentPadding = pad,
        ) { Icon(Icons.Filled.Stop, null, Modifier.size(20.dp)); Text(" Stop", maxLines = 1) }
    }
}
