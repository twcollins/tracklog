package com.tomcollins.tracklog.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Kayaking
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tomcollins.tracklog.data.ActivityType
import com.tomcollins.tracklog.data.MarkerEntity
import com.tomcollins.tracklog.photo.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("DEPRECATION")
fun ActivityType.icon(): ImageVector = when (this) {
    ActivityType.WALK -> Icons.Filled.DirectionsWalk
    ActivityType.HIKE -> Icons.Filled.Hiking
    ActivityType.RUN -> Icons.Filled.DirectionsRun
    ActivityType.CYCLE -> Icons.Filled.DirectionsBike
    ActivityType.PADDLE -> Icons.Filled.Kayaking
    ActivityType.SKI -> Icons.Filled.DownhillSkiing
    ActivityType.DRIVE -> Icons.Filled.DirectionsCar
    ActivityType.OTHER -> Icons.Filled.Place
}

/** Pace makes more sense than speed for these. */
val ActivityType.usesPace: Boolean get() = this == ActivityType.WALK || this == ActivityType.HIKE || this == ActivityType.RUN

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                value,
                style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun StatGrid(items: List<Pair<String, String>>, columns: Int = 2, big: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (l, v) -> StatTile(l, v, Modifier.weight(1f), big) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityChooser(selected: ActivityType, onSelect: (ActivityType) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActivityType.entries.forEach { a ->
            FilterChip(
                selected = a == selected,
                onClick = { onSelect(a) },
                label = { Text(a.label) },
                leadingIcon = { Icon(a.icon(), null, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
fun PhotoThumb(path: String, size: Dp, modifier: Modifier = Modifier, maxPx: Int = 320) {
    val bmp by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { Photos.loadBitmap(path, maxPx) }
    }
    Box(
        modifier.size(size).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun PhotoViewer(path: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val bmp by produceState<Bitmap?>(null, path) {
            value = withContext(Dispatchers.IO) { Photos.loadBitmap(path, 2048) }
        }
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
        }
    }
}

/** Create or edit a marker's name and note. */
@Composable
fun MarkerDialog(
    title: String,
    initialName: String,
    initialNote: String,
    onDismiss: () -> Unit,
    onSave: (name: String, note: String) -> Unit,
    onDelete: (() -> Unit)? = null,
    extra: @Composable (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var note by remember { mutableStateOf(initialNote) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                extra?.invoke()
                OutlinedTextField(
                    name, { name = it }, label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    note, { note = it }, label = { Text("Note") }, minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.ifBlank { "Marker" }, note) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun MarkerRow(m: MarkerEntity, onClick: () -> Unit, onPhoto: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.photoPath != null) {
            PhotoThumb(m.photoPath, 56.dp, Modifier.clickable { onPhoto(m.photoPath) })
        } else {
            Box(
                Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Place, null, tint = MarkerColor) }
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(m.name, fontWeight = FontWeight.SemiBold)
            if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            Text(
                "${Format.time(m.time)} · ${Format.coords(m.lat, m.lon)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
