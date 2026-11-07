package com.tomcollins.tracklog.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomcollins.tracklog.app
import com.tomcollins.tracklog.data.MapStyle
import com.tomcollins.tracklog.data.Units
import com.tomcollins.tracklog.photo.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = ctx.app.settings
    val s by store.state.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var storage by remember { mutableStateOf("") }
    LaunchedEffect(refresh) {
        storage = withContext(Dispatchers.IO) {
            val db = ctx.getDatabasePath("tracklog.db").let { it.length() + File(it.path + "-wal").length() }
            val photos = Photos.dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val tiles = OsmSetup.tileCacheDir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
            "Tracks database ${mb(db)} · photos ${mb(photos)} · map tile cache ${mb(tiles)}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header("Units")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.units == Units.METRIC, { store.update { it.copy(units = Units.METRIC) } }, { Text("Metric (km, m)") })
                FilterChip(s.units == Units.IMPERIAL, { store.update { it.copy(units = Units.IMPERIAL) } }, { Text("Imperial (mi, ft)") })
            }

            HorizontalDivider()
            Header("Maps")
            SwitchRow(
                "Show online map",
                "Downloads map images from the chosen provider (OpenStreetMap, OpenTopoMap or Esri). That server sees " +
                    "your IP address and which area you're viewing; your tracks are never uploaded. When off, tracks are " +
                    "drawn offline with no network use.",
                s.mapEnabled,
            ) { v -> store.update { it.copy(mapEnabled = v) } }
            if (s.mapEnabled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapStyle.entries.forEach { m ->
                        FilterChip(s.mapStyle == m, { store.update { it.copy(mapStyle = m) } }, { Text(m.label) })
                    }
                }
            }
            OutlinedButton(onClick = {
                OsmSetup.tileCacheDir(ctx).deleteRecursively()
                refresh++
                Toast.makeText(ctx, "Map cache cleared", Toast.LENGTH_SHORT).show()
            }) { Text("Clear map tile cache") }

            HorizontalDivider()
            Header("Recording")
            Label("GPS update interval")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1000L to "1 s", 2000L to "2 s", 5000L to "5 s", 10000L to "10 s", 30000L to "30 s").forEach { (v, l) ->
                    FilterChip(s.gpsIntervalMs == v, { store.update { it.copy(gpsIntervalMs = v) } }, { Text(l) })
                }
            }
            Text(
                "Shorter is more detailed; longer saves battery. Takes effect on the next recording.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Label("Minimum distance between points")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 3, 5, 10, 20).forEach { v ->
                    FilterChip(s.minDistanceM == v, { store.update { it.copy(minDistanceM = v) } }, { Text("$v m") })
                }
            }
            Label("Ignore fixes less accurate than")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 20, 30, 50, 100).forEach { v ->
                    FilterChip(s.maxAccuracyM == v, { store.update { it.copy(maxAccuracyM = v) } }, { Text("$v m") })
                }
            }
            Label("Default activity")
            ActivityChooser(s.defaultActivity) { a -> store.update { it.copy(defaultActivity = a) } }
            SwitchRow("Keep screen on while recording", "Uses more battery.", s.keepScreenOn) { v ->
                store.update { it.copy(keepScreenOn = v) }
            }
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }) { Text("Battery optimisation settings") }
            Text(
                "Some phones stop background apps to save battery. If recordings stop when the screen is off, " +
                    "set TrackLog to \"Unrestricted\" / \"Don't optimise\".",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Header("Privacy & storage")
            Text(
                "All tracks, notes and photos are stored only in this app's private storage on your phone and are " +
                    "excluded from Google cloud backup. Location comes directly from the GPS chip (no Google Play " +
                    "services). Nothing is shared unless you export a GPX/KML file yourself.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(storage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun mb(bytes: Long) = String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)

@Composable
private fun Header(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}
