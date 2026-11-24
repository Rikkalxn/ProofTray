package org.openbridge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.openbridge.app.domain.ToneCurve

@Composable
internal fun CurveColorControls(curves: List<ToneCurve>, onChange: (List<ToneCurve>) -> Unit) {
    var channel by rememberSaveable { mutableStateOf(0) }
    var selected by rememberSaveable(channel) { mutableStateOf(0) }
    val curve = curves[channel]
    val active = selected.coerceIn(curve.points.indices)
    val gesture = LocalColorGesture.current
    val current by rememberUpdatedState(curve)
    val change by rememberUpdatedState<(ToneCurve) -> Unit> { next ->
        onChange(curves.mapIndexed { index, old -> if (index == channel) next else old })
    }
    val hitRadius = with(LocalDensity.current) { 24.dp.toPx() }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = listOf(MaterialTheme.colorScheme.primary, Color(0xFFE57373), Color(0xFF66BB6A), Color(0xFF64B5F6))[channel]
    DisposableEffect(Unit) { onDispose { gesture(false) } }
    Column {
        ChoiceChips(listOf("RGB", "红", "绿", "蓝"), channel,
            listOf(MaterialTheme.colorScheme.onSurface, Color(0xFFE57373), Color(0xFF66BB6A), Color(0xFF64B5F6))) { channel = it }
        Text("点击加点，拖动调整 · ${curve.points.size}/16 点", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp).height(200.dp)
            .pointerInput(channel) {
                var draggingCurve = current
                fun select(point: Offset, insert: Boolean) {
                    draggingCurve = current
                    val closest = current.points.indices.minBy { i ->
                        (Offset(current.points[i].x * size.width, (1 - current.points[i].y) * size.height) - point).getDistance()
                    }
                    val distance = (Offset(current.points[closest].x * size.width, (1 - current.points[closest].y) * size.height) - point)
                        .getDistance()
                    if (distance < hitRadius || !insert) selected = closest else {
                        val x = (point.x / size.width).coerceIn(0f, 1f)
                        val next = current.insert(x, 1 - point.y / size.height)
                        draggingCurve = next
                        selected = next.points.indices.minBy { kotlin.math.abs(next.points[it].x - x) }
                        change(next)
                    }
                }
                detectDragGestures(onDragStart = { gesture(true); select(it, true) }, onDragEnd = { gesture(false) },
                    onDragCancel = { gesture(false) }) { event, _ ->
                    event.consume()
                    draggingCurve = draggingCurve.move(selected, event.position.x / size.width, 1 - event.position.y / size.height)
                    change(draggingCurve)
                }
            }.pointerInput(channel) {
                detectTapGestures { point ->
                    val closest = current.points.indices.minBy { i ->
                        (Offset(current.points[i].x * size.width, (1 - current.points[i].y) * size.height) - point).getDistance()
                    }
                    val location = Offset(current.points[closest].x * size.width, (1 - current.points[closest].y) * size.height)
                    if ((point - location).getDistance() < hitRadius) selected = closest else {
                        gesture(true)
                        val x = (point.x / size.width).coerceIn(0f, 1f)
                        val next = current.insert(x, 1 - point.y / size.height)
                        selected = next.points.indices.minBy { kotlin.math.abs(next.points[it].x - x) }
                        change(next)
                        gesture(false)
                    }
                }
            }) {
            drawRect(background)
            for (i in 0..4) {
                val x = size.width * i / 4; val y = size.height * i / 4
                drawLine(grid, Offset(x, 0f), Offset(x, size.height))
                drawLine(grid, Offset(0f, y), Offset(size.width, y))
            }
            drawLine(grid, Offset(0f, size.height), Offset(size.width, 0f))
            val path = Path()
            for (i in 0..128) {
                val x = i / 128f; val y = 1 - curve.evaluate(x)
                if (i == 0) path.moveTo(x * size.width, y * size.height) else path.lineTo(x * size.width, y * size.height)
            }
            drawPath(path, ink, style = Stroke(2.dp.toPx()))
            curve.points.forEachIndexed { i, p ->
                val point = Offset(p.x * size.width, (1 - p.y) * size.height)
                drawCircle(ink, if (i == active) 7.dp.toPx() else 5.dp.toPx(), point)
                drawCircle(background, 2.dp.toPx(), point)
            }
        }
        val point = curve.points[active]
        if (active > 0 && active < curve.points.lastIndex) EditorSlider("输入", point.x, 0f..1f,
            "${(point.x * 100).roundToInt()}", onReset = null) { change(curve.move(active, it, point.y)) }
        EditorSlider("输出", point.y, 0f..1f, "${(point.y * 100).roundToInt()}", onReset = { change(curve.move(active, point.x, point.x)) }) {
            change(curve.move(active, point.x, it))
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { change(curve.remove(active)); selected = 0 }, enabled = active > 0 && active < curve.points.lastIndex) {
                Text("删除控制点")
            }
            TextButton(onClick = { change(ToneCurve()); selected = 0 }) { Text("重置当前曲线") }
        }
    }
}
