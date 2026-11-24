package org.prooftray.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal data class ColorBandSwatch(val name: String, val hue: Float) {
    val color: Color get() = Color.hsl(hue, .78f, .54f)
}

internal val hslSwatches = listOf(ColorBandSwatch("红", 0f), ColorBandSwatch("橙", 30f), ColorBandSwatch("黄", 60f),
    ColorBandSwatch("绿", 120f), ColorBandSwatch("青", 180f), ColorBandSwatch("蓝", 240f),
    ColorBandSwatch("紫", 270f), ColorBandSwatch("洋红", 300f))

@Composable
internal fun ColorToolTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).selectableGroup()) {
        listOf("LUT", "基础", "曲线", "HSL", "颜色分级").forEachIndexed { index, label ->
            val active = index == selected
            Column(Modifier.weight(1f).height(48.dp).selectable(active, role = Role.Tab, onClick = { onSelect(index) }),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                Text(label, Modifier.padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                Box(Modifier.size(24.dp, 3.dp).clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent))
            }
        }
    }
}

@Composable
internal fun ChoiceChips(labels: List<String>, selected: Int, colors: List<Color>? = null, onSelect: (Int) -> Unit) {
    val gesture = LocalColorGesture.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp).selectableGroup()) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Row(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(9.dp))
                .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
                .selectable(active, role = Role.Tab, onClick = { gesture(false); onSelect(index) }),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                colors?.getOrNull(index)?.let { Box(Modifier.size(8.dp).background(it, CircleShape)) }
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun HslBandSelector(selected: Int, modified: List<Boolean>, onSelect: (Int) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val gesture = LocalColorGesture.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).selectableGroup()) {
        hslSwatches.forEachIndexed { index, swatch ->
            val active = selected == index
            val edited = modified[index]
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .selectable(active, role = Role.RadioButton, onClick = { gesture(false); onSelect(index) })
                .semantics { contentDescription = "${swatch.name}色范围"; stateDescription = if (edited) "已调整" else "未调整" }
                .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(38.dp)) {
                    val radius = size.minDimension / 2 - 2.dp.toPx()
                    drawCircle(swatch.color, radius - 4.dp.toPx())
                    drawCircle(if (active) ink else outline, if (active) radius else radius - 4.dp.toPx(),
                        style = Stroke(if (active) 2.dp.toPx() else 1.dp.toPx()))
                    if (edited) drawCircle(ink, 2.dp.toPx(), Offset(size.width - 3.dp.toPx(), 3.dp.toPx()))
                }
                Text(swatch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                    color = if (active) ink else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String,
    onReset: (() -> Unit)?, onInfo: (() -> Unit)? = null, trackColors: List<Color>? = null, neutral: Float? = null, onChange: (Float) -> Unit
) {
    val gesture = LocalColorGesture.current
    var dragging by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (dragging) gesture(false) } }
    val scheme = MaterialTheme.colorScheme
    val anchor = neutral ?: 0f.takeIf { range.start < 0 && range.endInclusive > 0 }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            if (onInfo != null) IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) { AppIcon(AppGlyph.INFO, "曝光参考说明") }
            Text(display, Modifier.widthIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).background(scheme.surfaceContainerHigh)
                .padding(horizontal = 8.dp, vertical = 3.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Medium)
            IconButton(onClick = { onReset?.invoke() }, enabled = onReset != null, modifier = Modifier.size(32.dp)) {
                AppIcon(AppGlyph.REFRESH, "$label 复位", Modifier.size(17.dp))
            }
        }
        Slider(value = value, onValueChange = {
            dragging = true
            gesture(true)
            onChange(it)
        }, onValueChangeFinished = { dragging = false; gesture(false) }, valueRange = range,
            modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = label; stateDescription = display },
            thumb = {
                Canvas(Modifier.size(20.dp)) {
                    drawCircle(scheme.onSurface, size.minDimension / 2)
                    drawCircle(scheme.surface, size.minDimension / 2 - 2.dp.toPx())
                }
            }, track = { state ->
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    fun coordinate(v: Float): Float {
                        val fraction = ((v - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                        return (if (rtl) 1 - fraction else fraction) * size.width
                    }
                    if (trackColors != null) {
                        drawRoundRect(Brush.horizontalGradient(if (rtl) trackColors.reversed() else trackColors),
                            cornerRadius = CornerRadius(size.height / 2))
                    } else {
                        drawRoundRect(scheme.outlineVariant, cornerRadius = CornerRadius(size.height / 2))
                        drawLine(scheme.primary, Offset(coordinate(anchor ?: range.start), center.y), Offset(coordinate(state.value), center.y),
                            strokeWidth = size.height, cap = StrokeCap.Round)
                    }
                    anchor?.let {
                        val x = coordinate(it)
                        drawLine(scheme.surface, Offset(x, -2.dp.toPx()), Offset(x, size.height + 2.dp.toPx()), 3.dp.toPx())
                        drawLine(scheme.onSurfaceVariant, Offset(x, -2.dp.toPx()), Offset(x, size.height + 2.dp.toPx()), 1.dp.toPx())
                    }
                }
            })
    }
}
