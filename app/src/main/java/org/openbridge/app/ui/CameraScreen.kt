package org.openbridge.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.openbridge.app.camera.CameraConnection
import org.openbridge.app.camera.CameraMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraScreen(state: LibraryUiState, actions: LibraryActions, onBrowse: () -> Unit) {
    var modeIndex by rememberSaveable { mutableStateOf(0) }
    var host by rememberSaveable { mutableStateOf("") }
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("15740") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var specifyHotspot by rememberSaveable { mutableStateOf(false) }
    var showRegistration by rememberSaveable { mutableStateOf(false) }
    var showBluetoothHistory by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view, state.ble.pairingInProgress) {
        val previous = view.keepScreenOn
        view.keepScreenOn = state.ble.pairingInProgress
        onDispose { view.keepScreenOn = previous }
    }
    val mode = if (modeIndex == 0) CameraMode.AP else CameraMode.STA
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionCard(state.camera.cameraName.ifBlank { "相机连接" }, state.camera.stage, BridgeGlyph.CAMERA) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(if (state.camera.connected) "Wi‑Fi 已连接" else "Wi‑Fi 未连接", active = state.camera.connected)
                    if (state.ble.systemPaired) StatusBadge("系统已配对", active = true)
                    else if (state.ble.authenticated) StatusBadge("身份已验证", active = true)
                    if (state.camera.busy) LoadingIndicator(modifier = Modifier.size(32.dp))
                }
                if (state.camera.total > 0) {
                    LinearProgressIndicator(progress = { (state.camera.indexed.toFloat() / state.camera.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                    Text("已读取 ${state.camera.indexed} / ${state.camera.total} 项", style = MaterialTheme.typography.bodySmall)
                }
                state.camera.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (state.camera.connected || state.camera.busy || state.ble.wifiRequested) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.camera.connected) FilledTonalButton(onClick = actions.onRefreshCamera, enabled = !state.camera.busy) {
                            Text("刷新照片")
                        }
                        TextButton(onClick = actions.onDisconnectCamera) { Text(if (state.camera.connecting) "取消连接" else "断开连接") }
                    }
                }
                if (state.camera.connected) PrimaryAction("开始选片", onBrowse, modifier = Modifier.fillMaxWidth(), glyph = BridgeGlyph.PHOTOS)
            }
        }
        item {
            SectionCard("通过 Wi‑Fi 传图", "选择相机热点，或让手机与相机加入同一网络。", BridgeGlyph.WIFI) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("相机热点", "同一局域网").forEachIndexed { index, label ->
                        SegmentedButton(selected = modeIndex == index, onClick = { modeIndex = index },
                            shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                    }
                }
                if (mode == CameraMode.AP) {
                    Text("在相机上开启 Wi‑Fi 热点，手机连接后即可继续。", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = actions.onOpenWifi, modifier = Modifier.fillMaxWidth()) { Text("打开手机 Wi‑Fi") }
                    TextButton(onClick = { specifyHotspot = !specifyHotspot }) {
                        Text(if (specifyHotspot) "收起热点信息" else "手动填写热点信息")
                    }
                    AnimatedVisibility(specifyHotspot) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(ssid, { ssid = it }, label = { Text("热点名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(password, { password = it }, label = { Text("热点密码") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                        }
                    }
                } else {
                    Text("手机与相机需连接同一路由器。可以搜索相机，也可以直接填写地址。", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = actions.onDiscoverCamera, modifier = Modifier.fillMaxWidth()) { Text("搜索局域网相机") }
                    state.cameraEndpoints.forEach { endpoint ->
                        OutlinedButton(onClick = { host = endpoint.host; port = endpoint.port.toString() }, modifier = Modifier.fillMaxWidth()) {
                            Column { Text(endpoint.name); Text(endpoint.host, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    OutlinedTextField(host, { host = it }, label = { Text("相机 IP 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级选项" else "高级连接选项") }
                AnimatedVisibility(advanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (mode == CameraMode.AP) OutlinedTextField(host, { host = it }, label = { Text("相机 IP，留空使用热点网关") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(port, { port = it }, label = { Text("PTP/IP 端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (state.camera.connected) TextButton(onClick = { showRegistration = true }) { Text("首次 STA：登记当前手机") }
                    }
                }
                PrimaryAction("连接相机", onClick = {
                    actions.onConnectCamera(CameraConnection(mode, host, port.toInt(),
                        if (specifyHotspot) ssid else "", if (specifyHotspot) password else ""))
                }, modifier = Modifier.fillMaxWidth(), enabled = state.camera.canConnect && (port.toIntOrNull() ?: 0) in 1..65535,
                    glyph = BridgeGlyph.CAMERA)
            }
        }
        item {
            SectionCard("蓝牙配对", "先完成配对，再唤醒相机的 Wi‑Fi。原片通过 Wi‑Fi 传输。", BridgeGlyph.BLUETOOTH) {
                Text(state.ble.message, style = MaterialTheme.typography.bodyMedium)
                if (state.ble.history.isNotEmpty()) {
                    TextButton(onClick = { showBluetoothHistory = !showBluetoothHistory }) {
                        Text(if (showBluetoothHistory) "收起连接过程" else "查看连接过程")
                    }
                    AnimatedVisibility(showBluetoothHistory) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.ble.history.forEachIndexed { index, message ->
                                Text("${index + 1}. $message", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                FilledTonalButton(onClick = actions.onScanBle, enabled = !state.ble.scanning && !state.ble.pairingInProgress,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.ble.scanning) "正在搜索相机…" else "搜索蓝牙相机")
                }
                state.ble.cameras.forEach { camera ->
                    OutlinedButton(onClick = { actions.onConnectBle(camera) }, enabled = !state.ble.pairingInProgress,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("${camera.name} · ${if (camera.classic) "完成系统配对" else "连接"}")
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = actions.onDiscoverClassic, enabled = !state.ble.pairingInProgress) { Text("完成系统配对") }
                    if (state.ble.systemPaired || state.settings.transferAddress.isNotBlank()) {
                        FilledTonalButton(onClick = actions.onWakeWifi, enabled = !state.ble.pairingInProgress) {
                            Text(if (state.ble.authenticated) "开启相机 Wi-Fi" else "连接蓝牙并开启 Wi-Fi")
                        }
                    }
                }
                if (state.ble.wifiRequested) Text("Wi-Fi 优先，蓝牙自动传图暂候。连接后可到导入页选片。", style = MaterialTheme.typography.bodySmall)
                if (state.ble.pairingInProgress || state.ble.authenticated) TextButton(onClick = actions.onDisconnectControl) {
                    Text(if (state.ble.pairingInProgress) "停止等待配对" else "断开蓝牙")
                }
                Text("首次使用时，在相机中进入“连接至智能设备 → 开始配对”，并按机身与系统提示确认。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text("连接功能仍在实机验证阶段。遇到问题时，保留连接提示和响应码，便于定位。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showRegistration) AlertDialog(onDismissRequest = { showRegistration = false }, title = { Text("登记当前手机") },
        text = { Text("此步骤用于首次 STA 连接。相机需已连接，并支持主机登记。开始后请核对机身配对码。") },
        confirmButton = { TextButton(onClick = { showRegistration = false; actions.onBeginRegistration() }) { Text("开始登记") } },
        dismissButton = { TextButton(onClick = { showRegistration = false }) { Text("取消") } })
    state.camera.pairingCode?.let { code ->
        AlertDialog(onDismissRequest = {}, title = { Text("核对机身配对码") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(code, style = MaterialTheme.typography.headlineLarge)
                Text("确认与相机显示一致，并在机身完成确认后，再继续。")
            } },
            confirmButton = { TextButton(onClick = actions.onConfirmRegistration) { Text("已核对，完成登记") } },
            dismissButton = { TextButton(onClick = actions.onDisconnectCamera) { Text("取消并断开") } })
    }
}
