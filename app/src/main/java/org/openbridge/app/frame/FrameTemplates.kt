package org.openbridge.app.frame

import org.openbridge.app.domain.FrameAlign
import org.openbridge.app.domain.FrameBackground
import org.openbridge.app.domain.FrameBackgroundKind
import org.openbridge.app.domain.FrameCanvas
import org.openbridge.app.domain.FrameCondition
import org.openbridge.app.domain.FrameDecoration
import org.openbridge.app.domain.FrameDesign
import org.openbridge.app.domain.FrameFieldFormat
import org.openbridge.app.domain.FrameFlow
import org.openbridge.app.domain.FrameInsets
import org.openbridge.app.domain.FrameLayer
import org.openbridge.app.domain.FrameLayerKind
import org.openbridge.app.domain.FramePhoto
import org.openbridge.app.domain.FramePoint
import org.openbridge.app.domain.FrameRegion
import org.openbridge.app.domain.FrameRepeat
import org.openbridge.app.domain.FrameTemplate
import org.openbridge.app.domain.FrameText
import org.openbridge.app.domain.FrameTextPart

/** Original data presets based on measured layout proportions. No vendor logos, fonts or artwork are bundled. */
internal object FrameTemplates {
    private const val DARK = "#222222"
    private const val GREY = "#777777"
    private const val WHITE = "#FFFFFF"

    private fun text(name: String, vararg fields: String, size: Float = 0.022f, weight: Int = 400, color: String = DARK,
        font: String = "system:sans-serif", format: FrameFieldFormat = FrameFieldFormat.DEFAULT): FrameLayer =
        FrameLayer(name = name, text = FrameText(fields.map { FrameTextPart(it, format) }, size = size, weight = weight, color = color, font = font))

    private fun column(name: String, vararg children: FrameLayer, align: FrameAlign = FrameAlign.START, gap: Float = 0.007f) =
        FrameLayer(name = name, kind = FrameLayerKind.GROUP, flow = FrameFlow.COLUMN, align = align, gap = gap, children = children.toList())

    private fun row(name: String, vararg children: FrameLayer, gap: Float = 0.018f) =
        FrameLayer(name = name, kind = FrameLayerKind.GROUP, flow = FrameFlow.ROW, align = FrameAlign.CENTER, gap = gap, children = children.toList())

    private fun logo(size: Float = 0.055f) =
        FrameLayer(name = "品牌标志", kind = FrameLayerKind.IMAGE, width = size * 1.35f, height = size, autoLogo = true)
    private fun divider(color: String = "#CCCCCC") =
        FrameLayer(name = "分隔线", kind = FrameLayerKind.LINE, width = 0.0015f, height = 0.045f, requiresLogo = true,
            decoration = FrameDecoration(color = color))
    private fun info(color: String = DARK, size: Float = 0.021f, mono: Boolean = false) =
        text("拍摄参数", "{focal}", "{aperture}", "{shutter}", "{iso}", color = color, size = size,
            font = if (mono) "system:monospace" else "system:sans-serif")
    private fun camera(color: String = DARK, size: Float = 0.025f) = text("机型", "{camera}", color = color, size = size, weight = 700)
    private fun time(color: String = GREY, size: Float = 0.018f) = text("时间", "{time}", color = color, size = size)
    private fun gps(color: String = GREY) = text("位置", "{gps}", color = color, size = 0.017f, format = FrameFieldFormat.DMS)
    private fun brand(color: String = DARK) = row("机型与标志", camera(color, 0.032f), divider(), logo(0.035f))

    private fun pin(layer: FrameLayer, region: FrameRegion, at: FramePoint, x: Float = 0f, y: Float = 0f,
        maxWidth: Float = 0.9f, pivot: FramePoint = at): FrameLayer =
        layer.copy(region = region, at = at, pivot = pivot, x = x, y = y, maxWidth = maxWidth)

    private fun preset(id: String, name: String, family: String, insets: FrameInsets, layers: List<FrameLayer>,
        background: FrameBackground = FrameBackground(), photo: FramePhoto = FramePhoto(), custom: String = "") =
        FrameTemplate("builtin." + id, name, family,
            design = FrameDesign(FrameCanvas(insets, background = background), photo, layers), custom = custom)

    private fun cell(field: String, label: String): FrameLayer {
        val value = text(label + "数值", "{" + field + "}", size = 0.021f, format = FrameFieldFormat.VALUE).let {
            it.copy(width = 0.10f, height = 0.055f, text = it.text.copy(align = FrameAlign.CENTER),
                decoration = FrameDecoration(fill = false, stroke = 0.0015f, strokeColor = "#BBBBBB", radius = 0.012f))
        }
        return column(label, value, text(label + "单位", label, size = 0.018f, color = GREY), align = FrameAlign.CENTER, gap = 0.016f)
            .copy(width = 0.1f, condition = FrameCondition("present", field = field))
    }

