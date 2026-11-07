package com.tomcollins.tracklog.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Units { METRIC, IMPERIAL }
enum class MapStyle(val label: String) { STANDARD("OpenStreetMap"), TOPO("OpenTopoMap (contours)"), SATELLITE("Satellite (Esri)") }

data class AppSettings(
    val units: Units = Units.METRIC,
    /** Off by default: no network traffic unless you turn maps on. */
    val mapEnabled: Boolean = false,
    val mapStyle: MapStyle = MapStyle.TOPO,
    val gpsIntervalMs: Long = 2000,
    val minDistanceM: Int = 3,
    val maxAccuracyM: Int = 30,
    val defaultActivity: ActivityType = ActivityType.WALK,
    val keepScreenOn: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun load() = AppSettings(
        units = runCatching { Units.valueOf(prefs.getString("units", null)!!) }.getOrDefault(Units.METRIC),
        mapEnabled = prefs.getBoolean("mapEnabled", false),
        mapStyle = runCatching { MapStyle.valueOf(prefs.getString("mapStyle", null)!!) }.getOrDefault(MapStyle.TOPO),
        gpsIntervalMs = prefs.getLong("gpsIntervalMs", 2000),
        minDistanceM = prefs.getInt("minDistanceM", 3),
        maxAccuracyM = prefs.getInt("maxAccuracyM", 30),
        defaultActivity = ActivityType.of(prefs.getString("defaultActivity", ActivityType.WALK.name)),
        keepScreenOn = prefs.getBoolean("keepScreenOn", false),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        prefs.edit()
            .putString("units", s.units.name)
            .putBoolean("mapEnabled", s.mapEnabled)
            .putString("mapStyle", s.mapStyle.name)
            .putLong("gpsIntervalMs", s.gpsIntervalMs)
            .putInt("minDistanceM", s.minDistanceM)
            .putInt("maxAccuracyM", s.maxAccuracyM)
            .putString("defaultActivity", s.defaultActivity.name)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .apply()
        _state.value = s
    }
}
