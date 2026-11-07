package com.tomcollins.tracklog.io

import android.util.Xml
import com.tomcollins.tracklog.data.MarkerEntity
import com.tomcollins.tracklog.data.PointEntity
import com.tomcollins.tracklog.data.TrackEntity
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class GpxPoint(val lat: Double, val lon: Double, val ele: Double?, val time: Long?)
data class GpxWaypoint(val lat: Double, val lon: Double, val ele: Double?, val time: Long?, val name: String, val desc: String)
data class ParsedGpx(
    val name: String?,
    val desc: String?,
    val type: String?,
    val segments: List<List<GpxPoint>>,
    val waypoints: List<GpxWaypoint>,
)

private fun esc(s: String) = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")

private fun iso(t: Long) = Instant.ofEpochMilli(t).toString()

object Gpx {
    fun write(out: OutputStream, track: TrackEntity, points: List<PointEntity>, markers: List<MarkerEntity>) {
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
            w.write("""<gpx version="1.1" creator="TrackLog" xmlns="http://www.topografix.com/GPX/1/1">""" + "\n")
            w.write("  <metadata><name>${esc(track.name)}</name><time>${iso(track.startTime)}</time></metadata>\n")
            for (m in markers) {
                w.write("""  <wpt lat="${m.lat}" lon="${m.lon}">""")
                m.alt?.let { w.write("<ele>${"%.1f".format(java.util.Locale.US, it)}</ele>") }
                w.write("<time>${iso(m.time)}</time><name>${esc(m.name)}</name>")
                if (m.note.isNotBlank()) w.write("<desc>${esc(m.note)}</desc>")
                m.photoPath?.let { w.write("<link href=\"${esc(java.io.File(it).name)}\"><text>photo</text></link>") }
                w.write("</wpt>\n")
            }
            w.write("  <trk>\n    <name>${esc(track.name)}</name>\n")
            if (track.description.isNotBlank()) w.write("    <desc>${esc(track.description)}</desc>\n")
            w.write("    <type>${esc(track.activityType.label)}</type>\n")
            points.groupBy { it.segment }.toSortedMap().values.forEach { seg ->
                w.write("    <trkseg>\n")
                for (p in seg) writePoint(w, p)
                w.write("    </trkseg>\n")
            }
            w.write("  </trk>\n</gpx>\n")
        }
    }

    private fun writePoint(w: Writer, p: PointEntity) {
        w.write("""      <trkpt lat="${p.lat}" lon="${p.lon}">""")
        p.alt?.let { w.write("<ele>${"%.1f".format(java.util.Locale.US, it)}</ele>") }
        w.write("<time>${iso(p.time)}</time></trkpt>\n")
    }

    fun parse(input: InputStream): ParsedGpx {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var trkName: String? = null
        var metaName: String? = null
        var desc: String? = null
        var type: String? = null
        val segments = mutableListOf<MutableList<GpxPoint>>()
        val waypoints = mutableListOf<GpxWaypoint>()

        val stack = ArrayList<String>()
        var lat = 0.0
        var lon = 0.0
        var ele: Double? = null
        var time: Long? = null
        var ptName = ""
        var ptDesc = ""
        var current: MutableList<GpxPoint>? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    stack += tag
                    when (tag) {
                        "trkseg", "rte" -> current = mutableListOf<GpxPoint>().also { segments += it }
                        "trkpt", "rtept", "wpt" -> {
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            ele = null; time = null; ptName = ""; ptDesc = ""
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isEmpty() || stack.isEmpty()) continue
                    val tag = stack.last()
                    val parent = stack.getOrNull(stack.size - 2)
                    when (tag) {
                        "ele" -> ele = text.toDoubleOrNull()
                        "time" -> if (parent in setOf("trkpt", "rtept", "wpt")) time = parseTime(text)
                        "name" -> when (parent) {
                            "trk", "rte" -> if (trkName == null) trkName = text
                            "metadata" -> metaName = text
                            "wpt" -> ptName = text
                        }
                        "desc" -> when (parent) {
                            "trk" -> if (desc == null) desc = text
                            "wpt" -> ptDesc = text
                        }
                        "type" -> if (parent == "trk") type = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "trkpt", "rtept" -> current?.add(GpxPoint(lat, lon, ele, time))
                        "wpt" -> waypoints += GpxWaypoint(lat, lon, ele, time, ptName.ifBlank { "Waypoint" }, ptDesc)
                        "trkseg", "rte" -> current = null
                    }
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                }
            }
        }
        return ParsedGpx(trkName ?: metaName, desc, type, segments.filter { it.isNotEmpty() }, waypoints)
    }

    fun parseTime(s: String): Long? =
        runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

object Kml {
    fun write(out: OutputStream, track: TrackEntity, points: List<PointEntity>, markers: List<MarkerEntity>) {
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
            w.write("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""" + "\n")
            w.write("<name>${esc(track.name)}</name>\n")
            w.write("""<Style id="track"><LineStyle><color>ff2d8cff</color><width>4</width></LineStyle></Style>""" + "\n")
            for (m in markers) {
                w.write("<Placemark><name>${esc(m.name)}</name>")
                if (m.note.isNotBlank()) w.write("<description>${esc(m.note)}</description>")
                w.write("<TimeStamp><when>${iso(m.time)}</when></TimeStamp>")
                w.write("<Point><coordinates>${m.lon},${m.lat}${m.alt?.let { ",$it" } ?: ""}</coordinates></Point></Placemark>\n")
            }
            w.write("<Placemark><name>${esc(track.name)}</name>")
            if (track.description.isNotBlank()) w.write("<description>${esc(track.description)}</description>")
            w.write("<styleUrl>#track</styleUrl><MultiGeometry>\n")
            points.groupBy { it.segment }.toSortedMap().values.forEach { seg ->
                w.write("<LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>\n")
                for (p in seg) w.write("${p.lon},${p.lat}${p.alt?.let { ",$it" } ?: ""}\n")
                w.write("</coordinates></LineString>\n")
            }
            w.write("</MultiGeometry></Placemark>\n</Document></kml>\n")
        }
    }
}
