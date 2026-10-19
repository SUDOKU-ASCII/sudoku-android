@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.futaiii.sudodroid.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.futaiii.sudodroid.data.ProxyMode
import com.futaiii.sudodroid.net.RatePoint
import com.futaiii.sudodroid.net.TrafficSnapshot

/** Material window width classes, computed from the available width so it also works in split-screen. */
enum class WidthClass {
    Compact, Medium, Expanded;

    companion object {
        fun of(width: Dp): WidthClass = when {
            width < 600.dp -> Compact
            width < 840.dp -> Medium
            else -> Expanded
        }
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val border = if (highlighted) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.4f)) else null
    val inner: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = MaterialTheme.shapes.large,
            color = scheme.surfaceContainer,
            border = border,
            content = inner
        )
    } else {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large,
            color = scheme.surfaceContainer,
            border = border,
            content = inner
        )
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Round power control. Colour and glow animate only on state changes; nothing animates while idle
 * so the screen costs no frames when the user is just looking at it.
 */
@Composable
fun PowerButton(
    running: Boolean,
    connecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 160.dp
) {
    val scheme = MaterialTheme.colorScheme
    val accent by animateColorAsState(
        targetValue = if (running) scheme.tertiary else scheme.primary,
        animationSpec = tween(450),
        label = "powerAccent"
    )
    val glow by animateFloatAsState(
        targetValue = if (running) 0.30f else if (connecting) 0.18f else 0f,
        animationSpec = tween(600),
        label = "powerGlow"
    )
    Box(
        modifier = modifier
            .size(diameter * 1.35f)
            .drawBehind {
                if (glow > 0.01f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = glow), Color.Transparent),
                            center = center,
                            radius = size.minDimension / 2f
                        )
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = onClick,
            enabled = enabled && !connecting,
            modifier = Modifier.size(diameter),
            shape = CircleShape,
            color = accent,
            shadowElevation = 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = if (running) "Disconnect" else "Connect",
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(diameter * 0.36f)
                )
            }
        }
        if (connecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(diameter + 12.dp),
                color = scheme.onSurface,
                strokeWidth = 2.dp
            )
        }
    }
}

/** Inset selection tile: icon over label, filled when selected. */
@Composable
fun ChoiceTile(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) scheme.surfaceContainerHighest else scheme.surfaceContainerLow,
        tween(200),
        label = "tile"
    )
    val content = if (selected) scheme.primary else scheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = container
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        }
    }
}

private fun ProxyMode.icon(): ImageVector = when (this) {
    ProxyMode.PAC -> Icons.Rounded.Rule
    ProxyMode.GLOBAL -> Icons.Rounded.Public
    ProxyMode.DIRECT -> Icons.Rounded.AltRoute
}

@Composable
fun ProxyModeSelector(
    selected: ProxyMode,
    onSelect: (ProxyMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ProxyMode.entries.forEach { mode ->
            ChoiceTile(
                selected = selected == mode,
                icon = mode.icon(),
                label = mode.label,
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

fun ProxyMode.describe(): String = when (this) {
    ProxyMode.PAC -> "Domestic traffic goes direct, everything else is proxied, ads are blocked."
    ProxyMode.GLOBAL -> "Everything goes through the proxy."
    ProxyMode.DIRECT -> "Nothing goes through the proxy."
}

/** Download and upload throughput over the last minute, scaled to the busiest second. */
@Composable
fun Sparkline(
    history: List<RatePoint>,
    downColor: Color,
    upColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        }
        if (history.size < 2) return@Canvas
        val peak = maxOf(history.maxOf { maxOf(it.up, it.down) }, 16L * 1024L).toFloat()
        val step = w / (TrafficSnapshot.HISTORY_SIZE - 1)
        val startX = w - step * (history.size - 1)

        fun series(selector: (RatePoint) -> Long, color: Color, fill: Boolean) {
            val line = Path()
            history.forEachIndexed { i, p ->
                val x = startX + step * i
                val y = h - (selector(p) / peak) * (h * 0.92f) - 2.dp.toPx()
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            if (fill) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(w, h)
                    lineTo(startX, h)
                    close()
                }
                drawPath(
                    area,
                    Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), startY = 0f, endY = h)
                )
            }
            drawPath(
                line,
                color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        series({ it.up }, upColor, fill = false)
        series({ it.down }, downColor, fill = true)
    }
}

@Composable
fun Metric(label: String, value: String, valueColor: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).drawBehind { drawCircle(color) })
}

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))

@Composable
fun CenteredRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}
