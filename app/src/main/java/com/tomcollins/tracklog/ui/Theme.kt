package com.tomcollins.tracklog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TrackColor = Color(0xFFE8590C)
val StartColor = Color(0xFF2F9E44)
val EndColor = Color(0xFFC92A2A)
val MarkerColor = Color(0xFF1971C2)
val PhotoColor = Color(0xFF7048E8)

private val Light = lightColorScheme(
    primary = Color(0xFF1F5D4C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9E5D3),
    secondaryContainer = Color(0xFFDDEBE4),
    tertiary = TrackColor,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8ED1B6),
    primaryContainer = Color(0xFF1F4A3D),
    secondaryContainer = Color(0xFF2A3D36),
    tertiary = Color(0xFFFF922B),
)

@Composable
fun TrackLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
