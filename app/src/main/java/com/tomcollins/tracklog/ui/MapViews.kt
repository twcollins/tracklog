package com.tomcollins.tracklog.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tomcollins.tracklog.app
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tomcollins.tracklog.data.AppSettings
import com.tomcollins.tracklog.data.MapStyle
import com.tomcollins.tracklog.data.MarkerEntity
import com.tomcollins.tracklog.data.Units
import com.tomcollins.tracklog.recording.LatLon
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Chooses the online OSM map (only if enabled in Settings) or the fully offline track view. */
@Composable
fun TrackMap(
    segments: List<List<LatLon>>,
    markers: List<MarkerEntity>,
    current: LatLon?,
    settings: AppSettings,
    follow: Boolean,
    onMarkerTap: (MarkerEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (settings.mapEnabled) {
            OsmMap(segments, markers, current, settings.mapStyle, follow, onMarkerTap, Modifier.matchParentSize())
        } else {
            OfflineTrackView(segments, markers, current, settings.units, onMarkerTap, Modifier.matchParentSize())
        }
        LayerPicker(settings, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

/** Quick switch between offline view and the online map styles, right on the map. */
@Composable
private fun LayerPicker(settings: AppSettings, modifier: Modifier) {
    val store = LocalContext.current.app.settings
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        androidx.compose.material3.SmallFloatingActionButton(
            onClick = { open = true },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Layers, "Map layers") }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun item(label: String, selected: Boolean, onPick: () -> Unit) =
                @Composable {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = {
                            androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                        },
                        onClick = { open = false; onPick() },
                    )
                }
            item("Offline (no map)", !settings.mapEnabled) { store.update { it.copy(mapEnabled = false) } }()
            MapStyle.entries.forEach { style ->
                item(style.label, settings.mapEnabled && settings.mapStyle == style) {
                    store.update { it.copy(mapEnabled = true, mapStyle = style) }
                }()
            }
            Text(
                "Online maps download map images; your tracks stay on the phone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 240.dp),
            )
        }
    }
}

object OsmSetup {
    private var done = false

    /**
     * Esri World Imagery. Its URL scheme is /tile/{z}/{y}/{x} (row before column), so it needs a
     * custom source. Kept as a single instance so the map isn't reset on every recomposition.
     */
    val esriImagery: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "EsriWorldImagery", 0, 19, 256, "",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "Imagery © Esri, Maxar, Earthstar Geographics, and the GIS User Community",
        TileSourcePolicy(
            2,
            TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_NO_PREVENTIVE or
                TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED,
        ),
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
    }

    fun source(style: MapStyle): ITileSource = when (style) {
        MapStyle.STANDARD -> TileSourceFactory.MAPNIK
        MapStyle.TOPO -> TileSourceFactory.OpenTopo
        MapStyle.SATELLITE -> esriImagery
    }

    fun tileCacheDir(ctx: Context) = File(ctx.cacheDir, "osmdroid")

    fun init(ctx: Context) {
        if (done) return
        val c = Configuration.getInstance()
        c.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        c.userAgentValue = ctx.packageName
        val base = tileCacheDir(ctx)
        c.osmdroidBasePath = base
        c.osmdroidTileCache = File(base, "tiles")
        // Keep more decoded tiles in memory so panning back and forth doesn't re-decode them.
        c.cacheMapTileCount = 64.toShort()
        c.cacheMapTileOvershoot = 16.toShort()
        c.tileFileSystemThreads = 4.toShort()
        done = true
    }
}

/** What's currently drawn on the map, so overlays are only rebuilt when the data really changes. */
private class MapContent {
    var segments: List<List<LatLon>>? = null
    var markers: List<MarkerEntity>? = null
    var current: LatLon? = null
    val dataOverlays = ArrayList<org.osmdroid.views.overlay.Overlay>()
    var fittedCount = 0
}

