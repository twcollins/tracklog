package com.tomcollins.tracklog.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tomcollins.tracklog.MainActivity
import com.tomcollins.tracklog.R
import com.tomcollins.tracklog.app
import com.tomcollins.tracklog.data.Geo
import com.tomcollins.tracklog.data.PointEntity
import com.tomcollins.tracklog.data.StatsAccumulator
import com.tomcollins.tracklog.data.TrackStats
import com.tomcollins.tracklog.data.withStats
import com.tomcollins.tracklog.ui.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

enum class RecStatus { IDLE, RECORDING, PAUSED }

data class LatLon(val lat: Double, val lon: Double)

data class LiveState(
    val status: RecStatus = RecStatus.IDLE,
    val trackId: Long? = null,
    val stats: TrackStats = TrackStats(),
    /** Elapsed time accumulated before the current running stretch. */
    val elapsedBaseMs: Long = 0,
    /** SystemClock.elapsedRealtime() when recording (re)started, or null while paused. */
    val runningSince: Long? = null,
    val lastLocation: Location? = null,
    val segments: List<List<LatLon>> = emptyList(),
) {
    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()) =
        elapsedBaseMs + (runningSince?.let { now - it } ?: 0)

    val active: Boolean get() = status != RecStatus.IDLE
}

/** Single source of truth for the live recording, observed by the UI. */
object Recorder {
    internal val mutable = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = mutable.asStateFlow()
}

