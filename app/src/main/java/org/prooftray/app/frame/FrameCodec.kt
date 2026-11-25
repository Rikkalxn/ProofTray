package org.prooftray.app.frame

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.prooftray.app.domain.allLayers
import org.prooftray.app.domain.fontIds
import org.prooftray.app.domain.FrameBackground
import org.prooftray.app.domain.FrameCanvas
import org.prooftray.app.domain.FrameCondition
import org.prooftray.app.domain.FrameDecoration
import org.prooftray.app.domain.FrameDesign
import org.prooftray.app.domain.FrameInsets
import org.prooftray.app.domain.FrameLayer
import org.prooftray.app.domain.FrameLayerKind
import org.prooftray.app.domain.FramePhoto
import org.prooftray.app.domain.FramePoint
import org.prooftray.app.domain.FrameRegion
import org.prooftray.app.domain.FrameRepeat
import org.prooftray.app.domain.FrameSnapshot
import org.prooftray.app.domain.FrameTemplate
import org.prooftray.app.domain.FrameText
import org.prooftray.app.domain.FrameTextPart
import org.prooftray.app.domain.imageIds

/** Data only: no scripting, HTML evaluation, resource URLs or permissive enum fallbacks. */
internal object FrameCodec {
    const val SCHEMA = "org.prooftray.frame"
    const val VERSION = 1
    private val SCHEMAS = setOf(SCHEMA, "org.openbridge.frame")
    const val MAX_JSON = 262144
    const val MAX_LAYERS = 32
    private val assetId = Regex("[a-f0-9]{64}")
    private val color = Regex("#[a-fA-F0-9]{6}")

