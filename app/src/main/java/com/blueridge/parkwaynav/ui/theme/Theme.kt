package com.blueridge.parkwaynav.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.blueridge.parkwaynav.data.AppearanceMode

private val ParkwayGreen = Color(0xFF1B4332)
private val ParkwayGreenLight = Color(0xFF74C69D)
private val ParkwaySky = Color(0xFFB7E4C7)
private val ParkwayYellow = Color(0xFFFFD166)

private val DarkColors = darkColorScheme(
    primary = ParkwayGreenLight,
    onPrimary = Color(0xFF06281C),
    secondary = ParkwaySky,
    tertiary = ParkwayYellow,
    background = Color(0xFF0F1A14),
    surface = Color(0xFF142019),
    onBackground = Color(0xFFE6F2EA),
    onSurface = Color(0xFFE6F2EA)
)

private val LightColors = lightColorScheme(
    primary = ParkwayGreen,
    onPrimary = Color.White,
    secondary = ParkwayGreenLight,
    tertiary = Color(0xFFB07A00),
    background = Color(0xFFF6FBF7),
    surface = Color.White,
    onBackground = Color(0xFF12241B),
    onSurface = Color(0xFF12241B)
)

@Composable
fun BlueRidgeTheme(
    appearance: AppearanceMode,
    content: @Composable () -> Unit
) {
    val dark = when (appearance) {
        AppearanceMode.DARK -> true
        AppearanceMode.LIGHT -> false
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
