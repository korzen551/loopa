package com.loopa.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Violet = Color(0xFF7C5CFF)
private val VioletSoft = Color(0xFFCFC2FF)
private val Ink = Color(0xFF12101A)
private val InkRaised = Color(0xFF1C1929)
private val Mint = Color(0xFF4ADE9B)

private val Dark = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A2E6B),
    onPrimaryContainer = VioletSoft,
    secondary = Mint,
    onSecondary = Ink,
    background = Ink,
    onBackground = Color(0xFFEDEAF6),
    surface = Ink,
    onSurface = Color(0xFFEDEAF6),
    surfaceVariant = InkRaised,
    onSurfaceVariant = Color(0xFFB4ADC9),
    outline = Color(0xFF4A4460),
)

private val Light = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E0FF),
    onPrimaryContainer = Color(0xFF241356),
    secondary = Color(0xFF12855A),
    background = Color(0xFFFBF9FF),
    surface = Color(0xFFFBF9FF),
    surfaceVariant = Color(0xFFE9E4F5),
    onSurfaceVariant = Color(0xFF494356),
)

@Composable
fun LoopaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        content = content,
    )
}
