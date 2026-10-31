package org.openbridge.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openbridge.app.camera.BleCamera
import org.openbridge.app.data.BluetoothRole
import org.openbridge.app.data.PowerProfile
import org.openbridge.app.domain.AutoDownloadKind
import org.openbridge.app.domain.DownloadVariant

@Composable
fun SettingsScreen(state: LibraryUiState, actions: LibraryActions, onWifiConnection: () -> Unit, onAbout: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { DevicesSettings(state, actions) }
        item {
            SectionCard("自动下载", "准备好相机连接后，在后台接收照片。", BridgeGlyph.DOWNLOAD) {
                SettingSwitch("开启自动下载", state.settings.autoDownload, actions.onSetAutoDownload)
                ChoiceRow(AutoDownloadKind.entries, state.settings.autoKind, AutoDownloadKind::label, actions.onSetAutoKind)
                Text(if (state.automatic.checking && state.ble.pairingInProgress) state.ble.message else state.automatic.message,
                    style = MaterialTheme.typography.bodyMedium)
                if (state.settings.autoKind == AutoDownloadKind.CAMERA_JPEG) {
                    Text("连接就绪后立即检查待传照片，此后由 BLE 通知触发蓝牙下载。请在相机启用自动选择发送。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("使用相机标记的待传列表，可能包含此前手动标记的照片。便携 JPEG 不表示 RAW 原片已备份。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = actions.onCheckBluetoothTransfers,
                        enabled = !state.automatic.checking) {
                        Text(if (state.automatic.checking) "正在连接并检查…" else "检查相机待传照片 / 重试")
                    }
                    if (!state.automatic.checking && state.automatic.lastCheckResult.isNotBlank()) {
                        Text(state.automatic.lastCheckResult, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("原图使用 Wi-Fi。准备完成后只监测本次连接新增的文件；断开后重建基线，期间拍摄的照片可手动导入。",
                        style = MaterialTheme.typography.bodySmall)
                    if (state.settings.autoKind == AutoDownloadKind.JPEG) Text("JPEG 原图要求机身实际拍摄 JPEG；只拍 RAW 时可选便携 JPEG。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onWifiConnection) { Text("配置 Wi-Fi 连接") }
                }
                Text("退出界面后由前台服务继续处理；系统强制停止后需重新打开并连接相机。", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SectionCard("功耗与响应", "按任务使用连接和定位，默认不扫描、不采集位置。", BridgeGlyph.SETTINGS) {
                ChoiceRow(PowerProfile.entries, state.settings.powerProfile, PowerProfile::label, actions.onSetPowerProfile)
                val profile = state.settings.powerProfile
                Text("Wi-Fi 事件优先，约每 ${profile.pollSeconds} 秒查漏；位置采样间隔约 ${profile.locationSeconds} 秒。",
                    style = MaterialTheme.typography.bodyMedium)
                Text("自动下载开启时，蓝牙空闲等待通知；断线后低功耗监听所选相机的待传广播，有照片才重连。等待时不持有 CPU 唤醒锁。",
                    style = MaterialTheme.typography.bodySmall)
                Text("Wi-Fi 连接本身仍有功耗；系统休眠时查漏可能延后。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = actions.onOpenAppSettings) { Text("系统权限与电池管理") }
            }
        }
        item {
            SectionCard("位置同步", "把手机的新鲜定位发送给相机，供后续拍摄使用。", BridgeGlyph.LOCATION) {
                SettingSwitch("同步 GPS 信息", state.settings.locationSync, actions.onSetLocationSync)
                Text(state.location.message, style = MaterialTheme.typography.bodyMedium)
                Text("优先复用 BLE；机型支持时也可通过现有 Wi-Fi 同步。没有可用相机连接时不启动 GPS 采样。",
                    style = MaterialTheme.typography.bodySmall)
                Text("BLE 待机时按机身的位置请求工作；相机端需允许使用手机位置，也可立即同步一次。",
                    style = MaterialTheme.typography.bodySmall)
                Text("定位最长等待 30 秒，随后停止采样；拒绝过期或精度不足的位置。已有照片不会按下载时的位置补写。",
                    style = MaterialTheme.typography.bodySmall)
                Text("定位失败会延长重试间隔，可点“立即同步一次”重试。", style = MaterialTheme.typography.bodySmall)
                if (state.settings.locationSync) TextButton(onClick = { actions.onSetLocationSync(true) }) {
                    Text(if (state.location.running) "立即同步一次" else "在前台恢复位置同步")
                }
            }
        }
        item {
            SectionCard("手动导入", "与自动下载策略独立。", BridgeGlyph.PHOTOS) {
                ChoiceRow(DownloadVariant.entries, state.settings.manualVariant, DownloadVariant::label, actions.onSetManualVariant)
                Text("原格式保留所选 JPEG / NEF / NRW；便携副本由支持的相机生成 JPEG，手动导入当前通过 Wi-Fi 获取。",
                    style = MaterialTheme.typography.bodySmall)
                Text("每批加载及导入上限", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(listOf(25, 50, 100, 200), state.camera.catalogBatchSize, { "$it 项" }, actions.onSetBatchSize)
            }
        }
        item {
            SectionCard("关于", "OpenBridge · 相机选片与传输", BridgeGlyph.INFO) {
                TextButton(onClick = onAbout) { Text("认识 OpenBridge") }
            }
        }
    }
}

@Composable
private fun DevicesSettings(state: LibraryUiState, actions: LibraryActions) {
    val devices = (state.ble.cameras + state.pairedDevices).distinctBy { it.address }
    SectionCard("已配对设备", "控制设备与传图设备分别选择；同一相机的两种地址可能不同。", BridgeGlyph.BLUETOOTH) {
        Row {
            TextButton(onClick = actions.onRefreshPairedDevices) { Text("读取系统配对设备") }
            TextButton(onClick = actions.onScanBle, enabled = !state.ble.scanning) { Text("搜索 BLE 控制设备") }
        }
        DeviceChoices("BLE 控制设备", devices, state.settings.controlAddress, actions.onChooseControlDevice)
        Text(state.ble.message, style = MaterialTheme.typography.bodySmall)
        if (state.ble.authenticated) Text("当前控制连接：${state.ble.connectedAddress}", style = MaterialTheme.typography.labelSmall)
        if (state.ble.serialNumber.isNotBlank()) Text("机身序列号：${state.ble.serialNumber}", style = MaterialTheme.typography.labelSmall)
        Row {
            TextButton(onClick = actions.onConnectSavedControl, enabled = state.settings.controlAddress.isNotBlank()) { Text("连接控制通道") }
            TextButton(onClick = actions.onDisconnectControl, enabled = state.ble.authenticated) { Text("断开控制通道") }
        }
        DeviceChoices("经典蓝牙传图设备", state.pairedDevices.filter { it.classic }, state.settings.transferAddress, actions.onChooseTransferDevice)
        Text("连接方向（按机型适配）", style = MaterialTheme.typography.labelLarge)
        ChoiceRow(BluetoothRole.entries, state.settings.bluetoothRole, { when (it) {
            BluetoothRole.AUTOMATIC -> "按相机能力自动选择"
            BluetoothRole.CONNECT_TO_CAMERA -> "手机连接相机"
            BluetoothRole.WAIT_FOR_CAMERA -> "等待相机连接手机"
        } }, actions.onSetBluetoothRole)
        Row {
            TextButton(onClick = actions.onOpenBluetoothSettings) { Text("系统配对 / 取消配对") }
            TextButton(onClick = {
                actions.onDisconnectControl(); actions.onChooseControlDevice(""); actions.onChooseTransferDevice("")
            }, enabled = state.settings.controlAddress.isNotBlank() || state.settings.transferAddress.isNotBlank()) { Text("清除应用内关联") }
        }
    }
}

@Composable
private fun DeviceChoices(title: String, devices: List<BleCamera>, selected: String, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (devices.isEmpty()) Text(if (selected.isBlank()) "尚未读取或发现设备" else "已保存地址：$selected", style = MaterialTheme.typography.bodySmall)
    devices.forEach { device ->
        FilterChip(selected = device.address == selected, onClick = { onSelect(device.address) },
            label = { Text("${device.name} · ${device.address.takeLast(8)}") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun <T> ChoiceRow(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label(value)) }) }
    }
}

internal fun AutoDownloadKind.label(): String = when (this) {
    AutoDownloadKind.CAMERA_JPEG -> "便携 JPEG · 蓝牙"
    AutoDownloadKind.JPEG -> "JPEG 原图 · Wi-Fi"
    AutoDownloadKind.RAW -> "RAW 原图 · Wi-Fi"
    AutoDownloadKind.BOTH -> "RAW + JPEG · Wi-Fi"
}
internal fun DownloadVariant.label(): String = if (this == DownloadVariant.ORIGINAL) "所选原格式" else "便携 JPEG（约 2MP）"
private fun PowerProfile.label(): String = when (this) {
    PowerProfile.SAVER -> "省电"
    PowerProfile.BALANCED -> "均衡"
    PowerProfile.RESPONSIVE -> "及时"
}
