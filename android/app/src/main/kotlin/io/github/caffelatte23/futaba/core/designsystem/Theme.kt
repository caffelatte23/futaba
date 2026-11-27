package io.github.caffelatte23.futaba.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.glance.material3.ColorProviders

private val LightScheme =
    lightColorScheme(
        primary = FutabaColors.Accent,
        onPrimary = FutabaColors.LightText,
        background = FutabaColors.LightBackground,
        onBackground = FutabaColors.LightText,
        surface = FutabaColors.LightCard,
        onSurface = FutabaColors.LightText,
        onSurfaceVariant = FutabaColors.LightSecondaryText,
    )

private val DarkScheme =
    darkColorScheme(
        primary = FutabaColors.Accent,
        onPrimary = FutabaColors.DarkBackground,
        background = FutabaColors.DarkBackground,
        onBackground = FutabaColors.DarkText,
        surface = FutabaColors.DarkCard,
        onSurface = FutabaColors.DarkText,
        onSurfaceVariant = FutabaColors.DarkSecondaryText,
    )

internal val FutabaWidgetColors = ColorProviders(light = LightScheme, dark = DarkScheme)

@Composable
fun FutabaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = FutabaTypography,
        content = content,
    )
}
