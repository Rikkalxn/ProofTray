package org.prooftray.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.prooftray.app.domain.allLayers
import org.prooftray.app.domain.FrameAspect
import org.prooftray.app.domain.FrameBackgroundKind
import org.prooftray.app.domain.FrameCanvas
import org.prooftray.app.domain.FrameLayerKind
import org.prooftray.app.domain.FramePhoto
import org.prooftray.app.domain.FrameInsets
import org.prooftray.app.frame.FrameFont

@Composable
internal fun FrameCanvasProperties(editor: FrameEditorState, width: Int, height: Int, fonts: List<FrameFont>,
    onImage: () -> Unit, onFont: () -> Unit, onGps: (Boolean) -> Unit) {
    val template = editor.template
    val design = template.forPhoto(width, height)
    val canvas = design.canvas
    val photo = design.photo
    fun canvas(change: (FrameCanvas) -> FrameCanvas) = editor.updateDesign(width, height) { it.copy(canvas = change(it.canvas)) }
    fun photo(change: (FramePhoto) -> FramePhoto) = editor.updateDesign(width, height) { it.copy(photo = change(it.photo)) }
    val begin = editor::beginGesture
    val end = editor::endGesture
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FrameChoice("画幅", canvas.aspect, FrameAspect.entries, {
            when (it) {
                FrameAspect.AUTO -> "随边框"; FrameAspect.PHOTO -> "照片比例"; FrameAspect.SQUARE -> "1:1"
                FrameAspect.PORTRAIT -> "4:5"; FrameAspect.STORY -> "9:16"; FrameAspect.LANDSCAPE -> "16:9"
                FrameAspect.CLASSIC -> "3:2"; FrameAspect.FOUR_THREE -> "4:3"
            }
        }) { value -> canvas { it.copy(aspect = value) } }
        FrameSwitch("横竖图分别调整", template.portrait != null || template.landscape != null) { editor.separate(width, height, it) }
        val insets = canvas.insets
        val linked = editor.uniformInsets && insets.top == insets.bottom && insets.top == insets.left && insets.top == insets.right
        FrameSwitch("四边一致", linked) { editor.setUniformInsets(width, height, it) }
        if (linked) FrameSlider("边距", insets.top, 0f..1f,
            { v -> canvas { it.copy(insets = FrameInsets(v, v, v, v)) } }, begin, end)
        else {
            FrameSlider("上边距", insets.top, 0f..1f, { v -> canvas { it.copy(insets = it.insets.copy(top = v)) } }, begin, end)
            FrameSlider("下边距", insets.bottom, 0f..1f, { v -> canvas { it.copy(insets = it.insets.copy(bottom = v)) } }, begin, end)
            FrameSlider("左边距", insets.left, 0f..1f, { v -> canvas { it.copy(insets = it.insets.copy(left = v)) } }, begin, end)
            FrameSlider("右边距", insets.right, 0f..1f, { v -> canvas { it.copy(insets = it.insets.copy(right = v)) } }, begin, end)
        }
        FrameChoice("背景", canvas.background.kind, FrameBackgroundKind.entries, {
            when (it) {
                FrameBackgroundKind.SOLID -> "纯色"; FrameBackgroundKind.GRADIENT -> "渐变"; FrameBackgroundKind.BLUR -> "照片模糊"
                FrameBackgroundKind.TEXTURE -> "纸纹"; FrameBackgroundKind.IMAGE -> "自选图片"
            }
        }) { kind -> canvas { it.copy(background = it.background.copy(kind = kind)) } }
        FrameColor("背景颜色", canvas.background.color) { color -> canvas { it.copy(background = it.background.copy(color = color)) } }
        when (canvas.background.kind) {
            FrameBackgroundKind.GRADIENT -> FrameColor("渐变末色", canvas.background.secondColor) { color ->
                canvas { it.copy(background = it.background.copy(secondColor = color)) }
            }
            FrameBackgroundKind.BLUR -> {
                FrameSlider("模糊", canvas.background.blur, 0f..0.15f,
                    { v -> canvas { it.copy(background = it.background.copy(blur = v)) } }, begin, end)
                FrameSlider("压暗", canvas.background.dim, 0f..0.9f, { v -> canvas { it.copy(background = it.background.copy(dim = v)) } }, begin, end)
            }
            FrameBackgroundKind.TEXTURE -> FrameSlider("纹理大小", canvas.background.textureSize, 0.02f..2f,
                { v -> canvas { it.copy(background = it.background.copy(textureSize = v)) } }, begin, end)
            FrameBackgroundKind.IMAGE -> TextButton(onClick = onImage) { Text("选择背景图片") }
            else -> Unit
        }
        FrameSlider("背景透明度", canvas.background.opacity, 0f..1f,
            { v -> canvas { it.copy(background = it.background.copy(opacity = v)) } }, begin, end)
        HorizontalDivider()
        Text("照片", style = MaterialTheme.typography.titleSmall)
        FrameSlider("大小", photo.scale, 0.1f..1.5f, { v -> photo { it.copy(scale = v) } }, begin, end)
        FrameSlider("水平位置", photo.x, -1f..1f, { v -> photo { it.copy(x = v) } }, begin, end)
        FrameSlider("垂直位置", photo.y, -1f..1f, { v -> photo { it.copy(y = v) } }, begin, end)
        FrameSlider("圆角", photo.radius, 0f..0.5f, { v -> photo { it.copy(radius = v) } }, begin, end)
        FrameSlider("阴影", photo.shadow, 0f..0.15f, { v -> photo { it.copy(shadow = v) } }, begin, end)
        if (photo.shadow > 0) FrameSlider("阴影浓度", photo.shadowOpacity, 0f..1f, { v -> photo { it.copy(shadowOpacity = v) } }, begin, end)
        FrameSlider("描边", photo.stroke, 0f..0.05f, { v -> photo { it.copy(stroke = v) } }, begin, end)
        if (photo.stroke > 0) FrameColor("描边颜色", photo.strokeColor) { v -> photo { it.copy(strokeColor = v) } }
        HorizontalDivider()
        FrameSwitch("显示时间", template.showTime) { v -> editor.change { it.copy(showTime = v) } }
        FrameSwitch("显示位置", template.showGps, onGps)
        OutlinedTextField(template.custom, { value -> editor.change { it.copy(custom = value.take(2048)) } },
            label = { Text("自定义文字") }, modifier = Modifier.fillMaxWidth())
        FrameChoice("全部文字字体", design.allLayers().firstOrNull { it.kind == FrameLayerKind.TEXT }?.text?.font ?: "system:sans-serif",
            fonts.map { it.id }, { id -> fonts.find { it.id == id }?.name ?: "未安装字体" },
            extraItem = "导入字体…" to onFont, change = editor::allFonts)
    }
}
