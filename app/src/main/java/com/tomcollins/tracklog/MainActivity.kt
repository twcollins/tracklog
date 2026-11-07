package com.tomcollins.tracklog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tomcollins.tracklog.ui.TrackLogRoot
import com.tomcollins.tracklog.ui.TrackLogTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TrackLogTheme { TrackLogRoot() } }
    }
}
