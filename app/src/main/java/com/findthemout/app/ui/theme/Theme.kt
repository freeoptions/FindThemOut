package com.findthemout.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = MintLeaf,
    onPrimary = DeepForest,
    secondary = RiverStone,
    onSecondary = Mist,
    tertiary = Ember,
    background = Mist,
    onBackground = Slate,
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = Slate,
    surfaceVariant = SoftSand,
    onSurfaceVariant = Slate,
)

private val DarkColors = darkColorScheme(
    primary = MintLeaf,
    onPrimary = DeepForest,
    secondary = RiverStone,
    onSecondary = Mist,
    tertiary = Ember,
)

@Composable
fun FindThemOutTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
