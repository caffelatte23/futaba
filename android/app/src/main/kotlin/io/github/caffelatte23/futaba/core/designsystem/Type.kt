package io.github.caffelatte23.futaba.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import io.github.caffelatte23.futaba.R

private val provider =
    GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.futaba_fonts_certs,
    )

private fun family(
    name: String,
    vararg weights: FontWeight,
): FontFamily {
    val font = GoogleFont(name)
    return FontFamily(weights.map { Font(googleFont = font, fontProvider = provider, weight = it) })
}

val FutabaSans = family("IBM Plex Sans JP", FontWeight.Normal, FontWeight.Medium, FontWeight.Bold)

/** For numbers and times. */
val FutabaMono = family("IBM Plex Mono", FontWeight.Normal, FontWeight.Medium)

private fun TextStyle.sans() = copy(fontFamily = FutabaSans)

internal val FutabaTypography =
    Typography().let {
        it.copy(
            displayLarge = it.displayLarge.sans(),
            displayMedium = it.displayMedium.sans(),
            displaySmall = it.displaySmall.sans(),
            headlineLarge = it.headlineLarge.sans(),
            headlineMedium = it.headlineMedium.sans(),
            headlineSmall = it.headlineSmall.sans(),
            titleLarge = it.titleLarge.sans(),
            titleMedium = it.titleMedium.sans(),
            titleSmall = it.titleSmall.sans(),
            bodyLarge = it.bodyLarge.sans(),
            bodyMedium = it.bodyMedium.sans(),
            bodySmall = it.bodySmall.sans(),
            labelLarge = it.labelLarge.sans(),
            labelMedium = it.labelMedium.sans(),
            labelSmall = it.labelSmall.sans(),
        )
    }
