package com.tomcollins.tracklog.data

import android.content.Context
import android.net.Uri
import com.tomcollins.tracklog.io.Gpx
import com.tomcollins.tracklog.io.Kml
import com.tomcollins.tracklog.photo.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class PhotoImportResult(val fromExif: Int, val fromTrackTime: Int, val skipped: Int)

class Repository(private val ctx: Context, db: AppDatabase) {
    val dao = db.dao()

    fun tracks() = dao.tracks()
    fun track(id: Long) = dao.track(id)
    fun markers(trackId: Long) = dao.markers(trackId)
    fun pointsFlow(trackId: Long) = dao.pointsFlow(trackId)
    fun unfinishedTrack() = dao.unfinishedTrackFlow()
    suspend fun points(trackId: Long) = dao.points(trackId)
    suspend fun getTrack(id: Long) = dao.getTrack(id)

    fun defaultName(activity: ActivityType, time: Long): String {
        val f = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
        return "${activity.label} · ${f.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))}"
    }

    suspend fun createTrack(activity: ActivityType): Long {
        val now = System.currentTimeMillis()
        return dao.insertTrack(
            TrackEntity(name = defaultName(activity, now), activity = activity.name, startTime = now, recording = true),
        )
    }

    suspend fun updateInfo(id: Long, name: String, description: String, activity: ActivityType) {
        val t = dao.getTrack(id) ?: return
        dao.updateTrack(t.copy(name = name.ifBlank { t.name }, description = description, activity = activity.name))
    }

    /** Recomputes the summary stats from the stored points. */
    suspend fun finish(id: Long, totalTimeMs: Long?) {
        val t = dao.getTrack(id) ?: return
        val s = StatsAccumulator.of(dao.points(id))
        dao.updateTrack(t.withStats(s, totalTimeMs ?: maxOf(t.totalTimeMs, s.totalTimeMs)).copy(recording = false))
    }

    suspend fun deleteTrack(id: Long) {
        dao.getMarkers(id).forEach { m -> m.photoPath?.let { File(it).delete() } }
        dao.deleteTrack(id)
    }

    suspend fun addMarker(m: MarkerEntity): Long = dao.insertMarker(m)
    suspend fun updateMarker(m: MarkerEntity) = dao.updateMarker(m)
    suspend fun deleteMarker(m: MarkerEntity) {
        m.photoPath?.let { File(it).delete() }
        dao.deleteMarker(m.id)
    }

    // ---- photos ---------------------------------------------------------------------------

