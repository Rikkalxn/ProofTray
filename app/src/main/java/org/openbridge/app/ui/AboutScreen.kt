package org.openbridge.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openbridge.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val info = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val version = info.versionName.orEmpty()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var licenses by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text("关于") }, navigationIcon = {
                IconButton(onClick = onBack) { BridgeIcon(BridgeGlyph.BACK, "返回设置") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    BrandIcon(Modifier.size(108.dp))
                    Text("OpenBridge", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
                    Text("先选片，再传图。", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AboutBadge("v${version.substringBefore('-')}")
                        AboutBadge("开发预览版")
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeatureTile("挑选", BridgeGlyph.FILTER, Modifier.weight(1f))
                    FeatureTile("传图", BridgeGlyph.DOWNLOAD, Modifier.weight(1f))
                    FeatureTile("调色", BridgeGlyph.PHOTOS, Modifier.weight(1f))
                }
            }
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("把喜欢的照片带走", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("OpenBridge 是面向尼康相机的选片与传图工具。先浏览与筛选，再把选中的照片带到手机，继续整理和后期。",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        AboutDetail(BridgeGlyph.CHECK, "原片和副本，分开记录", "便携 JPEG 与 RAW 原件分别管理，导入状态清楚可见。")
                        AboutDetail(BridgeGlyph.LOCATION, "连接与位置，由你决定", "自动下载和位置同步默认关闭，按需开启；照片和位置不上传到服务器。")
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AboutAction("快速上手", "连接、挑选、导入与导出", BridgeGlyph.PHOTOS) { help = true }
                    AboutAction("开源与许可", "项目与第三方组件说明", BridgeGlyph.INFO) { licenses = true }
                    FilledTonalButton(onClick = {
                        val details = "OpenBridge $version (build ${info.longVersionCode})\n" +
                            "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
                            "${Build.MANUFACTURER} ${Build.MODEL}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}\n${context.packageName}"
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenBridge 应用信息", details))
                        scope.launch { snackbar.showSnackbar("应用信息已复制") }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("复制应用信息")
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(version, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("机型与固件兼容性持续验证中。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
    }
    if (licenses) LicenseSheet { licenses = false }
    if (help) QuickStartSheet { help = false }
}

@Composable
internal fun BrandIcon(modifier: Modifier = Modifier) {
    Surface(modifier, color = colorResource(R.color.launcher_background), shape = RoundedCornerShape(30.dp), shadowElevation = 4.dp) {
        Image(painterResource(R.drawable.ic_launcher_foreground), "OpenBridge 标志",
            Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
    }
}

@Composable
private fun AboutBadge(label: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FeatureTile(label: String, glyph: BridgeGlyph, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BridgeIcon(glyph, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AboutDetail(glyph: BridgeGlyph, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        BridgeIcon(glyph, tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AboutAction(title: String, subtitle: String, glyph: BridgeGlyph, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BridgeIcon(glyph, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BridgeIcon(BridgeGlyph.FORWARD, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun LicenseSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notice by produceState("正在读取许可…") {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/furble-MIT.txt").bufferedReader().use { it.readText() } }
                .getOrDefault("暂时无法读取许可文本。")
        }
    }
    BridgeSheet("开源与许可", onDismiss, actions = { TextButton(onClick = onDismiss) { Text("完成") } }) {
        Text("OpenBridge 计划完全开源，项目整体许可证尚未选定。", style = MaterialTheme.typography.bodyLarge)
        Text("furble · MIT", style = MaterialTheme.typography.titleMedium)
        Text("配对相关组件保留了上游版权与许可。", style = MaterialTheme.typography.bodyMedium)
        SelectionContainer { Text(notice, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun QuickStartSheet(onDismiss: () -> Unit) {
    BridgeSheet("快速上手", onDismiss, actions = { TextButton(onClick = onDismiss) { Text("知道了") } }) {
        AboutDetail(BridgeGlyph.CAMERA, "01  连接你的相机", "在相机页完成连接；蓝牙设备关联和自动下载策略在设置中管理。")
        AboutDetail(BridgeGlyph.FILTER, "02  挑好，再导入", "进入底部“导入”，用预览和组合筛选挑片，再导入需要的照片。")
        AboutDetail(BridgeGlyph.PHOTOS, "03  在图库里继续处理", "比较、调色或导出。移除只改列表；删除手机文件会另行确认。")
    }
}
