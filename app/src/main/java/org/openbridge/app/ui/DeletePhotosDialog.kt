package org.openbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.openbridge.app.domain.PhotoAsset

@Composable
internal fun DeletePhotosDialog(assets: List<PhotoAsset>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("删除手机里的 ${assets.size} 个文件？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将删除所选本地文件，保留相机存储卡上的原件、其他导出副本和导入历史。Android 可能还会要求系统确认。")
                Text(assets.take(3).joinToString("\n") { it.name } + if (assets.size > 3) "\n…等 ${assets.size} 个文件" else "",
                    style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("删除手机文件")
            }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
