package org.openbridge.app.ui

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import org.openbridge.app.domain.FrameLayerKind
import org.openbridge.app.domain.allLayers
import org.openbridge.app.frame.FRAME_UNIT
import org.openbridge.app.frame.FrameRendered

@Composable
internal fun FrameCanvasPreview(rendered: FrameRendered, editor: FrameEditorState, photoWidth: Int, photoHeight: Int,
    accent: Color, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val presetId = editor.template.id
    var zoom by remember(photoWidth, photoHeight, presetId) { mutableFloatStateOf(1f) }
    var pan by remember(photoWidth, photoHeight, presetId) { mutableStateOf(Offset.Zero) }
    val latest by rememberUpdatedState(rendered)
    val touch = with(LocalDensity.current) { 22.dp.toPx() }
    fun viewScale(): Float = min(size.width / latest.plan.geometry.canvas.width, size.height / latest.plan.geometry.canvas.height) * zoom
    fun origin(): Offset = Offset(size.width / 2f, size.height / 2f) + pan -
        Offset(latest.plan.geometry.canvas.width, latest.plan.geometry.canvas.height) * (viewScale() / 2f)
    fun toFrame(point: Offset): Offset = (point - origin()) / max(0.0001f, viewScale())

    Canvas(modifier.clipToBounds().onSizeChanged { size = it }.pointerInput(photoWidth, photoHeight, presetId) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val point = toFrame(down.position)
            val placements = latest.plan.placements
            val selected = placements.find { it.layer.id == editor.selectedId && !it.locked }
            val handle = selected?.let { Offset(it.matrix.x(it.width, it.height), it.matrix.y(it.width, it.height)) }
            val resizing = handle != null && (point - handle).getDistance() * viewScale() < touch
            val hit = if (resizing) selected else selected?.takeIf { it.hit(point.x, point.y, 0f) }
                ?: latest.plan.hit(point.x, point.y, touch / max(0.0001f, viewScale()))
            editor.selectedId = hit?.layer?.id
            val startLayer = editor.template.forPhoto(photoWidth, photoHeight).allLayers().find { it.id == hit?.layer?.id }
            val resizeOrigin = hit?.let { Offset(it.matrix.tx, it.matrix.ty) } ?: Offset.Zero
            val startDistance = max(1f, (point - resizeOrigin).getDistance())
            var multiTouch = false
            var moved = false
            editor.beginGesture()
            try {
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        multiTouch = true
                        val ratio = event.calculateZoom().takeIf { it.isFinite() && it > 0f } ?: 1f
                        val nextZoom = (zoom * ratio).coerceIn(1f, 4f)
                        val actual = nextZoom / zoom
                        val pivot = event.calculateCentroid() - Offset(size.width / 2f, size.height / 2f)
                        pan = (pan - pivot) * actual + pivot + event.calculatePan()
                        zoom = nextZoom
                        if (zoom == 1f) pan = Offset.Zero
                        event.changes.forEach { it.consume() }
                    } else if (!multiTouch && pressed.size == 1) {
                        val change = pressed.single()
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved && change.positionChanged()) {
                            if (hit != null && startLayer != null) {
                                if (resizing) {
                                    val ratio = (toFrame(change.position) - resizeOrigin).getDistance() / startDistance
                                    editor.updateLayer(photoWidth, photoHeight, startLayer.id) { node ->
                                        when (node.kind) {
                                            FrameLayerKind.TEXT -> node.copy(text = node.text.copy(
                                                size = (startLayer.text.size * ratio).coerceIn(0.005f, 0.25f)))
                                            FrameLayerKind.GROUP -> node.copy(scale = (startLayer.scale * ratio).coerceIn(0.1f, 4f))
                                            else -> node.copy(width = (hit.width / FRAME_UNIT * ratio).coerceIn(0.001f, 16f),
                                                height = (hit.height / FRAME_UNIT * ratio).coerceIn(0.001f, 16f))
                                        }
                                    }
                                } else {
                                    val delta = (change.position - change.previousPosition) / max(0.0001f, viewScale())
                                    val inverse = hit.parent.inverse()
                                    val dx = inverse.a * delta.x + inverse.c * delta.y
                                    val dy = inverse.b * delta.x + inverse.d * delta.y
                                    editor.updateLayer(photoWidth, photoHeight, startLayer.id) {
                                        it.copy(x = (it.x + dx / FRAME_UNIT).coerceIn(-8f, 8f), y = (it.y + dy / FRAME_UNIT).coerceIn(-8f, 8f))
                                    }
                                }
                            } else if (zoom > 1f) pan += change.position - change.previousPosition
                            change.consume()
                        }
                    }
                } while (event.changes.any { it.pressed })
            } finally { editor.endGesture() }
        }
    }) {
        val scale = viewScale()
        val offset = origin()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(offset.x, offset.y)
            native.scale(scale, scale)
            native.drawBitmap(rendered.bitmap, null,
                RectF(0f, 0f, rendered.plan.geometry.canvas.width, rendered.plan.geometry.canvas.height), Paint(Paint.FILTER_BITMAP_FLAG))
            native.restore()
        }
        rendered.plan.placements.find { it.layer.id == editor.selectedId }?.let { selected ->
            val m = selected.matrix
            fun point(x: Float, y: Float) = offset + Offset(m.x(x, y), m.y(x, y)) * scale
            val corners = listOf(point(0f, 0f), point(selected.width, 0f), point(selected.width, selected.height), point(0f, selected.height))
            val path = Path().apply { moveTo(corners[0].x, corners[0].y); corners.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            drawPath(path, Color.White, style = Stroke(4.dp.toPx()))
            drawPath(path, accent, style = Stroke(2.dp.toPx()))
            if (!selected.locked) {
                drawCircle(Color.White, 8.dp.toPx(), corners[2])
                drawCircle(accent, 6.dp.toPx(), corners[2])
            }
        }
    }
}
