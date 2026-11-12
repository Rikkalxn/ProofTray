package org.openbridge.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.openbridge.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
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
                    Text(appName, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
                    Text("v$version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AboutAction("快速上手", BridgeGlyph.PHOTOS) { help = true }
                    AboutAction("开源与许可", BridgeGlyph.INFO) { licenses = true }
                    FilledTonalButton(onClick = {
                        val details = "$appName $version (build ${info.longVersionCode})\n" +
                            "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
                            "${Build.MANUFACTURER} ${Build.MODEL}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}\n${context.packageName}"
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("$appName 应用信息", details))
                        scope.launch { snackbar.showSnackbar("应用信息已复制") }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("复制应用信息")
                    }
                }
            }
        }
    }
    if (licenses) LicenseSheet { licenses = false }
    if (help) QuickStartSheet { help = false }
}

@Composable
internal fun BrandIcon(modifier: Modifier = Modifier) {
    Surface(modifier, color = colorResource(R.color.launcher_background), shape = RoundedCornerShape(22)) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
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
private fun AboutAction(title: String, glyph: BridgeGlyph, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BridgeIcon(glyph, tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            BridgeIcon(BridgeGlyph.FORWARD, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun LicenseSheet(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    val openLink: (String) -> Unit = { url ->
        error = null
        try { uriHandler.openUri(url) }
        catch (_: Exception) { error = "无法打开链接，请检查是否已安装浏览器。" }
    }
    val furbleLicense = "https://raw.githubusercontent.com/gkoh/furble/master/LICENSE"
    val librawSource = "https://raw.githubusercontent.com/LibRaw/LibRaw/0.22.3"
    BridgeSheet("开源与许可", onDismiss, actions = { TextButton(onClick = onDismiss) { Text("完成") } }, compact = true) {
        LicenseEntry(stringResource(R.string.app_name), "Apache 2.0", "https://www.apache.org/licenses/LICENSE-2.0", onOpen = openLink)
        LicenseEntry("furble", "MIT", furbleLicense, noticeUrl = furbleLicense, onOpen = openLink)
        LicenseEntry("LibRaw 0.22.3", "LGPL-2.1", "$librawSource/LICENSE.LGPL", noticeUrl = "$librawSource/COPYRIGHT", onOpen = openLink)
        LicenseEntry("Nordic Android BLE", "BSD-3-Clause", "https://github.com/nordicsemi/Android-BLE-Library/blob/2.11.0/LICENSE", onOpen = openLink)
        LicenseEntry("JSch", "BSD-3-Clause", "https://github.com/mwiede/jsch/blob/jsch-2.28.8/LICENSE.txt", onOpen = openLink)
        LicenseEntry("Okio", "Apache 2.0", "https://github.com/square/okio/blob/3.16.2/LICENSE.txt", onOpen = openLink)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LicenseEntry(name: String, license: String, licenseUrl: String, noticeUrl: String? = null, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { onOpen(licenseUrl) }) { Text(license) }
            if (noticeUrl != null) TextButton(onClick = { onOpen(noticeUrl) }) { Text("NOTICE") }
        }
    }
}

@Composable
private fun QuickStartSheet(onDismiss: () -> Unit) {
    BridgeSheet("快速上手", onDismiss, actions = { TextButton(onClick = onDismiss) { Text("知道了") } }, compact = true) {
        AboutDetail(BridgeGlyph.CAMERA, "连接", "在“相机”页配对并连接相机。")
        AboutDetail(BridgeGlyph.FILTER, "导入", "长按照片开始多选，再导入所选照片。")
        AboutDetail(BridgeGlyph.PHOTOS, "图库", "整理、比较、调色与导出。")
    }
}
