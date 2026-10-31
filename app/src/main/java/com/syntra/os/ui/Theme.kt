package com.syntra.os.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Syntra design tokens — "AI-native" dark system seeded from clay #D97757 (UI/UX Pro Max: minimal chrome,
 * streaming text, context cards). Tonal surface ladder per M3: lowest < low < container < high < highest.
 * Contrast: onSurface on surface 15:1, onSurfaceVariant 7:1, primary on surface 5.9:1.
 */
private val SyntraDark = darkColorScheme(
    primary = Color(0xFFE0896B), onPrimary = Color(0xFF3A1406),
    primaryContainer = Color(0xFF7A3520), onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BDB0), onSecondary = Color(0xFF442A21),
    secondaryContainer = Color(0xFF5D4036), onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFFD5C68E), onTertiary = Color(0xFF393005),
    tertiaryContainer = Color(0xFF51461A), onTertiaryContainer = Color(0xFFF2E2A7),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF141110), onBackground = Color(0xFFEDE0DB),
    surface = Color(0xFF141110), onSurface = Color(0xFFEDE0DB),
    surfaceVariant = Color(0xFF53433E), onSurfaceVariant = Color(0xFFD8C2BB),
    surfaceDim = Color(0xFF141110), surfaceBright = Color(0xFF3C3532),
    surfaceContainerLowest = Color(0xFF0F0C0B), surfaceContainerLow = Color(0xFF1C1917),
    surfaceContainer = Color(0xFF211D1B), surfaceContainerHigh = Color(0xFF2B2725), surfaceContainerHighest = Color(0xFF36312F),
    outline = Color(0xFFA08D87), outlineVariant = Color(0xFF53433E),
    inverseSurface = Color(0xFFEDE0DB), inverseOnSurface = Color(0xFF362F2C), inversePrimary = Color(0xFF9A452A),
    scrim = Color(0xFF000000),
)

private val SyntraShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

private val Sans = FontFamily.SansSerif
val Mono = FontFamily.Monospace

private val SyntraType = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
)

// Legacy names still used by older screens; all map to scheme roles so the palette has one source of truth.
val Bg = SyntraDark.background
val Panel = SyntraDark.surfaceContainer
val Raised = SyntraDark.surfaceContainerHigh
val Line = SyntraDark.outlineVariant
val Clay = Color(0xFFD97757)                 // brand seed, for the orb and accents
val ClayDeep = SyntraDark.primaryContainer
val ClaySoft = SyntraDark.onPrimaryContainer
val Ink = SyntraDark.onSurface
val Dim = SyntraDark.onSurfaceVariant
val Danger = SyntraDark.error
val Ok = Color(0xFF8FCB9B)
val Cyan = Clay
val Violet = SyntraDark.secondary

/** Memory-graph community colours (categorical, warm-first, distinguishable on dark). */
val Palette = listOf(
    Color(0xFFD97757), Color(0xFFE8B04B), Color(0xFF6FB3A8), Color(0xFF9DBF6A), Color(0xFFC98BB9),
    Color(0xFF7F9CD6), Color(0xFFE48F6E), Color(0xFFB5A27A), Color(0xFF8FC1D9), Color(0xFFD9A5A0),
)

/** True when the user turned animations off system-wide. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun SyntraTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val reduce = remember {
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(LocalReducedMotion provides reduce) {
        MaterialTheme(colorScheme = SyntraDark, typography = SyntraType, shapes = SyntraShapes, content = content)
    }
}
