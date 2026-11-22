package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.ActualInferenceSource
import com.example.ui.theme.NexusBlue
import com.example.ui.theme.NexusBorder
import com.example.ui.theme.NexusCyan
import com.example.ui.theme.NexusEmerald
import com.example.ui.theme.NexusEmeraldLight
import com.example.ui.theme.NexusError
import com.example.ui.theme.NexusObsidian
import com.example.ui.theme.NexusOnlineGreen
import com.example.ui.theme.NexusSurface
import com.example.ui.theme.NexusSurfaceElevated
import com.example.ui.theme.NexusTextMuted
import com.example.ui.theme.NexusTextPrimary
import com.example.ui.theme.NexusTextSecondary
import com.example.ui.theme.NexusViolet
import com.example.ui.theme.NexusVioletLight
import com.example.ui.theme.NexusWarning
import com.google.firebase.Timestamp

/**
 * Faceted 4-point electric violet & cyan NEXUS AI emblem matching the reference UI.
 */
@Composable
fun NexusStarLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val outer = w * 0.48f
        val inner = w * 0.16f

        val starPath = Path().apply {
            moveTo(cx, cy - outer)
            lineTo(cx + inner, cy - inner)
            lineTo(cx + outer, cy)
            lineTo(cx + inner, cy + inner)
            lineTo(cx, cy + outer)
            lineTo(cx - inner, cy + inner)
            lineTo(cx - outer, cy)
            lineTo(cx - inner, cy - inner)
            close()
        }
        drawPath(
            path = starPath,
            brush = Brush.linearGradient(
                colors = listOf(NexusVioletLight, NexusViolet, NexusCyan),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
        )

        val upperFacet = Path().apply {
            moveTo(cx, cy - outer)
            lineTo(cx + inner, cy - inner)
            lineTo(cx, cy)
            lineTo(cx - inner, cy - inner)
            close()
        }
        drawPath(path = upperFacet, color = Color.White.copy(alpha = 0.28f))

        val coreDiamond = Path().apply {
            moveTo(cx, cy - inner * 0.65f)
            lineTo(cx + inner * 0.65f, cy)
            lineTo(cx, cy + inner * 0.65f)
            lineTo(cx - inner * 0.65f, cy)
            close()
        }
        drawPath(path = coreDiamond, color = NexusTextPrimary.copy(alpha = 0.92f))
    }
}

/**
 * Atmospheric near-black obsidian backdrop with soft electric violet and cyan aurora washes.
 */
@Composable
fun NexusAuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NexusObsidian)
            .drawBehind {
                // Top-left violet glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NexusViolet.copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.08f, size.height * 0.06f),
                        radius = size.maxDimension * 0.45f
                    ),
                    radius = size.maxDimension * 0.45f,
                    center = Offset(size.width * 0.08f, size.height * 0.06f)
                )
                // Right-side cyan atmospheric aura
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NexusCyan.copy(alpha = 0.12f),
                            NexusBlue.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.94f, size.height * 0.52f),
                        radius = size.maxDimension * 0.52f
                    ),
                    radius = size.maxDimension * 0.52f,
                    center = Offset(size.width * 0.94f, size.height * 0.52f)
                )
                // Bottom-left violet plasma ribbon glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NexusViolet.copy(alpha = 0.14f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.12f, size.height * 0.96f),
                        radius = size.maxDimension * 0.40f
                    ),
                    radius = size.maxDimension * 0.40f,
                    center = Offset(size.width * 0.12f, size.height * 0.96f)
                )
            }
    ) {
        content()
    }
}

/**
 * Hero quick-action card matching the 4 glowing gradient cards on the NEXUS AI dashboard.
 */
