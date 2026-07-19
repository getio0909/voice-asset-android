package com.voiceasset.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF126A6A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFB7E4DE),
        onPrimaryContainer = Color(0xFF00201E),
        secondary = Color(0xFF53615F),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD6E5E1),
        onSecondaryContainer = Color(0xFF0F1C1A),
        tertiary = Color(0xFFB84E4E),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDAD7),
        onTertiaryContainer = Color(0xFF410006),
        background = Color(0xFFF5F8F7),
        onBackground = Color(0xFF171D1C),
        surface = Color(0xFFF5F8F7),
        onSurface = Color(0xFF171D1C),
        surfaceVariant = Color(0xFFDCE5E2),
        onSurfaceVariant = Color(0xFF3F4947),
        outline = Color(0xFF6F7976),
        outlineVariant = Color(0xFFBEC9C5),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF8AD5CC),
        onPrimary = Color(0xFF003735),
        primaryContainer = Color(0xFF00504D),
        onPrimaryContainer = Color(0xFFA6F1E8),
        secondary = Color(0xFFB9CCC8),
        onSecondary = Color(0xFF243331),
        secondaryContainer = Color(0xFF3A4A47),
        onSecondaryContainer = Color(0xFFD6E5E1),
        tertiary = Color(0xFFFFB3AE),
        onTertiary = Color(0xFF68000F),
        tertiaryContainer = Color(0xFF8D1720),
        onTertiaryContainer = Color(0xFFFFDAD7),
        background = Color(0xFF101615),
        onBackground = Color(0xFFE0E9E6),
        surface = Color(0xFF101615),
        onSurface = Color(0xFFE0E9E6),
        surfaceVariant = Color(0xFF3F4947),
        onSurfaceVariant = Color(0xFFBEC9C5),
        outline = Color(0xFF899390),
        outlineVariant = Color(0xFF3F4947),
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
