package com.tomcollins.tracklog

import android.app.Application
import android.content.Context
import com.tomcollins.tracklog.data.AppDatabase
import com.tomcollins.tracklog.data.Repository
import com.tomcollins.tracklog.data.SettingsStore

class TrackLogApp : Application() {
    val database by lazy { AppDatabase.create(this) }
    val repository by lazy { Repository(this, database) }
    val settings by lazy { SettingsStore(this) }
}

val Context.app: TrackLogApp get() = applicationContext as TrackLogApp
