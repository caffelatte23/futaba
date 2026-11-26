@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName") // file name fixed by the plan (Theme/Color/Type)

package io.github.caffelatte23.futaba.core.designsystem

import androidx.compose.ui.graphics.Color

/** The only place color literals may live (enforced by detekt ForbiddenMethodCall). */
object FutabaColors {
    val Accent = Color(0xFFFFC83D)

    val LightBackground = Color(0xFFF4F4F0)
    val LightText = Color(0xFF161616)
    val LightSecondaryText = Color(0xFF5C5C57)
    val LightCard = Color(0xFFFFFFFF)

    // Dark secondary text and card are not in the design yet (screen 07 only fixes background, text, accent).
    val DarkBackground = Color(0xFF161616)
    val DarkText = Color(0xFFF4F4F0)
    val DarkSecondaryText = Color(0xFFA8A8A0)
    val DarkCard = Color(0xFF222222)
}
