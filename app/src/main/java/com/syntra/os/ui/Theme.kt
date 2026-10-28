package com.syntra.os.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF05080F)
val Panel = Color(0xFF0C1322)
val Cyan = Color(0xFF22D3EE)
val Violet = Color(0xFF8B5CF6)
val Dim = Color(0xFF7A8BA6)
val Danger = Color(0xFFF87171)
val Ok = Color(0xFF34D399)

/** Community colours, similar to Graphify's palette. */
val Palette = listOf(
    Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF2DD4BF), Color(0xFF22C55E), Color(0xFFFACC15),
    Color(0xFFC084FC), Color(0xFFF472B6), Color(0xFFA16207), Color(0xFFCBD5E1), Color(0xFF60A5FA),
)

@Composable
fun SyntraTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Cyan, onPrimary = Bg, secondary = Violet, background = Bg, surface = Panel,
        surfaceVariant = Color(0xFF131C30), onSurface = Color(0xFFE2E8F0), onSurfaceVariant = Dim, error = Danger,
        outline = Color(0xFF1E2A44),
    ),
    content = content,
)
