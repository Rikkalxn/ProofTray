package org.prooftray.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import kotlin.math.atan2
import kotlin.math.hypot
import org.prooftray.app.domain.allLayers
import org.prooftray.app.domain.duplicate
import org.prooftray.app.domain.flatten
import org.prooftray.app.domain.FrameDecoration
import org.prooftray.app.domain.FrameDesign
import org.prooftray.app.domain.FrameInsets
import org.prooftray.app.domain.FrameFlow
import org.prooftray.app.domain.FrameLayer
import org.prooftray.app.domain.FrameLayerKind
import org.prooftray.app.domain.FramePoint
import org.prooftray.app.domain.FrameRegion
import org.prooftray.app.domain.FrameTemplate
import org.prooftray.app.domain.FrameText
import org.prooftray.app.domain.FrameTextPart
import org.prooftray.app.domain.removeLayer
import org.prooftray.app.domain.updateLayer
import org.prooftray.app.frame.FRAME_UNIT
import org.prooftray.app.frame.FrameCodec
import org.prooftray.app.frame.FramePlacement

internal enum class FrameLayerSection(val title: String) {
    CONTENT("内容"), TEXT_STYLE("文字样式"), GEOMETRY("位置与大小"), APPEARANCE("外观"), OTHER("其他")
}