    fun checkedJson(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_JSON) { "模板 JSON 不能超过 256 KiB" }
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { c ->
            if (c == '"' && !escaped) quoted = !quoted
            if (!quoted) {
                if (c == '{' || c == '[') depth++
                if (c == '}' || c == ']') depth--
                require(depth in 0..16) { "模板嵌套过深或格式无效" }
            }
            escaped = !escaped && c == '\\'
        }
        return JSONObject(text)
    }

    fun encode(template: FrameTemplate): JSONObject {
        validate(template)
        return encodeData(template)
    }

    private fun encodeData(template: FrameTemplate): JSONObject {
        return encodeDesign(template.design).apply {
            put("schema", SCHEMA); put("schemaVersion", VERSION); put("id", template.id); put("name", template.name)
            put("revision", template.revision); put("family", template.family); put("unit", "photoShortSide")
            put("author", template.author); put("license", template.license); put("metadata", JSONObject(template.metadata))
            put("params", JSONObject().put("custom", template.custom).put("showTime", template.showTime).put("showGps", template.showGps))
            put("orientationOverrides", JSONObject().apply {
                template.portrait?.let { put("portrait", encodeDesign(it)) }
                template.landscape?.let { put("landscape", encodeDesign(it)) }
            })
            put("logos", JSONObject(template.logos))
            put("resources", JSONArray(template.imageIds().sorted().map {
                JSONObject().put("id", it).put("path", "resources/" + it + ".png").put("sha256", it)
            }))
        }.also { require(it.toString().toByteArray(Charsets.UTF_8).size <= MAX_JSON) { "模板 JSON 不能超过 256 KiB" } }
    }

    fun decode(json: JSONObject): FrameTemplate {
        require(json.optString("schema") in SCHEMAS) { "这不是照片边框模板" }
        require(json.optInt("schemaVersion") == VERSION) { "此模板需要更新的应用版本" }
        require(json.optString("unit", "photoShortSide") == "photoShortSide") { "不支持的模板尺寸单位" }
        val params = json.optJSONObject("params") ?: JSONObject()
        val variants = json.optJSONObject("orientationOverrides")
        return FrameTemplate(json.getString("id"), json.getString("name"), json.optString("family", "我的模板"),
            json.optInt("revision", 1), decodeDesign(json), variants?.optJSONObject("portrait")?.let(::decodeDesign),
            variants?.optJSONObject("landscape")?.let(::decodeDesign), params.optString("custom"), params.optBoolean("showTime", true),
            params.optBoolean("showGps", false), json.optString("author"), json.optString("license"),
            (json.optJSONObject("metadata") ?: JSONObject()).toString(), stringMap(json.optJSONObject("logos"))).also(::validate)
    }

    fun encodeSnapshot(snapshot: FrameSnapshot): JSONObject = snapshot.rawJson?.let(::JSONObject) ?: JSONObject().apply {
        // Task journaling must also preserve an invalid frame and its failure. Validation happens at preparation/execution.
        put("template", encodeData(snapshot.template)); put("values", JSONObject(snapshot.values))
        snapshot.logoId?.let { put("logoId", it) }
    }

    fun decodeSnapshot(json: JSONObject): FrameSnapshot = try {
        withFrameInitialization {
            val template = decode(json.getJSONObject("template"))
            val logo = json.optString("logoId").takeIf { it.isNotBlank() }
            require(logo == null || assetId.matches(logo)) { "品牌图片标识无效" }
            val values = stringMap(json.optJSONObject("values"))
            require(values.size <= 32 && values.values.all { it.length <= 2048 }) { "水印字段超出上限" }
            FrameSnapshot(template, values, logo)
        }
    } catch (error: Exception) {
        FrameSnapshot(FrameTemplate("unavailable", "不可用模板"), unavailableReason = error.message ?: "模板无法读取", rawJson = json.toString())
    }

    private fun encodeDesign(design: FrameDesign): JSONObject = JSONObject().apply {
        val canvas = design.canvas
        put("canvas", JSONObject().apply {
            put("aspect", canvas.aspect.name)
            put("padding", JSONObject().put("top", canvas.insets.top).put("right", canvas.insets.right)
                .put("bottom", canvas.insets.bottom).put("left", canvas.insets.left))
            put("background", JSONObject().apply {
                val bg = canvas.background
                put("type", bg.kind.name); put("color", bg.color); put("secondColor", bg.secondColor); put("opacity", bg.opacity)
                put("blur", bg.blur); put("dim", bg.dim); put("textureSize", bg.textureSize); bg.imageId?.let { put("imageId", it) }
            })
        })
        put("photo", JSONObject().apply {
            val p = design.photo
            put("scale", p.scale); put("offset", JSONArray(listOf(p.x, p.y))); put("cornerRadius", p.radius)
            put("stroke", JSONObject().put("width", p.stroke).put("color", p.strokeColor))
            put("shadow", JSONObject().put("blur", p.shadow).put("opacity", p.shadowOpacity))
        })
        put("layers", JSONArray(design.layers.map(::encodeLayer)))
    }

    private fun encodeLayer(layer: FrameLayer): JSONObject = JSONObject().apply {
        put("id", layer.id); put("name", layer.name); put("type", layer.kind.name)
        put("anchor", JSONObject().put("target", layer.region.name).put("at", layer.at.name).put("pivot", layer.pivot.name))
        put("offset", JSONArray(listOf(layer.x, layer.y))); layer.width?.let { put("width", it) }; layer.height?.let { put("height", it) }
        put("maxWidth", layer.maxWidth); put("fillWidth", layer.fillWidth); put("fillHeight", layer.fillHeight)
        put("rotation", layer.rotation); put("scale", layer.scale); put("opacity", layer.opacity); put("z", layer.z)
        put("visible", layer.visible); put("locked", layer.locked); put("requiresLogo", layer.requiresLogo)
        put("layout", JSONObject().put("flow", layer.flow.name).put("align", layer.align.name).put("gap", layer.gap).put("padding", layer.padding))
        put("repeat", layer.repeat.name); put("repeatStep", layer.repeatStep)
        if (layer.kind == FrameLayerKind.TEXT) {
            val text = layer.text
            put("content", JSONObject().put("parts", JSONArray(text.parts.map { part ->
                JSONObject().put("text", part.text).put("format", part.format.name).apply {
                    part.color?.let { put("color", it) }; part.weight?.let { put("weight", it) }
                    if (part.literal) put("literal", true)
                }
            })).put("join", text.separator))
            put("font", text.font); put("fontSize", text.size); put("weight", text.weight); put("italic", text.italic)
            put("color", text.color); put("letterSpacing", text.letterSpacing); put("textAlign", text.align.name)
            put("maxLines", text.maxLines); put("minScale", text.minScale)
        }
        layer.decoration?.let { d -> put("decoration", JSONObject().apply {
            put("color", d.color); put("fill", d.fill); put("stroke", d.stroke); put("strokeColor", d.strokeColor)
            put("radius", d.radius); put("shadow", d.shadow); put("circle", d.circle); put("dash", d.dash)
        }) }
        if (layer.kind == FrameLayerKind.IMAGE) put("source", JSONObject().apply {
            if (layer.autoLogo) put("autoLogo", "make")
            layer.imageId?.let { put("imageId", it) }
        })
        if (layer.children.isNotEmpty()) put("children", JSONArray(layer.children.map(::encodeLayer)))
        layer.condition?.let { put("visibleWhen", encodeCondition(it)) }
    }

    private fun encodeCondition(condition: FrameCondition): JSONObject = JSONObject().apply {
        put("op", condition.op); put("field", condition.field); put("param", condition.param); put("value", condition.value)
        if (condition.conditions.isNotEmpty()) put("conditions", JSONArray(condition.conditions.map(::encodeCondition)))
    }

    private fun decodeDesign(json: JSONObject): FrameDesign {
        val tokens = json.optJSONObject("tokens") ?: JSONObject()
        fun size(obj: JSONObject, key: String, fallback: Float): Float = length(obj.opt(key), tokens, fallback)
        val canvas = json.getJSONObject("canvas")
        val padding = canvas.optJSONObject("padding") ?: JSONObject()
        val bg = canvas.optJSONObject("background") ?: JSONObject()
        val photo = json.optJSONObject("photo") ?: JSONObject()
        require(photo.optString("fit", "contain").equals("contain", true)) { "此模板的照片填充方式暂不支持" }
        val offset = photo.optJSONArray("offset")
        val stroke = photo.optJSONObject("stroke") ?: JSONObject()
        val shadow = photo.optJSONObject("shadow") ?: JSONObject()
        val aspect = canvas.opt("aspect")
        val aspectName = if (aspect is JSONObject) aspect.optString("mode", "auto") else aspect?.toString() ?: "AUTO"
        return FrameDesign(
            FrameCanvas(FrameInsets(size(padding, "top", 0f), size(padding, "right", 0f), size(padding, "bottom", 0f),
                size(padding, "left", 0f)), enum(aspectName), FrameBackground(enum(bg.optString("type", "SOLID")),
                bg.optString("color", "#FFFFFF"), bg.optString("secondColor", "#DFD7CD"), size(bg, "opacity", 1f),
                size(bg, "blur", 0.035f), size(bg, "dim", 0.15f), size(bg, "textureSize", 0.25f), bg.optionalString("imageId"))),
            FramePhoto(size(photo, "scale", 1f), length(offset?.opt(0), tokens, 0f), length(offset?.opt(1), tokens, 0f),
                size(photo, "cornerRadius", 0f), size(stroke, "width", 0f), stroke.optString("color", "#FFFFFF"),
                size(shadow, "blur", 0f), size(shadow, "opacity", 0.25f)),
            objects(json.getJSONArray("layers")).map { decodeLayer(it, tokens, json.optJSONObject("textDefaults") ?: JSONObject(), 0) }
        )
    }

    private fun decodeLayer(json: JSONObject, tokens: JSONObject, defaults: JSONObject, depth: Int): FrameLayer {
        require(depth <= 3) { "图层分组最多 3 层" }
        val known = setOf("id", "name", "type", "anchor", "offset", "width", "height", "maxWidth", "fillWidth", "fillHeight",
            "rotation", "scale", "opacity", "z", "visible", "locked", "requiresLogo", "layout", "repeat", "repeatStep", "content",
            "font", "fontSize", "weight", "italic", "color", "letterSpacing", "textAlign", "maxLines", "minScale", "decoration",
            "source", "children", "visibleWhen", "box", "fit")
        json.keys().asSequence().firstOrNull { it !in known }?.let { error("不支持的图层参数：" + it) }
        fun size(key: String, fallback: Float): Float = length(json.opt(key), tokens, fallback)
        val kind = enum<FrameLayerKind>(json.getString("type"))
        if (kind == FrameLayerKind.IMAGE) require(json.optString("fit", "contain").equals("contain", true)) { "此模板的图片填充方式暂不支持" }
        val anchor = json.optJSONObject("anchor") ?: JSONObject()
        val target = anchor.optString("target", "BOTTOM").uppercase(Locale.ROOT).removePrefix("BORDER.")
        val offset = json.optJSONArray("offset")
        val content = json.optJSONObject("content") ?: JSONObject()
        val layout = json.optJSONObject("layout") ?: JSONObject()
        val source = json.optJSONObject("source") ?: JSONObject()
        val box = json.optJSONObject("box")
        val parts = content.optJSONArray("parts") ?: JSONArray().put("{camera}")
        require(parts.length() <= 32) { "文字片段过多" }
        val d = json.optJSONObject("decoration")
        val text = FrameText((0 until parts.length()).map { index ->
            when (val part = parts.get(index)) {
                is String -> FrameTextPart(part)
                is JSONObject -> FrameTextPart(part.getString("text"), enum(part.optString("format", "DEFAULT")),
                    part.optionalString("color"), if (part.has("weight")) part.getInt("weight") else null, part.optBoolean("literal"))
                else -> error("文字片段格式无效")
            }
        }, content.optString("join", " "), json.optString("font", defaults.optString("font", "system:sans-serif")),
            size("fontSize", 0.022f), json.optInt("weight", 400), json.optBoolean("italic"),
            json.optString("color", defaults.optString("color", "#222222")),
            size("letterSpacing", 0f), enum(json.optString("textAlign", "START")), json.optInt("maxLines", defaults.optInt("maxLines", 1)),
            size("minScale", defaults.optDouble("minScale", 0.75).toFloat()))
        return FrameLayer(json.getString("id"), json.optString("name", defaultName(kind)), kind,
            if (target == "FOOTER") FrameRegion.BOTTOM else enum(target), point(anchor.optString("at", "CENTER")),
            point(anchor.optString("pivot", "CENTER")), length(offset?.opt(0), tokens, 0f), length(offset?.opt(1), tokens, 0f),
            (json.opt("width") ?: box?.opt("width"))?.takeUnless { it == JSONObject.NULL }?.let { length(it, tokens, 0f) },
            (json.opt("height") ?: box?.opt("height"))?.takeUnless { it == JSONObject.NULL }?.let { length(it, tokens, 0f) },
            size("maxWidth", 0.9f), json.optBoolean("fillWidth"), json.optBoolean("fillHeight"), size("rotation", 0f), size("scale", 1f),
            size("opacity", 1f), json.optInt("z", 10), json.optBoolean("visible", true), json.optBoolean("locked"),
            enum(layout.optString("flow", "FREE")), enum(layout.optString("align", "CENTER")),
            length(layout.opt("gap"), tokens, 0.008f), length(layout.opt("padding"), tokens, 0f),
            objects(json.optJSONArray("children") ?: JSONArray()).map { decodeLayer(it, tokens, defaults, depth + 1) }, text,
            d?.let { FrameDecoration(it.optString("color", "#FFFFFF"), it.optBoolean("fill", true), it.float("stroke", 0f),
                it.optString("strokeColor", "#BBBBBB"), it.float("radius", 0f), it.float("shadow", 0f),
                it.optBoolean("circle"), it.float("dash", 0f)) }, source.optionalString("imageId"),
            source.optString("autoLogo") == "make", json.optBoolean("requiresLogo"), enum(json.optString("repeat", "NONE")),
            size("repeatStep", 0.055f), json.optJSONObject("visibleWhen")?.let { decodeCondition(it, 0) })
    }

    private fun decodeCondition(json: JSONObject, depth: Int): FrameCondition {
        require(depth <= 3) { "可见条件嵌套过深" }
        return FrameCondition(json.getString("op"), json.optString("field"), json.optString("param"), json.optString("value"),
            objects(json.optJSONArray("conditions") ?: JSONArray()).map { decodeCondition(it, depth + 1) })
    }

    fun validate(template: FrameTemplate) {
        require(template.id.length in 1..96 && template.name.isNotBlank() && template.name.length <= 80) { "模板名称或标识无效" }
        require(template.family.length <= 32 && template.revision > 0 && template.custom.length <= 2048) { "模板信息超出上限" }
        require(template.author.length <= 128 && template.license.length <= 256 && template.metadata.length <= 8192) { "模板说明过长" }
        require(template.imageIds().size <= 8 && template.imageIds().all(assetId::matches)) { "模板最多包含 8 张有效图片" }
        require(template.logos.size <= 8 && template.logos.keys.all { it.length in 1..96 }) { "品牌映射无效" }
        require(template.fontIds().size <= 4) { "每个模板最多使用 4 个自定义字体" }
        require(template.designs().sumOf { d -> d.allLayers().sumOf { l -> l.text.parts.sumOf { it.text.length } } } <= 32000) { "模板文字过多" }
        template.designs().forEach { design ->
            val insets = design.canvas.insets
            listOf(insets.top, insets.right, insets.bottom, insets.left).forEach { range(it, 0f, 1f, "留白") }
            val bg = design.canvas.background
            colors(bg.color, bg.secondColor); range(bg.opacity, 0f, 1f, "背景透明度"); range(bg.blur, 0f, 0.15f, "背景模糊")
            range(bg.dim, 0f, 1f, "背景明暗"); range(bg.textureSize, 0.02f, 2f, "纹理大小")
            val p = design.photo
            range(p.scale, 0.1f, 1.5f, "照片大小"); range(p.x, -8f, 8f, "照片位置"); range(p.y, -8f, 8f, "照片位置")
            range(p.radius, 0f, 0.5f, "照片圆角"); range(p.stroke, 0f, 0.05f, "照片描边"); colors(p.strokeColor)
            range(p.shadow, 0f, 0.15f, "照片阴影"); range(p.shadowOpacity, 0f, 1f, "阴影透明度")
            val layers = design.allLayers()
            require(layers.size <= MAX_LAYERS && layers.map { it.id }.distinct().size == layers.size) { "图层过多或标识重复" }
            fun visit(node: FrameLayer, depth: Int) {
                require(depth <= 3 && node.id.length in 1..96 && node.name.length <= 80) { "图层信息或分组深度无效" }
                require(node.kind == FrameLayerKind.GROUP || node.children.isEmpty()) { "只有分组可以包含图层" }
                require(node.kind != FrameLayerKind.GROUP || node.repeat == FrameRepeat.NONE) { "重复效果用于单个图层" }
                range(node.x, -8f, 8f, "横向位置"); range(node.y, -8f, 8f, "纵向位置")
                node.width?.let { range(it, 0.001f, 16f, "宽度") }; node.height?.let { range(it, 0.001f, 16f, "高度") }
                range(node.maxWidth, 0.01f, 16f, "文字宽度"); range(node.scale, 0.1f, 4f, "图层大小")
                range(node.rotation, -180f, 180f, "旋转"); range(node.opacity, 0f, 1f, "透明度")
                range(node.gap, 0f, 1f, "间距"); range(node.padding, 0f, 1f, "内边距")
                range(node.repeatStep, 0.01f, 2f, "重复间距"); require(node.z in -1000..1000) { "图层顺序无效" }
                if (node.kind == FrameLayerKind.TEXT) {
                    val text = node.text
                    require(text.font in listOf("system:sans-serif", "system:serif", "system:monospace") || assetId.matches(text.font)) { "字体引用无效" }
                    range(text.size, 0.005f, 0.25f, "字号"); range(text.letterSpacing, -0.005f, 0.03f, "字距")
                    range(text.minScale, 0.25f, 1f, "最小字号")
                    require(text.weight in 100..900 && text.maxLines in 1..8 && text.parts.size <= 32 && text.separator.length <= 16) { "文字样式无效" }
                    colors(text.color)
                    text.parts.forEach {
                        FrameTextFormatter.validate(it.text, it.literal); it.color?.let { value -> colors(value) }
                        require(it.weight == null || it.weight in 100..900) { "片段字重无效" }
                    }
                }
                node.decoration?.let { d ->
                    colors(d.color, d.strokeColor); range(d.stroke, 0f, 0.05f, "描边"); range(d.radius, 0f, 0.5f, "圆角")
                    range(d.shadow, 0f, 0.15f, "阴影"); range(d.dash, 0f, 0.1f, "虚线")
                }
                node.condition?.let { validateCondition(it, 0) }
                node.children.forEach { visit(it, depth + 1) }
            }
            design.layers.forEach { visit(it, 0) }
        }
    }

    private fun validateCondition(c: FrameCondition, depth: Int) {
        require(depth <= 3 && c.conditions.size <= 8) { "可见条件过多" }
        require(c.op in setOf("present", "hasLogo", "eq", "all", "any")) { "不支持的可见条件" }
        if (c.op == "present") require(c.field in FrameTextFormatter.fields) { "不支持的条件字段" }
        if (c.op == "eq") require(c.param in setOf("showTime", "showGps", "custom") && c.value.length <= 2048) { "条件参数无效" }
        c.conditions.forEach { validateCondition(it, depth + 1) }
    }

    private fun length(value: Any?, tokens: JSONObject, fallback: Float): Float = when (value) {
        null, JSONObject.NULL -> fallback
        is Number -> value.toFloat()
        is JSONObject -> {
            val base = tokens.get(value.getString("token"))
            require(base is Number) { "尺寸变量必须是数值" }
            base.toFloat() * value.optDouble("scale", 1.0).toFloat()
        }
        else -> error("尺寸必须是相对数值")
    }
    private fun point(value: String): FramePoint = when (val key = value.uppercase(Locale.ROOT).replace('-', '_')) {
        "LEFT_CENTER", "CENTER_LEFT" -> FramePoint.LEFT
        "RIGHT_CENTER", "CENTER_RIGHT" -> FramePoint.RIGHT
        "TOP_CENTER", "CENTER_TOP" -> FramePoint.TOP
        "BOTTOM_CENTER", "CENTER_BOTTOM" -> FramePoint.BOTTOM
        else -> enum(key)
    }
    private inline fun <reified T : Enum<T>> enum(value: String): T = enumValues<T>()
        .firstOrNull { it.name == value.uppercase(Locale.ROOT) } ?: error("不支持的模板选项：" + value)
    private fun colors(vararg values: String) { require(values.all(color::matches)) { "颜色必须为 #RRGGBB" } }
    private fun range(value: Float, min: Float, max: Float, name: String) {
        require(value.isFinite() && value in min..max) { name + "超出允许范围" }
    }
    private fun JSONObject.float(key: String, fallback: Float): Float = if (has(key)) getDouble(key).toFloat() else fallback
    private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key).takeIf { it.isNotBlank() }
    private fun objects(array: JSONArray): List<JSONObject> {
        require(array.length() <= MAX_LAYERS) { "模板条目过多" }
        return (0 until array.length()).map(array::getJSONObject)
    }
    private fun stringMap(json: JSONObject?): Map<String, String> =
        json?.keys()?.asSequence()?.associateWith { json.getString(it) } ?: emptyMap()
    private fun defaultName(kind: FrameLayerKind): String = when (kind) {
        FrameLayerKind.GROUP -> "分组"; FrameLayerKind.TEXT -> "文字"; FrameLayerKind.IMAGE -> "图片"
        FrameLayerKind.SHAPE -> "色块"; FrameLayerKind.LINE -> "分隔线"
    }
}
