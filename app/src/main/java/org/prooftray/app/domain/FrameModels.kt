package org.prooftray.app.domain

import java.util.UUID

enum class FrameLayerKind { GROUP, TEXT, IMAGE, SHAPE, LINE }
enum class FrameRegion { CANVAS, PHOTO, TOP, RIGHT, BOTTOM, LEFT }
enum class FramePoint(val x: Float, val y: Float) {
    TOP_LEFT(0f, 0f), TOP(0.5f, 0f), TOP_RIGHT(1f, 0f), LEFT(0f, 0.5f), CENTER(0.5f, 0.5f),
    RIGHT(1f, 0.5f), BOTTOM_LEFT(0f, 1f), BOTTOM(0.5f, 1f), BOTTOM_RIGHT(1f, 1f)
}
enum class FrameFlow { FREE, ROW, COLUMN }
enum class FrameAlign { START, CENTER, END, SPACE_BETWEEN }
enum class FrameBackgroundKind { SOLID, GRADIENT, BLUR, TEXTURE, IMAGE }
enum class FrameAspect(val ratio: Float?) {
    AUTO(null), PHOTO(null), SQUARE(1f), PORTRAIT(4f / 5), STORY(9f / 16), LANDSCAPE(16f / 9), CLASSIC(3f / 2), FOUR_THREE(4f / 3)
}
enum class FrameFieldFormat { DEFAULT, VALUE, SECONDS, RECIPROCAL, DMS }
enum class FrameRepeat { NONE, ROW, COLUMN }

/** Geometry is in oriented photo-short-side units, independent of dp, font scale and output resolution. */
data class FrameInsets(val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0.15f, val left: Float = 0f)
data class FrameBackground(
    val kind: FrameBackgroundKind = FrameBackgroundKind.SOLID, val color: String = "#FFFFFF", val secondColor: String = "#DFD7CD",
    val opacity: Float = 1f, val blur: Float = 0.035f, val dim: Float = 0.15f, val textureSize: Float = 0.25f, val imageId: String? = null
)
data class FrameCanvas(val insets: FrameInsets = FrameInsets(), val aspect: FrameAspect = FrameAspect.AUTO,
    val background: FrameBackground = FrameBackground())
data class FramePhoto(
    val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f, val radius: Float = 0f, val stroke: Float = 0f,
    val strokeColor: String = "#FFFFFF", val shadow: Float = 0f, val shadowOpacity: Float = 0.25f
)
data class FrameTextPart(val text: String, val format: FrameFieldFormat = FrameFieldFormat.DEFAULT,
    val color: String? = null, val weight: Int? = null, val literal: Boolean = false)
data class FrameText(
    val parts: List<FrameTextPart> = listOf(FrameTextPart("{camera}")), val separator: String = " ", val font: String = "system:sans-serif",
    val size: Float = 0.022f, val weight: Int = 400, val italic: Boolean = false, val color: String = "#222222",
    val letterSpacing: Float = 0f, val align: FrameAlign = FrameAlign.START, val maxLines: Int = 1, val minScale: Float = 0.75f
)
data class FrameDecoration(
    val color: String = "#FFFFFF", val fill: Boolean = true, val stroke: Float = 0f, val strokeColor: String = "#BBBBBB",
    val radius: Float = 0f, val shadow: Float = 0f, val circle: Boolean = false, val dash: Float = 0f
)
data class FrameCondition(val op: String, val field: String = "", val param: String = "", val value: String = "",
    val conditions: List<FrameCondition> = emptyList())
data class FrameLayer(
    val id: String = UUID.randomUUID().toString(), val name: String = "文字", val kind: FrameLayerKind = FrameLayerKind.TEXT,
    val region: FrameRegion = FrameRegion.BOTTOM, val at: FramePoint = FramePoint.CENTER, val pivot: FramePoint = FramePoint.CENTER,
    val x: Float = 0f, val y: Float = 0f, val width: Float? = null, val height: Float? = null, val maxWidth: Float = 0.9f,
    val fillWidth: Boolean = false, val fillHeight: Boolean = false, val rotation: Float = 0f, val scale: Float = 1f,
    val opacity: Float = 1f, val z: Int = 10, val visible: Boolean = true, val locked: Boolean = false,
    val flow: FrameFlow = FrameFlow.FREE, val align: FrameAlign = FrameAlign.CENTER, val gap: Float = 0.008f,
    val padding: Float = 0f, val children: List<FrameLayer> = emptyList(), val text: FrameText = FrameText(),
    val decoration: FrameDecoration? = null, val imageId: String? = null, val autoLogo: Boolean = false,
    val requiresLogo: Boolean = false, val repeat: FrameRepeat = FrameRepeat.NONE, val repeatStep: Float = 0.055f,
    val condition: FrameCondition? = null
)
data class FrameDesign(val canvas: FrameCanvas = FrameCanvas(), val photo: FramePhoto = FramePhoto(), val layers: List<FrameLayer> = emptyList())
data class FrameTemplate(
    val id: String, val name: String, val family: String = "我的模板", val revision: Int = 1, val design: FrameDesign = FrameDesign(),
    val portrait: FrameDesign? = null, val landscape: FrameDesign? = null, val custom: String = "", val showTime: Boolean = true,
    val showGps: Boolean = false, val author: String = "", val license: String = "", val metadata: String = "{}",
    val logos: Map<String, String> = emptyMap()
) {
    fun forPhoto(width: Int, height: Int): FrameDesign = if (height > width) portrait ?: design else landscape ?: design
    fun designs(): List<FrameDesign> = listOfNotNull(design, portrait, landscape)
}

/** Immutable references plus per-photo metadata make queued exports independent of later edits to a template or logo mapping. */
data class FrameSnapshot(
    val template: FrameTemplate, val values: Map<String, String> = emptyMap(), val logoId: String? = null,
    val unavailableReason: String? = null, val rawJson: String? = null
)

fun FrameDesign.allLayers(): List<FrameLayer> = layers.flatMap { it.flatten() }
fun FrameLayer.flatten(): List<FrameLayer> = listOf(this) + children.flatMap { it.flatten() }
fun FrameDesign.updateLayer(id: String, transform: (FrameLayer) -> FrameLayer): FrameDesign {
    fun visit(layer: FrameLayer): FrameLayer = if (layer.id == id) transform(layer) else layer.copy(children = layer.children.map(::visit))
    return copy(layers = layers.map(::visit))
}
fun FrameDesign.removeLayer(id: String): FrameDesign {
    fun visit(layers: List<FrameLayer>): List<FrameLayer> = layers.filterNot { it.id == id }.map { it.copy(children = visit(it.children)) }
    return copy(layers = visit(layers))
}
fun FrameLayer.duplicate(): FrameLayer = copy(id = UUID.randomUUID().toString(), children = children.map { it.duplicate() })

fun FrameTemplate.imageIds(): Set<String> = buildSet {
    addAll(logos.values)
    designs().forEach { design ->
        design.canvas.background.imageId?.let(::add)
        design.allLayers().mapNotNullTo(this) { it.imageId }
    }
}
fun FrameTemplate.fontIds(): Set<String> = designs().flatMap { it.allLayers() }
    .filter { it.kind == FrameLayerKind.TEXT }.map { it.text.font }.filterNot { it.startsWith("system:") }.toSet()
