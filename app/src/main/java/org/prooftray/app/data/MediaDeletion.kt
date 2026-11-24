package org.prooftray.app.data

import android.app.PendingIntent
import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.prooftray.app.domain.DownloadState
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.isMediaItemUri
import org.prooftray.app.domain.isMediaRequestItemUri

data class MediaDeleteTarget(val id: String, val originalUri: String, val mediaUri: String, val name: String)
data class MediaDeletePlan(val targets: List<MediaDeleteTarget>, val confirmation: PendingIntent?)
data class MediaDeleteResult(val deleted: List<MediaDeleteTarget>, val errors: List<String>)

/** Called only after the app's delete confirmation. Android 11+ supplies the final system confirmation and performs the deletion. */
class MediaDeletion(private val context: Context) {
    private val pending = File(context.filesDir, "pending-media-deletions")
    suspend fun prepare(assets: List<PhotoAsset>): MediaDeletePlan = withContext(Dispatchers.IO) {
        require(assets.isNotEmpty() && assets.size <= 2000) { "一次请选择 1–2000 个本地文件" }
        val targets = assets.distinctBy { it.id }.map { asset ->
            require(asset.downloadState == DownloadState.DOWNLOADED) { "相机上的文件不能在此删除，请先导入到手机" }
            val original = Uri.parse(asset.uri)
            require(original.scheme == "content") { "预览缓存不属于可删除的手机原片" }
            val media = if (original.authority == MediaStore.AUTHORITY) original else {
                require(original.authority in LOCAL_DOCUMENT_PROVIDERS) { "${asset.name} 不在系统媒体库，可移出列表或在文件管理器中处理" }
                MediaStore.getMediaUri(context, original) ?: error("${asset.name} 无法对应到系统媒体库，可选择移出列表")
            }
            val normalized = media.buildUpon().clearQuery().fragment(null).build()
            require(isMediaItemUri(normalized.toString())) { "文件提供方没有返回明确的媒体条目，未发起删除" }
            val target = if (Build.VERSION.SDK_INT >= 30) requestUri(normalized) else normalized
            MediaDeleteTarget(asset.id, asset.uri, target.toString(), asset.name)
        }
        val request = if (Build.VERSION.SDK_INT >= 30) {
            MediaStore.createDeleteRequest(context.contentResolver, targets.map { Uri.parse(it.mediaUri) }.distinct())
        } else null
        MediaDeletePlan(targets, request)
    }

    /** Files/Downloads IDs are valid records, but Android's consent API requires an images/video/audio item URI. */
    private fun requestUri(original: Uri): Uri {
        if (isMediaRequestItemUri(original.toString())) return original
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.VOLUME_NAME)
        val resolver = context.contentResolver
        val target = resolver.query(original, projection, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "系统媒体库中找不到所选文件，未发起删除" }
            val id = cursor.getLong(0)
            check(id == ContentUris.parseId(original)) { "媒体记录标识发生变化，未发起删除" }
            val mime = cursor.getString(1).orEmpty().lowercase()
            val volume = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: MediaStore.getVolumeName(original)
            val collection = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume)
                mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume)
                mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(volume)
                else -> error("此文件未被系统识别为媒体，暂不能使用系统删除确认；可移出列表或用文件管理器处理")
            }
            ContentUris.withAppendedId(collection, id)
        } ?: error("无法读取所选文件的媒体记录")
        require(isMediaRequestItemUri(target.toString())) { "无法生成该文件的系统删除请求" }
        resolver.query(target, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == ContentUris.parseId(original)) { "媒体映射未确认，未发起删除" }
        } ?: error("无法验证系统媒体地址，未发起删除")
        return target
    }

    suspend fun remember(targets: List<MediaDeleteTarget>): String = withContext(Dispatchers.IO) {
        val token = UUID.randomUUID().toString()
        pending.mkdirs()
        val file = AtomicFile(pendingFile(token))
        val stream = file.startWrite()
        val record = JSONObject().put("targets", JSONArray(encode(targets)))
        try { stream.write(record.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        token
    }

    suspend fun pendingTargets(token: String): List<MediaDeleteTarget> = withContext(Dispatchers.IO) {
        decode(AtomicFile(pendingFile(token)).readFully().toString(Charsets.UTF_8))
    }

    suspend fun recordDeleted(token: String, targets: List<MediaDeleteTarget>) = withContext(Dispatchers.IO) {
        val file = AtomicFile(pendingFile(token))
        val record = JSONObject(file.readFully().toString(Charsets.UTF_8)).put("deleted", JSONArray(encode(targets)))
        val stream = file.startWrite()
        try { stream.write(record.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    suspend fun finish(token: String) = withContext(Dispatchers.IO) { AtomicFile(pendingFile(token)).delete() }

    /** Only recorded successful deletions can change the catalog after restart. Lost access is not proof that a file was deleted. */
    suspend fun completedRequests(): List<Pair<String, List<MediaDeleteTarget>>> = withContext(Dispatchers.IO) {
        pending.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
            runCatching {
                val record = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
                val deleted = record.optJSONArray("deleted") ?: return@runCatching null
                if (deleted.length() == 0) null else file.nameWithoutExtension to decodeTargets(deleted)
            }.getOrNull()
        } ?: emptyList()
    }

    private fun pendingFile(token: String): File {
        require(token.matches(Regex("[a-f0-9-]{36}"))) { "删除请求标识无效" }
        return File(pending, "$token.json")
    }

    /** Android 10 can directly delete app-owned items; other items remain untouched when additional authorization is required. */
    suspend fun deleteOnAndroid10(targets: List<MediaDeleteTarget>): MediaDeleteResult = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT == 29)
        val deleted = mutableListOf<MediaDeleteTarget>()
        val errors = mutableListOf<String>()
        for (target in targets) {
            require(isMediaItemUri(target.mediaUri))
            try {
                val uri = Uri.parse(target.mediaUri)
                val removed = context.contentResolver.delete(uri, null, null) > 0
                if (removed) deleted += target else errors += "${target.name}：系统未确认删除成功"
            } catch (_: FileNotFoundException) { errors += "${target.name}：文件不可访问，可移出列表" }
            catch (_: SecurityException) { errors += "${target.name}：Android 10 未授予删除权限，请使用系统相册或文件管理器" }
            catch (error: Exception) { errors += "${target.name}：${error.message ?: "删除失败"}" }
        }
        MediaDeleteResult(deleted, errors)
    }

    companion object {
        private val LOCAL_DOCUMENT_PROVIDERS = setOf(
            "com.android.providers.media.documents", "com.android.providers.downloads.documents", "com.android.externalstorage.documents"
        )

        fun encode(targets: List<MediaDeleteTarget>): String = JSONArray().apply {
            targets.forEach { target -> put(JSONObject().apply {
                put("id", target.id); put("original", target.originalUri); put("media", target.mediaUri); put("name", target.name)
            }) }
        }.toString()

        fun decode(value: String): List<MediaDeleteTarget> {
            return decodeTargets(JSONObject(value).getJSONArray("targets"))
        }

        private fun decodeTargets(list: JSONArray): List<MediaDeleteTarget> {
            require(list.length() in 1..2000)
            return (0 until list.length()).map { index ->
                val item = list.getJSONObject(index)
                MediaDeleteTarget(item.getString("id"), item.getString("original"), item.getString("media"), item.getString("name"))
                    .also { require(isMediaItemUri(it.mediaUri)) }
            }
        }
    }
}