    val all: List<FrameTemplate> by lazy {
        val footer = FrameInsets(bottom = 0.15f)
        val none = FrameInsets(bottom = 0f)
        val classic = preset("mi-classic", "经典双栏", "参数栏", footer, listOf(
            pin(column("机型与时间", camera(), time()), FrameRegion.BOTTOM, FramePoint.LEFT, x = 0.044f, maxWidth = 0.40f),
            pin(row("标志与参数", logo(), divider(), column("参数与位置", info(), gps()).copy(maxWidth = 0.38f)),
                FrameRegion.BOTTOM, FramePoint.RIGHT, x = -0.044f, maxWidth = 0.49f)))
        val overlay = preset("mi-center", "居中叠印", "叠印", none, listOf(
            pin(column("信息组", logo(), camera(WHITE), info("#DDDDDD", 0.019f), gps("#DDDDDD"), time("#DDDDDD"),
                align = FrameAlign.CENTER, gap = 0.008f), FrameRegion.PHOTO, FramePoint.BOTTOM, y = -0.04f)))
        val instrument = preset("mi-instrument", "仪表底栏", "参数栏", footer, listOf(
            pin(row("仪表参数", logo(0.065f), column("参数",
                text("焦距", "{focal}", size = 0.018f, font = "system:monospace"),
                info(size = 0.017f, mono = true), gps()).copy(maxWidth = 0.43f)),
                FrameRegion.BOTTOM, FramePoint.LEFT, x = 0.037f, maxWidth = 0.56f),
            pin(column("机型与时间", camera(size = 0.022f), time(size = 0.017f), align = FrameAlign.END),
                FrameRegion.BOTTOM, FramePoint.RIGHT, x = -0.037f, maxWidth = 0.34f)))
        val exhibition = preset("mi-exhibition", "展览框", "展览框", FrameInsets(0.137f, 0.074f, 0.216f, 0.074f), listOf(
            pin(row("顶部品牌", camera(size = 0.025f), divider(), logo(0.03f)), FrameRegion.TOP, FramePoint.LEFT, maxWidth = 0.9f),
            pin(column("左侧参数", info(size = 0.017f, mono = true), time(size = 0.016f)),
                FrameRegion.BOTTOM, FramePoint.LEFT, maxWidth = 0.6f),
            pin(gps(), FrameRegion.BOTTOM, FramePoint.RIGHT, maxWidth = 0.38f)))
        val cells = listOf(cell("shutter", "S"), cell("iso", "ISO"), cell("focal", "mm"), cell("aperture", "F"))
        val grid = preset("mi-grid", "横向参数格", "参数格", FrameInsets(0.044f, 0.044f, 0.31f, 0.044f), listOf(
            column("信息组", brand(), row("参数格", *cells.toTypedArray(), gap = 0.045f), align = FrameAlign.CENTER, gap = 0.04f)))
        val centered = preset("mi-mat", "居中留白框", "展览框", FrameInsets(0.045f, 0.045f, 0.33f, 0.045f), listOf(
            column("信息组", brand(), row("参数行",
                text("焦距", "{focal}", color = GREY), text("光圈", "{aperture}", color = GREY),
                text("快门", "{shutter}", color = GREY), text("ISO", "{iso}", color = GREY), gap = 0.05f),
                align = FrameAlign.CENTER, gap = 0.04f)))
        val side = preset("mi-side", "旋转侧栏", "参数栏", FrameInsets(right = 0.105f, bottom = 0f), listOf(
            pin(info(GREY, 0.020f), FrameRegion.RIGHT, FramePoint.TOP, y = 0.045f, pivot = FramePoint.LEFT).copy(rotation = 90f),
            pin(row("机型与标志", camera(size = 0.029f), divider(), logo(0.04f)), FrameRegion.RIGHT, FramePoint.BOTTOM,
                y = -0.045f, pivot = FramePoint.RIGHT).copy(rotation = 90f)))
        val sideGrid = preset("mi-side-grid", "竖向参数格", "参数格", FrameInsets(0.037f, 0.267f, 0.037f, 0.037f), listOf(
            pin(column("竖向参数", cell("focal", "mm"), cell("aperture", "F"), cell("shutter", "S"), cell("iso", "ISO"),
                align = FrameAlign.CENTER, gap = 0.055f), FrameRegion.RIGHT, FramePoint.TOP, y = 0.075f),
            pin(column("机型与标志", camera(size = 0.023f), logo(0.035f), align = FrameAlign.CENTER, gap = 0.03f),
                FrameRegion.RIGHT, FramePoint.BOTTOM, y = -0.065f, maxWidth = 0.24f)))
        val corners = preset("mi-corners", "角落叠印", "叠印", none, listOf(
            pin(camera(WHITE, 0.029f), FrameRegion.PHOTO, FramePoint.BOTTOM_LEFT, x = 0.046f, y = -0.04f, maxWidth = 0.55f),
            pin(time(WHITE, 0.025f), FrameRegion.PHOTO, FramePoint.BOTTOM_RIGHT, x = -0.046f, y = -0.04f, maxWidth = 0.36f)))
        val paper = preset("mi-paper", "深色纹理框", "胶片与材质", FrameInsets(0.07f, 0.07f, 0.30f, 0.07f),
            listOf(camera(WHITE, 0.035f)), FrameBackground(kind = FrameBackgroundKind.TEXTURE, color = "#303030"))
        val four = preset("four-corners", "经典四角", "参数栏", FrameInsets(0.03f, 0.03f, 0.12f, 0.03f), listOf(
            pin(column("左侧信息", camera(size = 0.021f), text("镜头", "{lens}", size = 0.0195f, color = "#505050")),
                FrameRegion.BOTTOM, FramePoint.LEFT, x = 0.03f, maxWidth = 0.43f),
            pin(column("右侧信息", info(size = 0.021f), time(size = 0.0195f)), FrameRegion.BOTTOM, FramePoint.RIGHT,
                x = -0.03f, maxWidth = 0.43f)))
        val soft = preset("soft", "柔光圆角", "展览框", FrameInsets(0.08f, 0.08f, 0.22f, 0.08f),
            listOf(column("底部信息", camera(WHITE), info("#EEEEEE"), align = FrameAlign.CENTER, gap = 0.014f)),
            FrameBackground(kind = FrameBackgroundKind.BLUR), FramePhoto(radius = 0.035f, shadow = 0.025f))
        fun perforations(region: FrameRegion) = FrameLayer(name = "齿孔", kind = FrameLayerKind.SHAPE, region = region,
            at = FramePoint.LEFT, pivot = FramePoint.LEFT, x = 0.015f, width = 0.026f, height = 0.022f,
            decoration = FrameDecoration(color = "#DAD4C9", radius = 0.004f), repeat = FrameRepeat.ROW, repeatStep = 0.055f)
        val film = preset("film", "胶片条", "胶片与材质", FrameInsets(0.065f, 0.025f, 0.14f, 0.025f), listOf(
            perforations(FrameRegion.TOP),
            pin(text("编号", "{custom}", size = 0.016f, color = "#E6C373", font = "system:monospace"),
                FrameRegion.BOTTOM, FramePoint.LEFT, y = -0.025f, maxWidth = 0.3f),
            pin(info("#E6C373", 0.016f, mono = true), FrameRegion.BOTTOM, FramePoint.RIGHT, y = -0.025f, maxWidth = 0.65f),
            perforations(FrameRegion.BOTTOM).copy(y = 0.04f)), FrameBackground(color = "#171717"), custom = "FRAME 01")
        val stamp = preset("stamp", "邮票装饰", "胶片与材质", FrameInsets(0.06f, 0.06f, 0.17f, 0.06f), listOf(
            pin(column("左下信息", camera(size = 0.023f), info(size = 0.017f)), FrameRegion.BOTTOM, FramePoint.LEFT, maxWidth = 0.68f),
            pin(column("邮戳", text("标题", "{custom}", size = 0.019f, color = "#98544B", weight = 600),
                time("#98544B", 0.014f), align = FrameAlign.CENTER).copy(width = 0.23f, height = 0.105f, rotation = -12f,
                decoration = FrameDecoration(fill = false, stroke = 0.0015f, strokeColor = "#98544B", circle = true)),
                FrameRegion.BOTTOM, FramePoint.RIGHT, maxWidth = 0.23f)),
            FrameBackground(color = "#F5F0E6"), FramePhoto(stroke = 0.003f, strokeColor = "#D5CDBF"), custom = "PHOTO")
        listOf(classic, overlay, instrument, exhibition, grid, centered, side, sideGrid, corners, paper, four, soft, film, stamp)
            .onEach(FrameCodec::validate)
    }

    fun blank(): FrameTemplate = FrameTemplate("user.blank", "空白模板", design = FrameDesign(
        FrameCanvas(FrameInsets(bottom = 0f)), layers = emptyList()))
}
