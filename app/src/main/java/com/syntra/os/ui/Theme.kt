package com.syntra.os.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Warm, cinematic dark system around clay #D97757 (UI/UX Pro Max: dark, minimal, clear hierarchy, 4.5:1 text contrast).
val Bg = Color(0xFF141110)
val Panel = Color(0xFF1E1A17)
val Raised = Color(0xFF29231F)
val Line = Color(0xFF3A322C)
val Clay = Color(0xFFD97757)
val ClayDeep = Color(0xFFB85C3E)
val ClaySoft = Color(0xFFF0B49B)
val Ink = Color(0xFFF4EEE8)
val Dim = Color(0xFFA89D94) // 6.9:1 on Bg
val Danger = Color(0xFFEF6F6C)
val Ok = Color(0xFF7FBF8E)

// Back-compat names used across screens.
val Cyan = Clay
val Violet = ClaySoft

/** Community colours for the memory graph: warm-first, still distinguishable. */
val Palette = listOf(
    Color(0xFFD97757), Color(0xFFE8B04B), Color(0xFF6FB3A8), Color(0xFF9DBF6A), Color(0xFFC98BB9),
    Color(0xFF7F9CD6), Color(0xFFE48F6E), Color(0xFFB5A27A), Color(0xFF8FC1D9), Color(0xFFD9A5A0),
)

private val type = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

@Composable
fun SyntraTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Clay, onPrimary = Color(0xFF1A0F0A), primaryContainer = ClayDeep, secondary = ClaySoft,
        background = Bg, onBackground = Ink, surface = Panel, onSurface = Ink, surfaceVariant = Raised,
        onSurfaceVariant = Dim, error = Danger, outline = Line, outlineVariant = Line,
        surfaceContainer = Panel, surfaceContainerHigh = Raised, secondaryContainer = Raised, onSecondaryContainer = Clay,
    ),
    typography = type,
    content = content,
)
