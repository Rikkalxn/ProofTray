package org.prooftray.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.prooftray.app.domain.FrameAlign
import org.prooftray.app.domain.FrameDecoration
import org.prooftray.app.domain.FrameFieldFormat
import org.prooftray.app.domain.FrameFlow
import org.prooftray.app.domain.FrameLayer
import org.prooftray.app.domain.FrameLayerKind
import org.prooftray.app.domain.FramePoint
import org.prooftray.app.domain.FrameRegion
import org.prooftray.app.domain.FrameTextPart
import org.prooftray.app.frame.FrameFont
import org.prooftray.app.frame.FrameTextFormatter

@Composable
internal fun FrameLayerProperties(layer: FrameLayer, fonts: List<FrameFont>, nested: Boolean,
    sections: Set<FrameLayerSection>, onSection: (FrameLayerSection) -> Unit, change: ((FrameLayer) -> FrameLayer) -> Unit,
    begin: () -> Unit, end: () -> Unit, onImage: () -> Unit, onFont: () -> Unit, onDetach: () -> Unit,
    onField: (String) -> Unit, modifier: Modifier = Modifier) {
    var addingField by remember { mutableStateOf(false) }
    var editingPart by remember(layer.id) { mutableStateOf<Int?>(null) }
    var literal by remember { mutableStateOf(false) }
    var literalText by remember { mutableStateOf("") }
    DisposableEffect(layer.id) { onDispose(end) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExpandableSection(FrameLayerSection.CONTENT.title, FrameLayerSection.CONTENT in sections, { onSection(FrameLayerSection.CONTENT) }) {
            OutlinedTextField(layer.name, { value -> change { it.copy(name = value.take(80)) } }, label = { Text("名称") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (layer.kind == FrameLayerKind.TEXT) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    layer.text.parts.forEachIndexed { index, part ->
                        AssistChip(onClick = { editingPart = index }, label = {
                            Text(FrameTextFormatter.fields[part.text.removePrefix("{").removeSuffix("}")] ?: part.text.take(24))
                        })
                    }
                }
                Row {
                    Column {
                        TextButton(onClick = { addingField = true }) { Text("添加字段") }
                        DropdownMenu(addingField, { addingField = false }) {
                            FrameTextFormatter.fields.forEach { (field, title) ->
                                DropdownMenuItem(text = { Text(title) }, onClick = {
                                    addingField = false
                                    onField(field)
                                    if (layer.text.parts.size < 32) change {
                                        it.copy(text = it.text.copy(parts = it.text.parts + FrameTextPart("{" + field + "}")))
                                    }
                                })
                            }
                        }
                    }
                    TextButton(onClick = { literalText = ""; literal = true }) { Text("添加文字") }
                }
                FrameChoice("连接方式", layer.text.separator, listOf(" ", " · ", "  ", "\n", ""),
                    { when (it) { "\n" -> "换行"; "" -> "紧接"; " · " -> "间隔点"; "  " -> "宽间隔"; else -> "空格" } }) { value ->
                    change { it.copy(text = it.text.copy(separator = value,
                        maxLines = if (value == "\n") maxOf(it.text.maxLines, 4) else it.text.maxLines)) }
                }
            }
            if (layer.kind == FrameLayerKind.IMAGE) {
                TextButton(onClick = onImage) { Text(if (layer.imageId == null) "选择图片" else "替换图片") }
                FrameSwitch("跟随相机品牌", layer.autoLogo) { value -> change { it.copy(autoLogo = value) } }
            }
            if (layer.kind == FrameLayerKind.GROUP) {
                FrameChoice("排列", layer.flow, FrameFlow.entries, {
                    when (it) { FrameFlow.FREE -> "自由"; FrameFlow.ROW -> "横向"; FrameFlow.COLUMN -> "纵向" }
                }) { value -> change { it.copy(flow = value) } }
                FrameChoice("对齐", layer.align, FrameAlign.entries, ::alignName) { value -> change { it.copy(align = value) } }
                FrameSlider("间距", layer.gap, 0f..0.3f, { value -> change { it.copy(gap = value) } }, begin, end)
                FrameSlider("内边距", layer.padding, 0f..0.2f, { value -> change { it.copy(padding = value) } }, begin, end)
            }
        }
        if (layer.kind == FrameLayerKind.TEXT) {
            ExpandableSection(FrameLayerSection.TEXT_STYLE.title, FrameLayerSection.TEXT_STYLE in sections,
                { onSection(FrameLayerSection.TEXT_STYLE) }) {
                FrameChoice("字体", layer.text.font, fonts.map { it.id }, { id -> fonts.find { it.id == id }?.name ?: "未安装字体" },
                    extraItem = "导入字体…" to onFont) { font ->
                    change { it.copy(text = it.text.copy(font = font)) }
                }
                FrameSlider("字号", layer.text.size, 0.005f..0.25f, { value -> change { it.copy(text = it.text.copy(size = value)) } }, begin, end)
                FrameChoice("字重", layer.text.weight, listOf(100, 200, 300, 400, 500, 600, 700, 800, 900), Int::toString) { value ->
                    change { it.copy(text = it.text.copy(weight = value)) }
                }
                FrameSwitch("斜体", layer.text.italic) { value -> change { it.copy(text = it.text.copy(italic = value)) } }
                FrameColor("文字颜色", layer.text.color) { value -> change { it.copy(text = it.text.copy(color = value)) } }
                FrameSlider("字距", layer.text.letterSpacing, -0.005f..0.03f,
                    { value -> change { it.copy(text = it.text.copy(letterSpacing = value)) } }, begin, end)
                FrameChoice("文字对齐", layer.text.align, listOf(FrameAlign.START, FrameAlign.CENTER, FrameAlign.END), ::alignName) { value ->
                    change { it.copy(text = it.text.copy(align = value)) }
                }
                FrameChoice("最多行数", layer.text.maxLines, (1..8).toList(), Int::toString) { value ->
                    change { it.copy(text = it.text.copy(maxLines = value)) }
                }
            }
        }
        ExpandableSection(FrameLayerSection.GEOMETRY.title, FrameLayerSection.GEOMETRY in sections, { onSection(FrameLayerSection.GEOMETRY) }) {
            if (!nested) {
                FrameChoice("放置区域", layer.region, FrameRegion.entries, ::regionName) { value -> change { it.copy(region = value) } }
                FrameChoice("位置", layer.at, FramePoint.entries, ::pointName) { value ->
                    change { it.copy(at = value, pivot = value, x = 0f, y = 0f) }
                }
            } else TextButton(onClick = { end(); onDetach() }) { Text("移出分组") }
            FrameSlider("水平偏移", layer.x, -1f..1f, { value -> change { it.copy(x = value) } }, begin, end)
            FrameSlider("垂直偏移", layer.y, -1f..1f, { value -> change { it.copy(y = value) } }, begin, end)
            FrameSlider("旋转", layer.rotation, -180f..180f, { value -> change { it.copy(rotation = value) } }, begin, end, percent = false)
            Row {
                TextButton(onClick = { change { it.copy(rotation = 0f) } }) { Text("复位") }
                TextButton(onClick = { change { it.copy(rotation = -90f) } }) { Text("左转 90°") }
                TextButton(onClick = { change { it.copy(rotation = 90f) } }) { Text("右转 90°") }
            }
            if (layer.kind != FrameLayerKind.TEXT) {
                FrameSlider("宽度", layer.width ?: 0.1f, 0.001f..2f, { value -> change { it.copy(width = value, fillWidth = false) } }, begin, end)
                FrameSlider("高度", layer.height ?: 0.1f, 0.001f..2f, { value -> change { it.copy(height = value, fillHeight = false) } }, begin, end)
                TextButton(onClick = { change { it.copy(width = null, height = null) } }) { Text("自动尺寸") }
            } else {
                FrameSlider("文字宽度", layer.maxWidth, 0.05f..2f, { value -> change { it.copy(maxWidth = value, width = null) } }, begin, end)
            }
            FrameSlider("整体大小", layer.scale, 0.1f..4f, { value -> change { it.copy(scale = value) } }, begin, end)
        }
        ExpandableSection(FrameLayerSection.APPEARANCE.title, FrameLayerSection.APPEARANCE in sections, { onSection(FrameLayerSection.APPEARANCE) }) {
            FrameSlider("透明度", layer.opacity, 0f..1f, { value -> change { it.copy(opacity = value) } }, begin, end)
            FrameSwitch("背景与描边", layer.decoration != null) { enabled ->
                change { it.copy(decoration = if (enabled) it.decoration ?: FrameDecoration() else null) }
            }
            layer.decoration?.let { decoration ->
                fun update(transform: (FrameDecoration) -> FrameDecoration) = change { it.copy(decoration = transform(decoration)) }
                FrameSwitch("填充", decoration.fill) { value -> update { it.copy(fill = value) } }
                if (decoration.fill) FrameColor("填充颜色", decoration.color) { value -> update { it.copy(color = value) } }
                FrameSlider("描边", decoration.stroke, 0f..0.02f, { value -> update { it.copy(stroke = value) } }, begin, end)
                if (decoration.stroke > 0f) FrameColor("描边颜色", decoration.strokeColor) { value -> update { it.copy(strokeColor = value) } }
                FrameSlider("圆角", decoration.radius, 0f..0.25f, { value -> update { it.copy(radius = value) } }, begin, end)
                FrameSlider("阴影", decoration.shadow, 0f..0.10f, { value -> update { it.copy(shadow = value) } }, begin, end)
                FrameSwitch("椭圆", decoration.circle) { value -> update { it.copy(circle = value) } }
            }
        }
        ExpandableSection(FrameLayerSection.OTHER.title, FrameLayerSection.OTHER in sections, { onSection(FrameLayerSection.OTHER) }) {
            FrameSwitch("锁定", layer.locked) { value -> change { it.copy(locked = value) } }
            if (!nested) FrameSwitch("放在照片后面", layer.z < 0) { value -> change { it.copy(z = if (value) -10 else 10) } }
        }
    }
    if (literal) AlertDialog(onDismissRequest = { literal = false }, title = { Text("添加文字") },
        text = { OutlinedTextField(literalText, { literalText = it.take(2048) }, label = { Text("文字") }) },
        confirmButton = { TextButton(enabled = literalText.isNotBlank() && layer.text.parts.size < 32, onClick = {
            change { it.copy(text = it.text.copy(parts = it.text.parts + FrameTextPart(literalText, literal = true))) }; literal = false
        }) { Text("添加") } }, dismissButton = { TextButton(onClick = { literal = false }) { Text("取消") } })
    editingPart?.let { index -> layer.text.parts.getOrNull(index)?.let { part ->
        FramePartDialog(part, onDismiss = { editingPart = null }, onSave = { updated ->
            change { node -> node.copy(text = node.text.copy(parts = node.text.parts.toMutableList().apply { set(index, updated) })) }
            editingPart = null
        }, onRemove = {
            change { node -> node.copy(text = node.text.copy(parts = node.text.parts.filterIndexed { i, _ -> i != index })) }
            editingPart = null
        })
    } }
}

