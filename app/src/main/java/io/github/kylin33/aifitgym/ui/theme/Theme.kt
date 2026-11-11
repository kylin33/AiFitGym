package io.github.kylin33.aifitgym.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = GreenContainerLight,
    onPrimaryContainer = Color(0xFF00281A),
    secondary = Orange40,
    onSecondary = Color.White,
    secondaryContainer = OrangeContainerLight,
    onSecondaryContainer = Color(0xFF3A1000),
    background = Paper,
    onBackground = Ink,
    surface = SurfaceLight,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDF1EA),
    onSurfaceVariant = InkMuted,
    outline = OutlineLight,
    outlineVariant = Color(0xFFEAF0E7),
)

private val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Color(0xFF00382A),
    primaryContainer = GreenContainerDark,
    onPrimaryContainer = Color(0xFFB9F0DA),
    secondary = Orange80,
    onSecondary = Color(0xFF4A1A00),
    secondaryContainer = OrangeContainerDark,
    onSecondaryContainer = Color(0xFFFFDBCB),
    background = Night,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = Color(0xFF222823),
    onSurfaceVariant = InkMutedDark,
    outline = OutlineDark,
    outlineVariant = Color(0xFF262C27),
)

@Composable
fun FitGymTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 系统栏颜色交给 enableEdgeToEdge，这里只需要保证图标明暗跟主题一致
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FitGymTypography,
        content = content,
    )
}
