package com.suivialimentation.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val SageGreen = Color(0xFF386A55)
private val SageGreenDark = Color(0xFFA4D0B5)
private val WarmBackground = Color(0xFFFFFBF7)
private val DarkBackground = Color(0xFF111512)
private val CalorieOrange = Color(0xFFE9783D)
private val ProteinTeal = Color(0xFF177B72)

val NutritionCalories = CalorieOrange
val NutritionProteins = ProteinTeal

private val LightColors = lightColorScheme(
    primary = SageGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E8D9),
    onPrimaryContainer = Color(0xFF0C271C),
    secondary = ProteinTeal,
    secondaryContainer = Color(0xFFBCECE5),
    tertiary = CalorieOrange,
    tertiaryContainer = Color(0xFFFFDBCA),
    background = WarmBackground,
    surface = WarmBackground,
    surfaceVariant = Color(0xFFF1F0EA),
    outlineVariant = Color(0xFFD8DDD7),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = SageGreenDark,
    onPrimary = Color(0xFF0A3827),
    primaryContainer = Color(0xFF1F513D),
    secondary = Color(0xFF9DD0C8),
    tertiary = Color(0xFFFFB68E),
    background = DarkBackground,
    surface = DarkBackground,
    surfaceVariant = Color(0xFF242A26),
    outlineVariant = Color(0xFF3E4942),
)

private val AppShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SuiviAlimentationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.surface.toArgb()
            window.navigationBarColor = colors.surface.toArgb()
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