@Composable
fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    testTagName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .testTag(testTagName)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.24f),
                        NexusSurface,
                        NexusSurfaceElevated.copy(alpha = 0.9f),
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.45f),
                        NexusBorder.copy(alpha = 0.65f)
                    )
                ),
                shape = shape
            )
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.20f))
                    .border(1.dp, accentColor.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NexusTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NexusTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.18f))
                        .border(1.dp, accentColor.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Open $title",
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Animated glowing iridescent violet-cyan orb for the AI Activity card.
 */
@Composable
fun AiActivityOrb(
    isBusy: Boolean,
    lastSource: ActualInferenceSource?,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
) {
    val transition = rememberInfiniteTransition(label = "ai_orb")
    val pulse by transition.animateFloat(
        initialValue = 0.90f,
        targetValue = if (isBusy) 1.12f else 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isBusy) 900 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )

    val coreStart = when (lastSource) {
        ActualInferenceSource.ON_DEVICE -> NexusEmeraldLight
        ActualInferenceSource.IN_CLOUD -> NexusVioletLight
        else -> NexusVioletLight
    }
    val coreEnd = when (lastSource) {
        ActualInferenceSource.ON_DEVICE -> NexusCyan
        ActualInferenceSource.IN_CLOUD -> NexusCyan
        else -> NexusBlue
    }

    Canvas(modifier = modifier.size(size)) {
        val radius = (this.size.minDimension / 2f) * 0.70f * pulse
        val center = Offset(this.size.width / 2f, this.size.height / 2f)

        // Outer soft halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    coreStart.copy(alpha = 0.42f),
                    coreEnd.copy(alpha = 0.16f),
                    Color.Transparent
                ),
                center = center,
                radius = this.size.minDimension / 2f
            ),
            radius = this.size.minDimension / 2f,
            center = center
        )

        // Main iridescent sphere
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(coreStart, NexusViolet, coreEnd),
                start = Offset(center.x - radius, center.y - radius),
                end = Offset(center.x + radius, center.y + radius)
            ),
            radius = radius,
            center = center
        )

        // Specular top-left highlight
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.45f),
                    Color.Transparent
                ),
                center = Offset(center.x - radius * 0.30f, center.y - radius * 0.32f),
                radius = radius * 0.55f
            ),
            radius = radius * 0.55f,
            center = Offset(center.x - radius * 0.30f, center.y - radius * 0.32f)
        )
    }
}

/**
 * Smooth cubic Bezier sparkline chart rendered via Canvas (uses DrawScope size dynamically).
 */
@Composable
fun SparklineCanvas(
    values: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    val safeValues = if (values.size >= 2) values else listOf(0.25f, 0.35f, 0.30f, 0.50f, 0.45f, 0.65f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val minV = safeValues.minOrNull() ?: 0f
        val maxV = (safeValues.maxOrNull() ?: 1f).let { if (it <= minV) minV + 1f else it }
        val stepX = w / (safeValues.size - 1).coerceAtLeast(1)

        val points = safeValues.mapIndexed { idx, v ->
            val norm = ((v - minV) / (maxV - minV)).coerceIn(0f, 1f)
            Offset(
                x = idx * stepX,
                y = h - (norm * (h * 0.78f)) - (h * 0.11f)
            )
        }

        val strokePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 0 until points.lastIndex) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val midX = (p0.x + p1.x) / 2f
                cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
            }
        }

        val fillPath = Path().apply {
            addPath(strokePath)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.24f), Color.Transparent)
            )
        )
        drawPath(
            path = strokePath,
            color = lineColor,
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Colored category badge matching the reference dashboard ("Ideas", "Notes", "Plans", etc.).
 */
@Composable
fun CategoryPill(
    category: String,
    modifier: Modifier = Modifier,
) {
    val accent = when (category.lowercase()) {
        "ideas" -> NexusVioletLight
        "notes" -> NexusBlue
        "plans" -> NexusEmeraldLight
        "research" -> NexusCyan
        "links" -> NexusEmerald
        "docs" -> NexusViolet
        else -> NexusTextSecondary
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Text(
            text = category,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Truthful execution-source pill displaying the real Firebase AI Logic InferenceSource.
 */
@Composable
fun InferenceSourceBadge(
    source: ActualInferenceSource,
    modelName: String? = null,
    latencyMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    val (dotColor, label) = when (source) {
        ActualInferenceSource.ON_DEVICE -> NexusOnlineGreen to "On-Device • Gemini Nano"
        ActualInferenceSource.IN_CLOUD -> NexusCyan to buildString {
            append("Cloud • ")
            append(modelName?.takeIf { it.isNotBlank() } ?: "Firebase AI Logic")
        }
        ActualInferenceSource.UNKNOWN -> NexusWarning to "Source Unreported"
    }

    val fullLabel = if (latencyMs != null && latencyMs > 0L) {
        "$label • ${latencyMs}ms"
    } else {
        label
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = dotColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = fullLabel,
                style = MaterialTheme.typography.labelSmall,
                color = dotColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Standard NEXUS AI dark charcoal card container with hairline border.
 */
@Composable
fun NexusCard(
    modifier: Modifier = Modifier,
    borderColor: Color = NexusBorder,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = NexusSurface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, borderColor)
    ) {
        content()
    }
}

@Composable
fun StatusPill(
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = NexusSurfaceElevated,
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = NexusTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun formatRelativeTimestamp(timestamp: Timestamp?): String {
    if (timestamp == null) return "Just now"
    val diffSec = ((System.currentTimeMillis() / 1000L) - timestamp.seconds).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "Just now"
        diffSec < 3600L -> "${diffSec / 60L}m ago"
        diffSec < 86_400L -> "${diffSec / 3600L}h ago"
        diffSec < 604_800L -> "${diffSec / 86_400L}d ago"
        else -> "${diffSec / 604_800L}w ago"
    }
}
