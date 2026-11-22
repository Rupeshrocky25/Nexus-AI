package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val NexusDarkColorScheme = darkColorScheme(
    primary = NexusViolet,
    onPrimary = NexusTextPrimary,
    primaryContainer = NexusVioletDeep,
    onPrimaryContainer = NexusVioletLight,
    secondary = NexusCyan,
    onSecondary = NexusObsidian,
    secondaryContainer = NexusCyanDeep,
    onSecondaryContainer = NexusTextPrimary,
    tertiary = NexusEmerald,
    onTertiary = NexusObsidian,
    tertiaryContainer = NexusEmeraldDeep,
    onTertiaryContainer = NexusEmeraldLight,
    background = NexusObsidian,
    onBackground = NexusTextPrimary,
    surface = NexusSurface,
    onSurface = NexusTextPrimary,
    surfaceVariant = NexusSurfaceElevated,
    onSurfaceVariant = NexusTextSecondary,
    surfaceContainerLowest = NexusObsidian,
    surfaceContainerLow = NexusSidebarBg,
    surfaceContainer = NexusSurface,
    surfaceContainerHigh = NexusSurfaceElevated,
    surfaceContainerHighest = NexusSurfaceVariant,
    outline = NexusBorder,
    outlineVariant = NexusBorderBright,
    error = NexusError,
    onError = NexusObsidian,
)

private val NexusShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NexusAiTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NexusDarkColorScheme,
        typography = NexusTypography,
        shapes = NexusShapes,
        content = content
    )
}
