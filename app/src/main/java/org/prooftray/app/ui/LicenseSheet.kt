package org.prooftray.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val noticesFile = "THIRD_PARTY_NOTICES.md"
private val sourceLink = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")

private fun licenseTitle(file: String): String = when (file) {
    noticesFile -> "第三方组件与来源"
    "ProofTray-Apache-2.0.txt" -> "ProofTray、AndroidX、Kotlin 与 Okio · Apache 2.0"
    "LibRaw-COPYRIGHT.txt" -> "LibRaw · 版权声明"
    "LibRaw-LICENSE.LGPL.txt" -> "LibRaw · LGPL 2.1"
    "LibRaw-LICENSE.CDDL.txt" -> "LibRaw · 上游备选许可 CDDL 1.0"
    "LibRaw-components.txt" -> "LibRaw · 内含组件声明"
    "libtiff-LICENSE.md" -> "libtiff · 许可证"
    "Adobe-DNG-SDK.txt" -> "Adobe DNG SDK · 许可协议"
    "Nordic-BLE-BSD.txt" -> "Nordic Android BLE · BSD"
    "JSch-licenses.txt" -> "JSch · 许可证与内含组件声明"
    "furble-MIT.txt" -> "furble · MIT"
    "Android-NDK-NOTICE.txt" -> "Android NDK · 运行库声明"
    else -> file
}

@Composable
internal fun LicenseSheet(onDismiss: () -> Unit) {
    val assets = LocalContext.current.assets
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val file = selected
    if (file == null) {
        val files = remember(assets) {
            assets.list("licenses").orEmpty().sortedWith(compareBy({ it != noticesFile }, { licenseTitle(it) }))
        }
        AppSheet("开源与许可", onDismiss, actions = { TextButton(onClick = onDismiss) { Text("完成") } }, compact = true) {
            files.forEach { name -> TextButton(onClick = { selected = name }) { Text(licenseTitle(name)) } }
        }
        return
    }

    val pages by produceState<List<String>?>(null, assets, file) {
        value = withContext(Dispatchers.IO) {
            try {
                assets.open("licenses/$file").bufferedReader().use { reader ->
                    reader.lineSequence().chunked(32).map { it.joinToString("\n") }.toList()
                }
            } catch (_: IOException) {
                listOf("无法读取许可文件")
            }
        }
    }
    AppSheet(licenseTitle(file), onDismiss, actions = { TextButton(onClick = { selected = null }) { Text("返回列表") } }, scrollable = false) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(pages ?: listOf("正在读取…")) { page ->
                SelectionContainer {
                    if (file == noticesFile) SourceNotice(page) else Text(page, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SourceNotice(page: String) {
    val color = MaterialTheme.colorScheme.primary
    val text = remember(page, color) {
        buildAnnotatedString {
            var offset = 0
            val styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
            for (match in sourceLink.findAll(page)) {
                append(page.substring(offset, match.range.first))
                val start = length
                append(match.groupValues[1])
                addLink(LinkAnnotation.Url(match.groupValues[2], styles), start, length)
                offset = match.range.last + 1
            }
            append(page.substring(offset))
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}
