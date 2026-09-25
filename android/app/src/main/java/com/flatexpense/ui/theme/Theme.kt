package com.flatexpense.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A fixed palette, deliberately not Material You.
 *
 * Dynamic colour derives the scheme from the user's wallpaper, which would mean
 * five flatmates looking at five differently coloured versions of the same
 * ledger, and no screen whose appearance could be promised to anyone. For an
 * app about shared money, everybody seeing the same thing is worth more than
 * matching each person's home screen.
 *
 * Teal carries the app because it reads as calm and financial without being a
 * bank's navy; amber is kept for things awaiting a decision, and is never used
 * decoratively, so an amber element on screen always means "someone needs to
 * act". Money states have their own fixed colours in StatusColors, held apart
 * from the brand ramp so an approved green can never be mistaken for a themed
 * accent.
 */

// --- Brand ramp -------------------------------------------------------------
private val Teal10 = Color(0xFF00201C)
private val Teal20 = Color(0xFF00382F)
private val Teal30 = Color(0xFF005046)
private val Teal40 = Color(0xFF0F766E)
private val Teal80 = Color(0xFF5EEAD4)
private val Teal90 = Color(0xFFB2F5EA)
private val Teal95 = Color(0xFFDCFCF5)

private val Amber30 = Color(0xFF7C3F00)
private val Amber40 = Color(0xFFB45309)
private val Amber80 = Color(0xFFFCD34D)
private val Amber90 = Color(0xFFFEF3C7)

private val Slate30 = Color(0xFF334155)
private val Slate40 = Color(0xFF475569)
private val Slate80 = Color(0xFFCBD5E1)
private val Slate90 = Color(0xFFE2E8F0)

private val LightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,

    secondary = Slate40,
    onSecondary = Color.White,
    secondaryContainer = Slate90,
    onSecondaryContainer = Slate30,

    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber30,

    // A barely-tinted background with white cards on top: the tint is what
    // separates a card from the page without needing a border or a heavy
    // shadow on every surface.
    background = Color(0xFFF6F8F8),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE8EFEE),
    onSurfaceVariant = Color(0xFF5B6B69),
    outline = Color(0xFFA9B8B6),
    outlineVariant = Color(0xFFDCE5E4),

    // These have to be stated. Material only derives the roles it is not
    // given, and the ones it derives come from its own baseline purple — so
    // setting surface alone leaves every default Card and Menu sitting on a
    // faint lilac that has nothing to do with this palette.
    surfaceDim = Color(0xFFDDE4E3),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    // Kept near-white on purpose: the default Material Card draws on the
    // highest container, and the app's own AppCard is white. Leaving these
    // several steps darker made half the screens grey and half white for no
    // reason a user could see.
    surfaceContainer = Color(0xFFFCFDFD),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF2C3735),
    inverseOnSurface = Color(0xFFEFF3F2),
    surfaceTint = Teal40,
    scrim = Color(0xFF000000),

    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal10,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal95,

    secondary = Slate80,
    onSecondary = Slate30,
    secondaryContainer = Slate30,
    onSecondaryContainer = Slate90,

    tertiary = Amber80,
    onTertiary = Amber30,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,

    background = Color(0xFF0E1413),
    onBackground = Color(0xFFE3E6E5),
    surface = Color(0xFF161D1C),
    onSurface = Color(0xFFE3E6E5),
    surfaceVariant = Color(0xFF212A29),
    onSurfaceVariant = Color(0xFFA8B6B4),
    outline = Color(0xFF5B6B69),
    outlineVariant = Color(0xFF2C3735),

    surfaceDim = Color(0xFF0E1413),
    surfaceBright = Color(0xFF333B3A),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF161D1C),
    surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF242C2B),
    surfaceContainerHighest = Color(0xFF2F3736),
    inverseSurface = Color(0xFFE3E6E5),
    inverseOnSurface = Color(0xFF2C3735),
    surfaceTint = Teal80,
    scrim = Color(0xFF000000),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC)
)

/**
 * Rupee amounts are the reason this screen exists, so the display sizes are
 * tightened and weighted to carry a number rather than a headline of prose.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        ),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp
        ),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
    )
}

/** One radius scale, so nothing on screen has a corner that belongs elsewhere. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** The hero number on the dashboard. Kept here so only one place sets it. */
val MoneyHero: TextStyle = TextStyle(
    fontSize = 34.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-0.8).sp
)

@Composable
fun FlatExpenseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
