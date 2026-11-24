package org.openbridge.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.openbridge.app.color.LookCodec
import org.openbridge.app.data.ExternalPhotoEditor
import org.openbridge.app.data.MediaDeletion
import org.openbridge.app.domain.ColorPreset
import org.openbridge.app.domain.PhotoAsset

/** Deletes phone files through the system confirmation; Android 10 deletes directly after the in-app confirmation. */
@Composable
internal fun rememberPhotoDeletion(model: LibraryViewModel): (List<PhotoAsset>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaDeletion = remember { MediaDeletion(context.applicationContext) }
    var pendingDeletion by rememberSaveable { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val confirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val token = pendingDeletion ?: return@rememberLauncherForActivityResult
        pendingDeletion = null
        if (result.resultCode == Activity.RESULT_OK) model.completeMediaDeletion(token)
        else scope.launch { mediaDeletion.finish(token) }
    }
    return { assets ->
        if (!preparing && pendingDeletion == null && model.validateDeletion(assets)) scope.launch {
            preparing = true
            var token: String? = null
            try {
                val plan = mediaDeletion.prepare(assets)
                val requestToken = mediaDeletion.remember(plan.targets)
                token = requestToken
                pendingDeletion = requestToken
                val request = plan.confirmation
                if (request != null) confirmation.launch(IntentSenderRequest.Builder(request.intentSender).build())
                else { model.deleteMediaOnAndroid10(requestToken); pendingDeletion = null }
            } catch (stop: CancellationException) { throw stop }
            catch (error: Exception) {
                token?.let { mediaDeletion.finish(it) }
                pendingDeletion = null
                model.showError("未能发起删除：${error.message}")
            } finally { preparing = false }
        }
    }
}

/** Opens an editable copy in a system photo editor and records that copy as an export of the source photo. */
@Composable
internal fun rememberSystemEditor(model: LibraryViewModel): (PhotoAsset) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editorCopy by rememberSaveable { mutableStateOf<String?>(null) }
    var editorSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    fun finishEditorCopy(): Uri? {
        val copy = editorCopy?.let(Uri::parse)
        val source = editorSourceId
        editorCopy = null
        editorSourceId = null
        if (copy != null && source != null) model.rememberSystemEdit(source, copy)
        return copy
    }
    val editor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val copy = finishEditorCopy()
        val saved = result.data?.data
        if (copy != null && saved != null && saved != copy) model.showError("编辑器另存的结果可从手机添加。编辑副本可在「导出」中查看。")
    }
    return { photo ->
        if (!preparing) scope.launch {
            preparing = true
            try {
                val copy = ExternalPhotoEditor(context.applicationContext).prepare(photo)
                editorSourceId = photo.id
                editorCopy = copy.uri.toString()
                val edit = Intent(Intent.ACTION_EDIT).apply {
                    setDataAndType(copy.uri, copy.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    clipData = ClipData.newRawUri("编辑副本", copy.uri)
                }
                check(edit.resolveActivity(context.packageManager) != null) { "没有可处理此格式的照片编辑器" }
                editor.launch(Intent.createChooser(edit, "选择照片编辑器"))
            } catch (stop: CancellationException) { throw stop }
            catch (error: Exception) {
                finishEditorCopy()
                model.showError("无法打开系统编辑器：${error.message}")
            } finally { preparing = false }
        }
    }
}

/** Saves a color preset as an .oblook file; the preset is kept as JSON so it survives the document picker recreating the activity. */
@Composable
internal fun rememberPresetExport(model: LibraryViewModel): (ColorPreset) -> Unit {
    var exportingPreset by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val record = exportingPreset
        exportingPreset = null
        if (uri == null || record == null) return@rememberLauncherForActivityResult
        try {
            val json = LookCodec.checkedJson(record)
            model.exportColorPreset(ColorPreset(json.getString("id"), json.getString("name"), LookCodec.decode(json.getJSONObject("look"))), uri)
        } catch (error: Exception) { model.showError("无法恢复待导出的预设：${error.message}") }
    }
    return { preset ->
        exportingPreset = JSONObject().apply {
            put("id", preset.id); put("name", preset.name); put("look", LookCodec.encode(preset.look))
        }.toString()
        destination.launch(preset.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + ".oblook")
    }
}
