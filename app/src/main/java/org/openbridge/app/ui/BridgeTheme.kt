package org.openbridge.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val lightPalette = lightColorScheme(
    primary = Color(0xFF65558F), onPrimary = Color.White, primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF210F47),
    secondary = Color(0xFF625B71), secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1E192B),
    tertiary = Color(0xFF426B5D), tertiaryContainer = Color(0xFFC4EBDC), onTertiaryContainer = Color(0xFF002117),
    background = Color(0xFFFEF7FF), surface = Color(0xFFFEF7FF), onSurface = Color(0xFF1D1B20), onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLow = Color(0xFFF7F2FA), surfaceContainer = Color(0xFFF3EDF7), surfaceContainerHigh = Color(0xFFECE6F0)
)

private val darkPalette = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72), primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFA8CFC0), tertiaryContainer = Color(0xFF294E40), onTertiaryContainer = Color(0xFFC4EBDC),
    background = Color(0xFF141218), surface = Color(0xFF141218), onSurface = Color(0xFFE6E0E9), onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLow = Color(0xFF1D1B20), surfaceContainer = Color(0xFF211F26), surfaceContainerHigh = Color(0xFF2B2930)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenBridgeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val palette = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkPalette else lightPalette
    SideEffect {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialExpressiveTheme(
        colorScheme = palette,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp)
        ),
        content = content
    )
}