/** One history entry per gesture; only lightweight template data participates in undo/redo. */
internal class FrameEditorState(
    initial: FrameTemplate, initiallyEnabled: Boolean = true, private val onChanged: (FrameTemplate?) -> Unit = {}
) {
    private data class Snapshot(val template: FrameTemplate, val enabled: Boolean)
    var enabled by mutableStateOf(initiallyEnabled)
        private set
    var template by mutableStateOf(initial)
        private set
    var selectedId by mutableStateOf<String?>(null)
    var issue by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var resourcesRevision by mutableStateOf(0)
        private set
    var expandedSections by mutableStateOf(setOf(FrameLayerSection.CONTENT, FrameLayerSection.TEXT_STYLE))
        private set
    var uniformInsets by mutableStateOf(false)
        private set
    var historyRevision by mutableStateOf(0)
        private set
    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    private var checkpoint: Snapshot? = null
    private fun snapshot() = Snapshot(template, enabled)
    private fun restore(value: Snapshot) {
        template = value.template; enabled = value.enabled
        onChanged(template.takeIf { enabled })
    }
    val canUndo: Boolean get() { historyRevision; return undo.isNotEmpty() }
    val canRedo: Boolean get() { historyRevision; return redo.isNotEmpty() }

    fun resourcesChanged() { resourcesRevision++ }

    fun toggleSection(section: FrameLayerSection) {
        endGesture()
        expandedSections = if (section in expandedSections) expandedSections - section else expandedSections + section
    }

    fun setUniformInsets(width: Int, height: Int, enabled: Boolean) {
        endGesture()
        uniformInsets = enabled
        if (enabled) updateDesign(width, height) { design ->
            val insets = design.canvas.insets
            val value = maxOf(insets.top, insets.right, insets.bottom, insets.left)
            design.copy(canvas = design.canvas.copy(insets = FrameInsets(value, value, value, value)))
        }
    }

    fun change(transform: (FrameTemplate) -> FrameTemplate) {
        val next = transform(template)
        if (next == template && enabled) return
        try { FrameCodec.validate(next) }
        catch (error: IllegalArgumentException) { issue = error.message ?: "参数无效"; return }
        if (checkpoint == null) { pushUndo(snapshot()); redo.clear() }
        template = next
        enabled = true
        onChanged(next)
    }
    fun clear() {
        endGesture()
        if (!enabled) return
        pushUndo(snapshot()); redo.clear(); enabled = false; selectedId = null
        onChanged(null)
    }
    fun replace(next: FrameTemplate) { endGesture(); change { next }; selectedId = null }
    fun beginGesture() { if (checkpoint == null) checkpoint = snapshot() }
    fun endGesture() {
        checkpoint?.takeIf { it != snapshot() }?.let { pushUndo(it); redo.clear() }
        checkpoint = null
    }
    fun undo() {
        endGesture()
        if (undo.isEmpty()) return
        redo.addLast(snapshot()); restore(undo.removeLast()); historyRevision++
    }
    fun redo() {
        endGesture()
        if (redo.isEmpty()) return
        pushUndo(snapshot()); restore(redo.removeLast()); historyRevision++
    }
    private fun pushUndo(value: Snapshot) {
        undo.addLast(value)
        if (undo.size > 100) undo.removeFirst()
        historyRevision++
    }

    fun updateDesign(width: Int, height: Int, transform: (FrameDesign) -> FrameDesign) = change { current ->
        val next = transform(current.forPhoto(width, height))
        when {
            current.portrait == null && current.landscape == null -> current.copy(design = next)
            height > width -> current.copy(portrait = next)
            else -> current.copy(landscape = next)
        }
    }
    fun updateLayer(width: Int, height: Int, id: String, transform: (FrameLayer) -> FrameLayer) =
        updateDesign(width, height) { it.updateLayer(id, transform) }
    fun separate(width: Int, height: Int, enabled: Boolean) = change { current ->
        if (enabled) current.copy(portrait = current.portrait ?: current.design, landscape = current.landscape ?: current.design)
        else current.copy(design = current.forPhoto(width, height), portrait = null, landscape = null)
    }
    fun add(width: Int, height: Int, layer: FrameLayer) {
        val design = template.forPhoto(width, height)
        require(design.allLayers().size + layer.flatten().size <= FrameCodec.MAX_LAYERS) { "最多添加 32 个图层" }
        val parent = design.allLayers().find { it.id == selectedId && it.kind == FrameLayerKind.GROUP }
        updateDesign(width, height) { current ->
            if (parent == null) current.copy(layers = current.layers + layer)
            else current.updateLayer(parent.id) { it.copy(children = it.children + layer.copy(x = 0f, y = 0f)) }
        }
        if (template.forPhoto(width, height).allLayers().any { it.id == layer.id }) selectedId = layer.id
    }
    fun remove(width: Int, height: Int, id: String) {
        updateDesign(width, height) { it.removeLayer(id) }
        selectedId = null
    }
    fun duplicate(width: Int, height: Int, id: String) {
        val design = template.forPhoto(width, height)
        val original = design.allLayers().find { it.id == id } ?: return
        require(design.allLayers().size + original.flatten().size <= FrameCodec.MAX_LAYERS) { "最多添加 32 个图层" }
        val copy = original.duplicate().copy(name = original.name.take(70) + " 副本", x = original.x + 0.02f, y = original.y + 0.02f)
        fun visit(layers: List<FrameLayer>): List<FrameLayer> = layers.flatMap {
            if (it.id == id) listOf(it, copy) else listOf(it.copy(children = visit(it.children)))
        }
        updateDesign(width, height) { it.copy(layers = visit(it.layers)) }
        selectedId = copy.id
    }
    fun reorder(width: Int, height: Int, id: String, delta: Int) {
        fun visit(layers: List<FrameLayer>): List<FrameLayer> {
            val ordered = layers.sortedBy { it.z }.toMutableList()
            val index = ordered.indexOfFirst { it.id == id }
            if (index >= 0) {
                val target = (index + delta).coerceIn(0, ordered.lastIndex)
                val other = ordered[target]; ordered[target] = ordered[index]; ordered[index] = other
                return ordered.mapIndexed { i, layer -> layer.copy(z = if (layer.z < 0) -100 + i else 10 + i) }
            }
            return layers.map { it.copy(children = visit(it.children)) }
        }
        updateDesign(width, height) { it.copy(layers = visit(it.layers)) }
    }
    fun detach(width: Int, height: Int, placement: FramePlacement) {
        val matrix = placement.matrix
        val angle = Math.toDegrees(atan2(matrix.b.toDouble(), matrix.a.toDouble())).toFloat()
        val detached = placement.layer.copy(region = FrameRegion.CANVAS, at = FramePoint.TOP_LEFT, pivot = FramePoint.TOP_LEFT,
            x = matrix.tx / FRAME_UNIT, y = matrix.ty / FRAME_UNIT, rotation = angle, scale = hypot(matrix.a, matrix.b).coerceIn(0.1f, 4f),
            width = placement.width / FRAME_UNIT, height = if (placement.layer.kind == FrameLayerKind.TEXT) null else placement.height / FRAME_UNIT,
            opacity = placement.opacity)
        updateDesign(width, height) { it.removeLayer(detached.id).let { design -> design.copy(layers = design.layers + detached) } }
    }
    fun allFonts(font: String) = change { current ->
        fun design(d: FrameDesign): FrameDesign {
            fun layer(n: FrameLayer): FrameLayer = n.copy(text = n.text.copy(font = font), children = n.children.map(::layer))
            return d.copy(layers = d.layers.map(::layer))
        }
        current.copy(design = design(current.design), portrait = current.portrait?.let(::design), landscape = current.landscape?.let(::design))
    }

    companion object {
        val StateSaver = Saver<FrameEditorState, String>(
            save = { FrameCodec.encode(it.template).toString() },
            restore = { runCatching { FrameEditorState(FrameCodec.decode(FrameCodec.checkedJson(it))) }.getOrNull() }
        )
        fun newLayer(kind: FrameLayerKind): FrameLayer = when (kind) {
            FrameLayerKind.TEXT -> FrameLayer(name = "文字", region = FrameRegion.PHOTO, at = FramePoint.BOTTOM, pivot = FramePoint.BOTTOM,
                y = -0.04f, text = FrameText(parts = listOf(FrameTextPart("文字")), color = "#FFFFFF"))
            FrameLayerKind.SHAPE -> FrameLayer(name = "色块", kind = kind, region = FrameRegion.PHOTO, width = 0.25f, height = 0.12f,
                decoration = FrameDecoration(color = "#FFFFFF", radius = 0.015f))
            FrameLayerKind.LINE -> FrameLayer(name = "分隔线", kind = kind, region = FrameRegion.PHOTO, width = 0.3f, height = 0.002f,
                decoration = FrameDecoration(color = "#FFFFFF"))
            FrameLayerKind.IMAGE -> FrameLayer(name = "图片", kind = kind, region = FrameRegion.PHOTO, width = 0.15f, height = 0.10f)
            FrameLayerKind.GROUP -> FrameLayer(name = "分组", kind = kind, region = FrameRegion.PHOTO, flow = FrameFlow.COLUMN,
                children = listOf(newLayer(FrameLayerKind.TEXT).copy(x = 0f, y = 0f)))
        }
    }
}