class RecordingService : LifecycleService() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        /** Continue recording into an unfinished track (e.g. after the app was killed). */
        const val ACTION_CONTINUE = "continue"
        const val EXTRA_ACTIVITY = "activity"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 42

        fun send(ctx: Context, action: String, configure: Intent.() -> Unit = {}) {
            val i = Intent(ctx, RecordingService::class.java).setAction(action).apply(configure)
            if (action == ACTION_STOP || action == ACTION_PAUSE || action == ACTION_RESUME) ctx.startService(i)
            else ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var locationManager: LocationManager
    private var acc = StatsAccumulator()
    private var segment = 0
    private var lastRecorded: PointEntity? = null
    private var ticker: Job? = null
    private var listening = false
    private var activity = com.tomcollins.tracklog.data.ActivityType.WALK

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a track is being recorded"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (goForeground()) startNew(intent.getStringExtra(EXTRA_ACTIVITY))
            ACTION_CONTINUE, null -> if (goForeground()) continueUnfinished()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean {
        if (!hasLocationPermission()) {
            stopSelf(); return false
        }
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(Recorder.state.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
            true
        } catch (e: Exception) {
            // e.g. restarted from the background on newer Android; the track stays "unfinished"
            // and the app offers to continue it next time it's opened.
            stopSelf()
            false
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startNew(activityName: String?) {
        if (Recorder.state.value.active) return
        lifecycleScope.launch {
            val repo = app.repository
            // Never leave a previous unfinished recording dangling.
            withContext(io) { repo.dao.unfinishedTrack()?.let { repo.finish(it.id, it.totalTimeMs) } }
            activity = com.tomcollins.tracklog.data.ActivityType.of(activityName ?: app.settings.value.defaultActivity.name)
            val id = withContext(io) { repo.createTrack(activity) }
            acc = StatsAccumulator()
            segment = 0
            lastRecorded = null
            Recorder.mutable.value = LiveState(
                status = RecStatus.RECORDING, trackId = id,
                runningSince = SystemClock.elapsedRealtime(), segments = listOf(emptyList()),
            )
            startListening()
            startTicker()
        }
    }

    private fun continueUnfinished() {
        if (Recorder.state.value.active) return
        lifecycleScope.launch {
            val repo = app.repository
            val track = withContext(io) { repo.dao.unfinishedTrack() }
            if (track == null) {
                stopForegroundAndSelf(); return@launch
            }
            val points = withContext(io) { repo.points(track.id) }
            activity = track.activityType
            acc = StatsAccumulator()
            points.forEach { acc.add(it) }
            segment = (points.maxOfOrNull { it.segment } ?: -1) + 1
            lastRecorded = null
            val segs = points.groupBy { it.segment }.toSortedMap().values.map { s -> s.map { LatLon(it.lat, it.lon) } }
            Recorder.mutable.value = LiveState(
                status = RecStatus.RECORDING, trackId = track.id, stats = acc.snapshot(),
                elapsedBaseMs = track.totalTimeMs, runningSince = SystemClock.elapsedRealtime(),
                segments = segs + listOf(emptyList()),
            )
            startListening()
            startTicker()
        }
    }

    private fun pause() {
        val s = Recorder.state.value
        if (s.status != RecStatus.RECORDING) return
        stopListening()
        Recorder.mutable.value = s.copy(status = RecStatus.PAUSED, elapsedBaseMs = s.elapsedMs(), runningSince = null)
        saveSummary()
        updateNotification()
    }

    private fun resume() {
        val s = Recorder.state.value
        if (s.status != RecStatus.PAUSED) return
        segment++
        lastRecorded = null
        Recorder.mutable.value = s.copy(
            status = RecStatus.RECORDING, runningSince = SystemClock.elapsedRealtime(),
            segments = s.segments + listOf(emptyList()),
        )
        startListening()
        updateNotification()
    }

    private fun stop() {
        val s = Recorder.state.value
        stopListening()
        ticker?.cancel()
        val id = s.trackId
        val elapsed = s.elapsedMs()
        Recorder.mutable.value = LiveState()
        lifecycleScope.launch {
            if (id != null) withContext(io) { app.repository.finish(id, elapsed) }
            stopForegroundAndSelf()
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listening || !hasLocationPermission()) return
        val interval = app.settings.value.gpsIntervalMs
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, 0f, listener, Looper.getMainLooper())
        listening = true
    }

    private fun stopListening() {
        if (!listening) return
        locationManager.removeUpdates(listener)
        listening = false
    }

    private fun onLocation(loc: Location) {
        val s = Recorder.state.value
        if (s.status != RecStatus.RECORDING) return
        val settings = app.settings.value
        if (!loc.hasAccuracy() || loc.accuracy > settings.maxAccuracyM) {
            Recorder.mutable.value = s.copy(lastLocation = loc) // show weak fix, don't record it
            return
        }
        val prev = lastRecorded
        if (prev != null) {
            val d = Geo.distance(prev.lat, prev.lon, loc.latitude, loc.longitude)
            // Skip jitter while standing still (movement smaller than the GPS uncertainty),
            // but keep a heartbeat point every 30 s.
            val threshold = maxOf(settings.minDistanceM.toFloat(), loc.accuracy / 2f)
            if (d < threshold && loc.time - prev.time < 30_000) {
                Recorder.mutable.value = s.copy(lastLocation = loc)
                return
            }
            // Reject GPS glitches: a jump bigger than the fix's uncertainty at an impossible speed.
            val dt = (loc.time - prev.time) / 1000.0
            if (dt > 0 && d > loc.accuracy && d / dt > activity.maxPlausibleMps) {
                Recorder.mutable.value = s.copy(lastLocation = loc)
                return
            }
        }
        val trackId = s.trackId ?: return
        val point = PointEntity(
            trackId = trackId,
            time = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            alt = if (loc.hasAltitude()) loc.altitude else null,
            accuracy = loc.accuracy,
            speed = if (loc.hasSpeed()) loc.speed else null,
            segment = segment,
        )
        lastRecorded = point
        acc.add(point)
        val stats = acc.snapshot()
        val segs = s.segments.toMutableList()
        if (segs.isEmpty()) segs += emptyList<LatLon>()
        segs[segs.lastIndex] = segs.last() + LatLon(point.lat, point.lon)
        Recorder.mutable.value = s.copy(stats = stats, lastLocation = loc, segments = segs)

        val elapsed = s.elapsedMs()
        lifecycleScope.launch(io) {
            val repo = app.repository
            repo.dao.insertPoint(point)
            repo.dao.getTrack(trackId)?.let { repo.dao.updateTrack(it.withStats(stats, elapsed)) }
        }
    }

    private fun saveSummary() {
        val s = Recorder.state.value
        val id = s.trackId ?: return
        val elapsed = s.elapsedMs()
        lifecycleScope.launch(io) {
            val repo = app.repository
            repo.dao.getTrack(id)?.let { repo.dao.updateTrack(it.copy(totalTimeMs = elapsed)) }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            var n = 0
            while (isActive) {
                delay(5_000)
                updateNotification()
                if (++n % 6 == 0) saveSummary() // persist elapsed time every 30 s
            }
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Recorder.state.value.active) nm.notify(NOTIFICATION_ID, buildNotification(Recorder.state.value))
    }

    private fun buildNotification(s: LiveState): Notification {
        val units = app.settings.value.units
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, RecordingService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
        )
        val paused = s.status == RecStatus.PAUSED
        val title = if (paused) "Recording paused" else "Recording track"
        val text = "${Format.distance(s.stats.distanceM, units)} · ${Format.duration(s.elapsedMs())}"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (paused) "Resume" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Stop", action(ACTION_STOP, 2))
            .build()
    }

    override fun onDestroy() {
        stopListening()
        ticker?.cancel()
        io.close()
        super.onDestroy()
    }
}
