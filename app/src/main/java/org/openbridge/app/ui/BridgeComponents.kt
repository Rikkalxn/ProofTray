package org.openbridge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

enum class BridgeGlyph {
    PHOTOS, CAMERA, TASKS, ADD, FILTER, MORE, CLOSE, CHECK, REFRESH, STAR, BLUETOOTH, WIFI, DOWNLOAD, SHARE, BACK, FORWARD,
    SETTINGS, LOCATION, INFO, FRAME, LAYERS, EDIT, COPY, DELETE, UNDO, REDO, LOCK, VISIBLE, HIDDEN
}

@Composable
fun BridgeIcon(glyph: BridgeGlyph, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier.size(24.dp).semantics { if (description != null) contentDescription = description }) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x1, y1), Offset(x2, y2), 1.8f, StrokeCap.Round)
            fun path(vararg points: Float) {
                val shape = Path().apply {
                    moveTo(points[0], points[1])
                    for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
                }
                drawPath(shape, tint, style = stroke)
            }
            when (glyph) {
                BridgeGlyph.PHOTOS -> {
                    drawRoundRect(tint, Offset(3f, 4f), Size(18f, 16f), CornerRadius(3f), style = stroke)
                    drawCircle(tint, 1.5f, Offset(8f, 9f))
                    path(4f, 17f, 10f, 12f, 14f, 16f, 17f, 13f, 21f, 17f)
                }
                BridgeGlyph.CAMERA -> {
                    val shape = Path().apply {
                        moveTo(5f, 7f); lineTo(8f, 7f); lineTo(10f, 4f); lineTo(15f, 4f); lineTo(17f, 7f); lineTo(20f, 7f)
                        quadraticTo(22f, 7f, 22f, 9f); lineTo(22f, 18f); quadraticTo(22f, 20f, 20f, 20f)
                        lineTo(4f, 20f); quadraticTo(2f, 20f, 2f, 18f); lineTo(2f, 9f); quadraticTo(2f, 7f, 5f, 7f); close()
                    }
                    drawPath(shape, tint, style = stroke)
                    drawCircle(tint, 4f, Offset(12f, 13.5f), style = stroke)
                }
                BridgeGlyph.TASKS -> {
                    path(4f, 7f, 6f, 9f, 9f, 5f); line(12f, 7f, 21f, 7f)
                    path(4f, 16f, 6f, 18f, 9f, 14f); line(12f, 16f, 21f, 16f)
                }
                BridgeGlyph.ADD -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
                BridgeGlyph.FILTER -> {
                    line(3f, 6f, 21f, 6f); line(3f, 12f, 21f, 12f); line(3f, 18f, 21f, 18f)
                    drawCircle(tint, 2.4f, Offset(8f, 6f)); drawCircle(tint, 2.4f, Offset(16f, 12f)); drawCircle(tint, 2.4f, Offset(10f, 18f))
                }
                BridgeGlyph.MORE -> listOf(5f, 12f, 19f).forEach { drawCircle(tint, 1.7f, Offset(it, 12f)) }
                BridgeGlyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
                BridgeGlyph.CHECK -> path(4f, 12f, 10f, 18f, 21f, 6f)
                BridgeGlyph.BACK -> { path(14f, 5f, 7f, 12f, 14f, 19f); line(7f, 12f, 21f, 12f) }
                BridgeGlyph.FORWARD -> path(9f, 5f, 16f, 12f, 9f, 19f)
                BridgeGlyph.REFRESH -> {
                    val arrow = Path().apply {
                        arcTo(Rect(4f, 4f, 20f, 20f), 30f, 285f, true)
                        lineTo(20f, 8.7f)
                        moveTo(20f, 3.2f); lineTo(20f, 8.7f); lineTo(14.5f, 8.7f)
                    }
                    drawPath(arrow, tint, style = stroke)
                }
                BridgeGlyph.STAR -> {
                    val star = Path()
                    for (point in 0 until 10) {
                        val angle = Math.PI * point / 5 - Math.PI / 2
                        val radius = if (point % 2 == 0) 9f else 4.3f
                        val x = 12f + radius * cos(angle).toFloat(); val y = 12f + radius * sin(angle).toFloat()
                        if (point == 0) star.moveTo(x, y) else star.lineTo(x, y)
                    }
                    star.close(); drawPath(star, tint, style = stroke)
                }
                BridgeGlyph.BLUETOOTH -> path(6f, 6f, 17f, 17f, 12f, 22f, 12f, 2f, 17f, 7f, 6f, 18f)
                BridgeGlyph.WIFI -> {
                    drawArc(tint, 215f, 110f, false, Offset(1f, 4f), Size(22f, 22f), style = stroke)
                    drawArc(tint, 215f, 110f, false, Offset(6f, 9f), Size(12f, 12f), style = stroke)
                    drawCircle(tint, 1.8f, Offset(12f, 19f))
                }
                BridgeGlyph.DOWNLOAD -> { line(12f, 3f, 12f, 15f); path(7f, 11f, 12f, 16f, 17f, 11f); path(4f, 17f, 4f, 21f, 20f, 21f, 20f, 17f) }
                BridgeGlyph.SHARE -> { path(7f, 8f, 12f, 3f, 17f, 8f); line(12f, 3f, 12f, 15f); path(5f, 12f, 5f, 21f, 19f, 21f, 19f, 12f) }
                BridgeGlyph.SETTINGS -> {
                    drawCircle(tint, 6f, Offset(12f, 12f), style = stroke)
                    drawCircle(tint, 2f, Offset(12f, 12f), style = stroke)
                    for (step in 0..7) {
                        val angle = Math.PI * step / 4
                        val x = cos(angle).toFloat(); val y = sin(angle).toFloat()
                        line(12f + 7f * x, 12f + 7f * y, 12f + 10f * x, 12f + 10f * y)
                    }
                }
                BridgeGlyph.LOCATION -> {
                    drawCircle(tint, 7f, Offset(12f, 12f), style = stroke)
                    drawCircle(tint, 2.5f, Offset(12f, 12f))
                    line(12f, 1f, 12f, 5f); line(12f, 19f, 12f, 23f); line(1f, 12f, 5f, 12f); line(19f, 12f, 23f, 12f)
                }
                BridgeGlyph.INFO -> {
                    drawCircle(tint, 9f, Offset(12f, 12f), style = stroke); line(12f, 11f, 12f, 17f); drawCircle(tint, 1f, Offset(12f, 7f))
                }
                BridgeGlyph.FRAME -> {
                    drawRoundRect(tint, Offset(3f, 3f), Size(18f, 18f), CornerRadius(2f), style = stroke)
                    drawRect(tint, Offset(6f, 6f), Size(12f, 10f), style = stroke)
                    line(7f, 18.5f, 17f, 18.5f)
                }
                BridgeGlyph.LAYERS -> {
                    path(3f, 8f, 12f, 3f, 21f, 8f, 12f, 13f, 3f, 8f)
                    path(3f, 13f, 12f, 18f, 21f, 13f); path(3f, 17f, 12f, 22f, 21f, 17f)
                }
                BridgeGlyph.EDIT -> {
                    path(4f, 16f, 16f, 4f, 20f, 8f, 8f, 20f, 3f, 21f, 4f, 16f)
                    line(13f, 7f, 17f, 11f)
                }
                BridgeGlyph.COPY -> {
                    drawRoundRect(tint, Offset(7f, 7f), Size(14f, 14f), CornerRadius(2f), style = stroke)
                    path(17f, 4f, 17f, 3f, 3f, 3f, 3f, 17f, 4f, 17f)
                }
                BridgeGlyph.DELETE -> {
                    path(5f, 7f, 6f, 21f, 18f, 21f, 19f, 7f); line(3f, 6f, 21f, 6f)
                    path(8f, 6f, 8f, 3f, 16f, 3f, 16f, 6f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
                }
                BridgeGlyph.UNDO, BridgeGlyph.REDO -> {
                    withTransform({ if (glyph == BridgeGlyph.REDO) scale(-1f, 1f, pivot = Offset(12f, 12f)) }) {
                        val shape = Path().apply { moveTo(4f, 8f); lineTo(14f, 8f); cubicTo(23f, 8f, 23f, 20f, 14f, 20f) }
                        drawPath(shape, tint, style = stroke)
                        path(8f, 3f, 3f, 8f, 8f, 13f)
                    }
                }
                BridgeGlyph.LOCK -> {
                    drawRoundRect(tint, Offset(5f, 10f), Size(14f, 11f), CornerRadius(2f), style = stroke)
                    drawArc(tint, 180f, 180f, false, Offset(8f, 2f), Size(8f, 14f), style = stroke)
                    line(12f, 14f, 12f, 17f)
                }
                BridgeGlyph.VISIBLE, BridgeGlyph.HIDDEN -> {
                    val shape = Path().apply {
                        moveTo(2f, 12f); quadraticTo(12f, 0f, 22f, 12f); quadraticTo(12f, 24f, 2f, 12f)
                    }
                    drawPath(shape, tint, style = stroke); drawCircle(tint, 3f, Offset(12f, 12f), style = stroke)
                    if (glyph == BridgeGlyph.HIDDEN) line(3f, 3f, 21f, 21f)
                }
            }
        }
    }
}

@Composable
fun SectionCard(title: String, subtitle: String? = null, glyph: BridgeGlyph? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (glyph != null) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { BridgeIcon(glyph) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            content()
        }
    }
}

/** Failure reason with its recovery actions in the same place. */
@Composable
fun ErrorNotice(message: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun PrimaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, glyph: BridgeGlyph? = null) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp), shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.contentPaddingFor(56.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            glyph?.let { BridgeIcon(it) }
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeSheet(
    title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit, compact: Boolean = false,
    scrollState: ScrollState? = null, scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().then(if (compact) Modifier else Modifier.fillMaxHeight(0.9f)).imePadding()) {
            Text(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f, fill = !compact)
                .then(if (scrollable) Modifier.verticalScroll(scrollState ?: rememberScrollState()) else Modifier)
                .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