    /**
     * Adds photos to a finished track. Location comes from the photo's own GPS EXIF if present,
     * otherwise the photo's timestamp is matched against the track and the position interpolated.
     * The location is written into the app's copy of the photo.
     */
    suspend fun addPhotos(trackId: Long, uris: List<Uri>): PhotoImportResult = withContext(Dispatchers.IO) {
        val points = dao.points(trackId)
        var exif = 0
        var timed = 0
        var skipped = 0
        for (uri in uris) {
            val file = Photos.copyIntoApp(ctx, uri)
            if (file == null) { skipped++; continue }
            val info = Photos.read(file)
            var lat = info.lat
            var lon = info.lon
            var alt: Double? = null
            if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) {
                exif++
            } else {
                val located = info.timeMillis?.let { locate(points, it) }
                if (located == null) {
                    file.delete(); skipped++; continue
                }
                lat = located.lat; lon = located.lon; alt = located.alt
                Photos.geotag(file, lat, lon, alt)
                timed++
            }
            dao.insertMarker(
                MarkerEntity(
                    trackId = trackId,
                    time = info.timeMillis ?: System.currentTimeMillis(),
                    lat = lat!!, lon = lon!!, alt = alt,
                    name = "Photo", photoPath = file.absolutePath,
                ),
            )
        }
        PhotoImportResult(exif, timed, skipped)
    }

    private data class Located(val lat: Double, val lon: Double, val alt: Double?)

    private fun locate(points: List<PointEntity>, time: Long): Located? {
        if (points.isEmpty()) return null
        val tolerance = 10 * 60_000L
        if (time < points.first().time - tolerance || time > points.last().time + tolerance) return null
        var lo = 0
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].time < time) lo = mid + 1 else hi = mid
        }
        val after = points[lo]
        val before = points.getOrNull(lo - 1)
        if (before == null || after.time <= time || before.segment != after.segment) {
            val nearest = listOfNotNull(before, after).minBy { abs(it.time - time) }
            return Located(nearest.lat, nearest.lon, nearest.alt)
        }
        val f = (time - before.time).toDouble() / (after.time - before.time)
        val alt = if (before.alt != null && after.alt != null) before.alt + (after.alt - before.alt) * f else before.alt ?: after.alt
        return Located(before.lat + (after.lat - before.lat) * f, before.lon + (after.lon - before.lon) * f, alt)
    }

    // ---- import / export ------------------------------------------------------------------

    suspend fun exportGpx(id: Long, uri: Uri) = withContext(Dispatchers.IO) {
        val t = dao.getTrack(id) ?: return@withContext
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { Gpx.write(it, t, dao.points(id), dao.getMarkers(id)) }
    }

    suspend fun exportKml(id: Long, uri: Uri) = withContext(Dispatchers.IO) {
        val t = dao.getTrack(id) ?: return@withContext
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { Kml.write(it, t, dao.points(id), dao.getMarkers(id)) }
    }

    /** Imports a GPX file; returns the new track id. */
    suspend fun importGpx(uri: Uri): Long = withContext(Dispatchers.IO) {
        val parsed = ctx.contentResolver.openInputStream(uri)!!.use { Gpx.parse(it) }
        require(parsed.segments.isNotEmpty() || parsed.waypoints.isNotEmpty()) { "No track found in file" }
        val firstTime = parsed.segments.firstOrNull()?.firstOrNull { it.time != null }?.time
            ?: parsed.waypoints.firstOrNull { it.time != null }?.time
            ?: System.currentTimeMillis()
        val activity = guessActivity(parsed.type)
        val id = dao.insertTrack(
            TrackEntity(
                name = parsed.name ?: defaultName(activity, firstTime),
                description = parsed.desc.orEmpty(),
                activity = activity.name,
                startTime = firstTime,
            ),
        )
        // Points without timestamps get synthetic one-second spacing so ordering is kept.
        var synthetic = firstTime
        val points = ArrayList<PointEntity>()
        parsed.segments.forEachIndexed { segIndex, seg ->
            for (p in seg) {
                val time = p.time ?: (synthetic + 1000)
                synthetic = time
                points += PointEntity(trackId = id, time = time, lat = p.lat, lon = p.lon, alt = p.ele, accuracy = null, speed = null, segment = segIndex)
            }
        }
        points.chunked(2000).forEach { dao.insertPoints(it) }
        for (w in parsed.waypoints) {
            dao.insertMarker(MarkerEntity(trackId = id, time = w.time ?: firstTime, lat = w.lat, lon = w.lon, alt = w.ele, name = w.name, note = w.desc))
        }
        finish(id, null)
        id
    }

    private fun guessActivity(type: String?): ActivityType {
        val t = type?.lowercase() ?: return ActivityType.OTHER
        return when {
            "hik" in t -> ActivityType.HIKE
            "walk" in t -> ActivityType.WALK
            "run" in t -> ActivityType.RUN
            "cycl" in t || "bik" in t -> ActivityType.CYCLE
            "ski" in t -> ActivityType.SKI
            "kayak" in t || "canoe" in t || "paddl" in t -> ActivityType.PADDLE
            "driv" in t || "car" in t -> ActivityType.DRIVE
            else -> ActivityType.entries.firstOrNull { it.label.equals(type, true) } ?: ActivityType.OTHER
        }
    }
}