@Composable
private fun FramePartDialog(part: FrameTextPart, onDismiss: () -> Unit, onSave: (FrameTextPart) -> Unit, onRemove: () -> Unit) {
    var draft by remember(part) { mutableStateOf(part) }
    val error = runCatching { FrameTextFormatter.validate(draft.text, draft.literal) }.exceptionOrNull()?.message
    val formats = when {
        draft.literal -> listOf(FrameFieldFormat.DEFAULT)
        draft.text.contains("{shutter}") ->
            listOf(FrameFieldFormat.DEFAULT, FrameFieldFormat.VALUE, FrameFieldFormat.SECONDS, FrameFieldFormat.RECIPROCAL)
        draft.text.contains("{gps}") -> listOf(FrameFieldFormat.DEFAULT, FrameFieldFormat.DMS)
        listOf("{focal}", "{focal35}", "{aperture}", "{iso}").any { draft.text.contains(it) } ->
            listOf(FrameFieldFormat.DEFAULT, FrameFieldFormat.VALUE)
        else -> listOf(FrameFieldFormat.DEFAULT)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑内容") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft.text, { draft = draft.copy(text = it.take(2048)) }, isError = error != null, label = { Text("内容") })
            FrameSwitch("自动填入照片信息", !draft.literal) { draft = draft.copy(literal = !it) }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            if (formats.size > 1) FrameChoice("格式", draft.format, formats, {
                when (it) {
                    FrameFieldFormat.DEFAULT -> "数值与单位"; FrameFieldFormat.VALUE -> "只显示数值"
                    FrameFieldFormat.SECONDS -> "秒数"; FrameFieldFormat.RECIPROCAL -> "倒数快门"; FrameFieldFormat.DMS -> "度分秒"
                }
            }) { draft = draft.copy(format = it) }
            FrameSwitch("单独字重", draft.weight != null) { draft = draft.copy(weight = if (it) 700 else null) }
            draft.weight?.let { weight ->
                FrameChoice("字重", weight, listOf(300, 400, 500, 600, 700, 800, 900), Int::toString) { draft = draft.copy(weight = it) }
            }
            FrameSwitch("单独颜色", draft.color != null) { draft = draft.copy(color = if (it) "#98544B" else null) }
            draft.color?.let { color -> FrameColor("颜色", color) { draft = draft.copy(color = it) } }
            TextButton(onClick = onRemove) { Text("删除此内容", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = error == null, onClick = { onSave(draft) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal fun alignName(value: FrameAlign): String = when (value) {
    FrameAlign.START -> "起始"; FrameAlign.CENTER -> "居中"; FrameAlign.END -> "末尾"; FrameAlign.SPACE_BETWEEN -> "均匀分布"
}
internal fun regionName(value: FrameRegion): String = when (value) {
    FrameRegion.CANVAS -> "整个画布"; FrameRegion.PHOTO -> "照片"; FrameRegion.TOP -> "顶部留白"
    FrameRegion.RIGHT -> "右侧留白"; FrameRegion.BOTTOM -> "底部留白"; FrameRegion.LEFT -> "左侧留白"
}
internal fun pointName(value: FramePoint): String = when (value) {
    FramePoint.TOP_LEFT -> "左上"; FramePoint.TOP -> "上方居中"; FramePoint.TOP_RIGHT -> "右上"
    FramePoint.LEFT -> "左侧居中"; FramePoint.CENTER -> "居中"; FramePoint.RIGHT -> "右侧居中"
    FramePoint.BOTTOM_LEFT -> "左下"; FramePoint.BOTTOM -> "下方居中"; FramePoint.BOTTOM_RIGHT -> "右下"
}