@Composable
private fun OsmMap(
    segments: List<List<LatLon>>,
    markers: List<MarkerEntity>,
    current: LatLon?,
    style: MapStyle,
    follow: Boolean,
    onMarkerTap: (MarkerEntity) -> Unit,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tap by rememberUpdatedState(onMarkerTap)
    val following by rememberUpdatedState(follow)
    // Set when the user drags the map while following; shows a "re-centre" button.
    var userMoved by remember { mutableStateOf(false) }
    val content = remember { MapContent() }
    val mapView = remember {
        OsmSetup.init(ctx)
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            isTilesScaledToDpi = true
            isVerticalMapRepetitionEnabled = false
            controller.setZoom(15.0)
            // Static overlays, created once.
            overlays += CopyrightOverlay(ctx)
            overlays += ScaleBarOverlay(this).apply {
                val dm = ctx.resources.displayMetrics
                setScaleBarOffset((12 * dm.density).toInt(), (12 * dm.density).toInt())
            }
            // Keep the page from scrolling while the finger is on the map, and stop
            // auto-following once the user pans.
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE && following) userMoved = true
                false
            }
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize().clipToBounds(), update = { map ->
            val source = OsmSetup.source(style)
            if (map.tileProvider.tileSource !== source) {
                map.setTileSource(source)
                map.maxZoomLevel = source.maximumZoomLevel.toDouble()
            }

            val dataChanged = segments !== content.segments || markers !== content.markers || current != content.current
            if (!dataChanged) return@AndroidView
            val currentMoved = current != content.current
            content.segments = segments
            content.markers = markers
            content.current = current

            map.overlays.removeAll(content.dataOverlays.toSet())
            content.dataOverlays.clear()
            val add = { o: org.osmdroid.views.overlay.Overlay -> content.dataOverlays += o }

            val all = ArrayList<GeoPoint>()
            for (seg in segments) {
                val pts = seg.map { GeoPoint(it.lat, it.lon) }
                all += pts
                if (pts.size < 2) continue
                add(Polyline(map).apply {
                    setPoints(pts)
                    outlinePaint.color = TrackColor.toArgb()
                    outlinePaint.strokeWidth = 12f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    infoWindow = null
                })
            }
            segments.firstOrNull { it.isNotEmpty() }?.first()?.let { add(dotMarker(map, it, StartColor, "Start")) }
            if (current == null) segments.lastOrNull { it.isNotEmpty() }?.last()?.let { add(dotMarker(map, it, EndColor, "Finish")) }
            for (m in markers) {
                add(dotMarker(map, LatLon(m.lat, m.lon), if (m.photoPath != null) PhotoColor else MarkerColor, m.name, sizeDp = 18).apply {
                    setOnMarkerClickListener { _, _ -> tap(m); true }
                })
                all += GeoPoint(m.lat, m.lon)
            }
            current?.let {
                add(dotMarker(map, it, MarkerColor, "You", sizeDp = 18))
                if (all.isEmpty()) all += GeoPoint(it.lat, it.lon)
            }
            // Data goes underneath the copyright/scale overlays.
            map.overlays.addAll(0, content.dataOverlays)

            val shouldFit = all.isNotEmpty() && (content.fittedCount == 0 || (!follow && all.size != content.fittedCount))
            if (shouldFit) {
                content.fittedCount = all.size
                val pad = (32 * ctx.resources.displayMetrics.density).toInt()
                val fit = {
                    if (all.size == 1) {
                        map.controller.setZoom(17.0); map.controller.setCenter(all[0])
                    } else {
                        map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(all).increaseByScale(1.1f), false, pad)
                    }
                }
                if (map.isLayoutOccurred && map.height > 0) map.post { fit() }
                else map.addOnFirstLayoutListener { _, _, _, _, _ -> map.post { fit() } }
            } else if (follow && current != null && currentMoved && !userMoved) {
                map.controller.animateTo(GeoPoint(current.lat, current.lon))
            }
            map.invalidate()
        })
        if (follow && userMoved && current != null) {
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = {
                    userMoved = false
                    mapView.controller.animateTo(GeoPoint(current.lat, current.lon))
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.MyLocation, "Re-centre on my position") }
        }
    }
}

private fun dotMarker(map: MapView, p: LatLon, color: Color, label: String, sizeDp: Int = 14): Marker {
    val px = (sizeDp * map.context.resources.displayMetrics.density).toInt()
    // (title is used for accessibility only; info windows are disabled)
    val d = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color.toArgb())
        setStroke(px / 6, android.graphics.Color.WHITE)
        setSize(px, px)
    }
    return Marker(map).apply {
        position = GeoPoint(p.lat, p.lon)
        icon = d
        title = label
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
    }
}

/**
 * Draws the track without any map tiles — works with no network at all.
 * Pinch to zoom, drag to pan, double-tap to reset.
 */
