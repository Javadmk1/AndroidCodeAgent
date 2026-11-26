package com.codeagent.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AgentColors = darkColorScheme(
    primary = Color(0xFF8B5CF6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF38216F),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFD6B56D),
    onSecondary = Color(0xFF241A08),
    secondaryContainer = Color(0xFF4B3B1D),
    onSecondaryContainer = Color(0xFFF5E5BD),
    background = Color(0xFF0B0D18),
    onBackground = Color(0xFFE9E7F2),
    surface = Color(0xFF121525),
    onSurface = Color(0xFFE9E7F2),
    surfaceVariant = Color(0xFF20243A),
    onSurfaceVariant = Color(0xFFC1C4D8),
    outline = Color(0xFF555B78),
    error = Color(0xFFFF6B78)
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AgentColors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
