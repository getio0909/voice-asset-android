package com.voiceasset.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF315E7D),
        secondary = Color(0xFF4E616E),
        tertiary = Color(0xFF65587A),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF9CCBEE),
        secondary = Color(0xFFB5CAD8),
        tertiary = Color(0xFFD0BFE7),
    )

@Composable
fun VoiceAssetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