@Composable
fun OfflineTrackView(
    segments: List<List<LatLon>>,
    markers: List<MarkerEntity>,
    current: LatLon?,
    units: Units,
    onMarkerTap: (MarkerEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val tap by rememberUpdatedState(onMarkerTap)
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    val allPoints = remember(segments, markers, current) {
        segments.flatten() + markers.map { LatLon(it.lat, it.lon) } + listOfNotNull(current)
    }
    if (allPoints.isEmpty()) {
        Box(modifier.background(bg), contentAlignment = Alignment.Center) {
            Text("Waiting for GPS…", color = textColor)
        }
        return
    }
    val minLat = allPoints.minOf { it.lat }
    val maxLat = allPoints.maxOf { it.lat }
    val minLon = allPoints.minOf { it.lon }
    val maxLon = allPoints.maxOf { it.lon }
    val kx = cos(Math.toRadians((minLat + maxLat) / 2))
    val cx = (minLon + maxLon) / 2 * kx
    val cy = -(minLat + maxLat) / 2
    val spanX = max((maxLon - minLon) * kx, 0.0005)
    val spanY = max(maxLat - minLat, 0.0005)

    Box(modifier) {
        Canvas(
            Modifier
                .matchParentSize()
                .background(bg)
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(0.3f, 200f)
                        val z = newScale / scale
                        val c = Offset(size.width / 2f, size.height / 2f)
                        offset = offset * z + (centroid - c) * (1 - z) + pan
                        scale = newScale
                    }
                }
                .pointerInput(markers) {
                    detectTapGestures(
                        onDoubleTap = { scale = 1f; offset = Offset.Zero },
                        onTap = { o ->
                            val base = minOf(size.width * 0.85 / spanX, size.height * 0.85 / spanY)
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val hit = markers.minByOrNull { m ->
                                val p = Offset(
                                    (c.x + (m.lon * kx - cx) * base * scale + offset.x).toFloat(),
                                    (c.y + (-m.lat - cy) * base * scale + offset.y).toFloat(),
                                )
                                (p - o).getDistance()
                            }
                            if (hit != null) {
                                val p = Offset(
                                    (c.x + (hit.lon * kx - cx) * base * scale + offset.x).toFloat(),
                                    (c.y + (-hit.lat - cy) * base * scale + offset.y).toFloat(),
                                )
                                if ((p - o).getDistance() < 36.dp.toPx()) tap(hit)
                            }
                        },
                    )
                },
        ) {
            val base = minOf(size.width * 0.85 / spanX, size.height * 0.85 / spanY)
            val c = Offset(size.width / 2f, size.height / 2f)
            fun project(p: LatLon) = Offset(
                (c.x + (p.lon * kx - cx) * base * scale + offset.x).toFloat(),
                (c.y + (-p.lat - cy) * base * scale + offset.y).toFloat(),
            )

            // Grid with a "nice" spacing in metres.
            val metresPerPx = 111_320.0 / (base * scale)
            val targetPx = 90.0
            val raw = metresPerPx * targetPx
            val mag = 10.0.pow(floor(log10(raw)))
            val nice = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
            val stepPx = (nice / metresPerPx).toFloat()
            if (stepPx > 8f) {
                val origin = project(LatLon(minLat, minLon))
                var x = origin.x % stepPx
                while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); x += stepPx }
                var y = origin.y % stepPx
                while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); y += stepPx }
            }

            for (seg in segments) {
                if (seg.size < 2) continue
                val path = Path()
                seg.forEachIndexed { i, p ->
                    val o = project(p)
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, Color.White.copy(alpha = 0.7f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, TrackColor, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            fun dot(p: LatLon, color: Color, r: Float) {
                val o = project(p)
                drawCircle(Color.White, r + 2.5.dp.toPx(), o)
                drawCircle(color, r, o)
            }
            segments.firstOrNull { it.isNotEmpty() }?.first()?.let { dot(it, StartColor, 6.dp.toPx()) }
            if (current == null) segments.lastOrNull { it.isNotEmpty() }?.last()?.let { dot(it, EndColor, 6.dp.toPx()) }
            for (m in markers) {
                val o = project(LatLon(m.lat, m.lon))
                drawCircle(Color.White, 9.dp.toPx(), o)
                drawCircle(if (m.photoPath != null) PhotoColor else MarkerColor, 7.dp.toPx(), o)
            }
            current?.let { dot(it, MarkerColor, 8.dp.toPx()) }

            // Scale bar label
            val label = Format.distance(nice, units).let { if (units == Units.METRIC && nice >= 1000) "${(nice / 1000).toInt()} km" else it }
            if (stepPx > 8f) {
                val y = size.height - 14.dp.toPx()
                val x0 = 12.dp.toPx()
                drawLine(textColor, Offset(x0, y), Offset(x0 + stepPx, y), 2.dp.toPx())
                drawText(measurer, label, Offset(x0, y - 18.dp.toPx()), style = TextStyle(color = textColor, fontSize = 11.sp))
            }
        }
        Surface(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Offline view", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}
