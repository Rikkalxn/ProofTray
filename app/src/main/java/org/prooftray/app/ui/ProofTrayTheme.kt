package org.prooftray.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val ink = Color(0xFF14161B)
private val paper = Color(0xFFF4EFE6)
private val graphite = Color(0xFF2E323C)
private val lightAccent = Color(0xFFA63820)
private val darkAccent = Color(0xFFFF795A)

private val lightPalette = lightColorScheme(
    primary = lightAccent, onPrimary = Color.White, primaryContainer = Color(0xFFFF5A36), onPrimaryContainer = ink,
    secondary = Color(0xFF754936), onSecondary = Color.White, secondaryContainer = Color(0xFFF5DBCE), onSecondaryContainer = Color(0xFF45271C),
    tertiary = Color(0xFF505E71), onTertiary = Color.White, tertiaryContainer = Color(0xFFDCE3ED), onTertiaryContainer = Color(0xFF25303D),
    error = Color(0xFFA21D39), onError = Color.White, errorContainer = Color(0xFFFFDAE0), onErrorContainer = Color(0xFF570E20),
    background = paper, onBackground = ink, surface = paper, onSurface = ink, surfaceTint = lightAccent,
    surfaceVariant = Color(0xFFE6DFD5), onSurfaceVariant = Color(0xFF50535B), outline = Color(0xFF74716D), outlineVariant = Color(0xFFCCC6BC),
    surfaceDim = Color(0xFFDCD6CD), surfaceBright = Color(0xFFFCF9F3), surfaceContainerLowest = Color(0xFFFFFCF8),
    surfaceContainerLow = Color(0xFFF0EBE2), surfaceContainer = Color(0xFFEBE5DB),
    surfaceContainerHigh = Color(0xFFE6DFD5), surfaceContainerHighest = Color(0xFFDFD9D0),
    inverseSurface = graphite, inverseOnSurface = paper, inversePrimary = darkAccent,
    primaryFixed = Color(0xFFFFDBD0), primaryFixedDim = Color(0xFFFFB59E),
    onPrimaryFixed = Color(0xFF381004), onPrimaryFixedVariant = Color(0xFF812B15),
    secondaryFixed = Color(0xFFF5DBCE), secondaryFixedDim = Color(0xFFE5BCA8),
    onSecondaryFixed = Color(0xFF311A10), onSecondaryFixedVariant = Color(0xFF64402F),
    tertiaryFixed = Color(0xFFDCE3ED), tertiaryFixedDim = Color(0xFFBAC6D6),
    onTertiaryFixed = Color(0xFF19232F), onTertiaryFixedVariant = Color(0xFF3E4C5E)
)

private val darkPalette = lightPalette.copy(
    primary = darkAccent, onPrimary = ink, primaryContainer = Color(0xFF74321F), onPrimaryContainer = lightPalette.primaryFixed,
    secondary = lightPalette.secondaryFixedDim, onSecondary = Color(0xFF41291D),
    secondaryContainer = Color(0xFF4F382E), onSecondaryContainer = lightPalette.secondaryFixed,
    tertiary = lightPalette.tertiaryFixedDim, onTertiary = Color(0xFF243141),
    tertiaryContainer = Color(0xFF354354), onTertiaryContainer = lightPalette.tertiaryFixed,
    error = Color(0xFFFFB2C0), onError = Color(0xFF5A0E23), errorContainer = Color(0xFF722337), onErrorContainer = Color(0xFFFFDAE0),
    background = ink, onBackground = paper, surface = ink, onSurface = paper, surfaceTint = darkAccent,
    surfaceVariant = graphite, onSurfaceVariant = Color(0xFFC2C6CF), outline = Color(0xFF9198A4), outlineVariant = Color(0xFF474D58),
    surfaceDim = ink, surfaceBright = Color(0xFF333740), surfaceContainerLowest = Color(0xFF0F1115),
    surfaceContainerLow = Color(0xFF1B1E25), surfaceContainer = Color(0xFF21252D),
    surfaceContainerHigh = Color(0xFF282C35), surfaceContainerHighest = graphite,
    inverseSurface = paper, inverseOnSurface = ink, inversePrimary = lightAccent
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProofTrayTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val palette = if (dark) darkPalette else lightPalette
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
