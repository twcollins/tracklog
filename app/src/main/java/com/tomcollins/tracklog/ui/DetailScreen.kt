package com.tomcollins.tracklog.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomcollins.tracklog.app
import com.tomcollins.tracklog.data.MarkerEntity
import com.tomcollins.tracklog.data.TrackEntity
import com.tomcollins.tracklog.recording.LatLon
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.app.repository
    val scope = rememberCoroutineScope()
    val settings by ctx.app.settings.state.collectAsStateWithLifecycle()
    val track by remember(id) { repo.track(id) }.collectAsStateWithLifecycle(null)
    val markers by remember(id) { repo.markers(id) }.collectAsStateWithLifecycle(emptyList())
    val points by remember(id) { repo.pointsFlow(id) }.collectAsStateWithLifecycle(emptyList())
    val segments = remember(points) {
        points.groupBy { it.segment }.toSortedMap().values.map { seg -> seg.map { LatLon(it.lat, it.lon) } }
    }
    val profile = remember(points) { buildProfile(points) }

    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editMarker by remember { mutableStateOf<MarkerEntity?>(null) }
    var viewPhoto by remember { mutableStateOf<String?>(null) }
    var deleted by remember { mutableStateOf(false) }

    val t = track
    LaunchedEffect(t, deleted) { if (deleted) onBack() }

    val gpxSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) scope.launch {
            runCatching { repo.exportGpx(id, uri) }
                .onSuccess { Toast.makeText(ctx, "GPX saved", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val kmlSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri ->
        if (uri != null) scope.launch {
            runCatching { repo.exportKml(id, uri) }
                .onSuccess { Toast.makeText(ctx, "KML saved", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val r = repo.addPhotos(id, uris)
            val msg = buildString {
                append("Added ${r.fromExif + r.fromTrackTime} photo(s)")
                if (r.fromTrackTime > 0) append(", ${r.fromTrackTime} placed by matching the time taken to the track")
                if (r.skipped > 0) append(". ${r.skipped} skipped (taken outside this track's time)")
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Filled.AddPhotoAlternate, "Add photos") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val base = t?.let { safeFileName(it) } ?: "track"
                        DropdownMenuItem(text = { Text("Export GPX…") }, onClick = { menu = false; gpxSaver.launch("$base.gpx") })
                        DropdownMenuItem(text = { Text("Export KML…") }, onClick = { menu = false; kmlSaver.launch("$base.kml") })
                        DropdownMenuItem(text = { Text("Delete track") }, onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
    ) { padding ->
        if (t == null) return@Scaffold
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val mapView: @Composable (Modifier) -> Unit = { m ->
            TrackMap(
                segments = segments,
                markers = markers,
                current = null,
                settings = settings,
                follow = false,
                onMarkerTap = { editMarker = it },
                modifier = m,
            )
        }
        // The map is kept outside the scrolling area so dragging it never scrolls the page.
        val mapHeight = maxHeight * 0.42f
        val details: @Composable (Modifier) -> Unit = { dm ->
        Column(
            dm.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))

            Text(
                "${t.activityType.label} · ${Format.date(t.startTime)}, ${Format.time(t.startTime)}–${Format.time(t.endTime)}",
                style = MaterialTheme.typography.titleSmall,
            )
            if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodyMedium)
            if (t.recording) Text("Still recording…", color = MaterialTheme.colorScheme.tertiary)

            val u = settings.units
            val pace = t.activityType.usesPace
            val avg = if (t.totalTimeMs > 0) t.distanceM / (t.totalTimeMs / 1000.0) else 0.0
            val avgMoving = if (t.movingTimeMs > 0) t.distanceM / (t.movingTimeMs / 1000.0) else 0.0
            StatGrid(
                listOf(
                    "Distance" to Format.distance(t.distanceM, u),
                    "Total time" to Format.duration(t.totalTimeMs),
                    "Moving time" to Format.duration(t.movingTimeMs),
                    (if (pace) "Avg moving pace" else "Avg moving speed") to (if (pace) Format.pace(avgMoving, u) else Format.speed(avgMoving, u)),
                    "Average speed" to Format.speed(avg, u),
                    "Max speed" to Format.speed(t.maxSpeedMps, u),
                    "Climb ↑" to Format.elevation(t.elevationGainM, u),
                    "Descent ↓" to Format.elevation(t.elevationLossM, u),
                    "Lowest" to Format.elevation(t.minAltM, u),
                    "Highest" to Format.elevation(t.maxAltM, u),
                ),
            )

            profile.altitude?.let { alt ->
                ProfileChart(
                    "Elevation", profile.distanceM, alt, MaterialTheme.colorScheme.primary, u,
                    formatY = { Format.elevation(it, u) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            if (points.size > 2) {
                ProfileChart(
                    if (pace) "Speed (pace shown when tapped)" else "Speed",
                    profile.distanceM, profile.speedMps, TrackColor, u,
                    formatY = { if (pace) "${Format.speed(it, u)} (${Format.pace(it, u)})" else Format.speed(it, u) },
                    modifier = Modifier.fillMaxWidth(),
                    yFromZero = true,
                )
            }

            Text(
                "Notes & photos (${markers.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (markers.isEmpty()) {
                Text(
                    "Add notes and photos while recording, or tap the photo button above to add photos you've taken. " +
                        "Photos without a location are placed by matching the time they were taken to this track.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            markers.forEach { m ->
                MarkerRow(m, onClick = { editMarker = m }, onPhoto = { viewPhoto = it })
                HorizontalDivider()
            }
            Text(
                "${t.pointCount} GPS points",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
        }
        if (landscape) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                mapView(Modifier.weight(1f).fillMaxHeight())
                details(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                mapView(Modifier.fillMaxWidth().height(mapHeight))
                details(Modifier.weight(1f).fillMaxWidth())
            }
        }
        }
    }

    if (editing && t != null) EditTrackDialog(t, onDismiss = { editing = false }) { name, desc, activity ->
        editing = false
        scope.launch { repo.updateInfo(id, name, desc, activity) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this track?") },
            text = { Text("The track, its notes and its photos will be permanently removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.deleteTrack(id); deleted = true }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    editMarker?.let { m ->
        MarkerDialog(
            title = "Edit marker",
            initialName = m.name,
            initialNote = m.note,
            onDismiss = { editMarker = null },
            onSave = { name, note -> editMarker = null; scope.launch { repo.updateMarker(m.copy(name = name, note = note)) } },
            onDelete = { editMarker = null; scope.launch { repo.deleteMarker(m) } },
            extra = m.photoPath?.let { p ->
                {
                    PhotoThumb(
                        p, 220.dp,
                        Modifier.fillMaxWidth().padding(bottom = 4.dp).clickable { viewPhoto = p },
                        maxPx = 800,
                    )
                }
            },
        )
    }

    viewPhoto?.let { PhotoViewer(it) { viewPhoto = null } }
}

private fun safeFileName(t: TrackEntity) =
    "${Format.fileStamp(t.startTime)}_${t.activityType.label}".replace(Regex("[^A-Za-z0-9_\\-]"), "_")

@Composable
private fun EditTrackDialog(
    t: TrackEntity,
    onDismiss: () -> Unit,
    onSave: (String, String, com.tomcollins.tracklog.data.ActivityType) -> Unit,
) {
    var name by remember { mutableStateOf(t.name) }
    var desc by remember { mutableStateOf(t.description) }
    var activity by remember { mutableStateOf(t.activityType) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit track") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(
                    desc, { desc = it }, label = { Text("Description") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                ActivityChooser(activity) { activity = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, desc, activity) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
