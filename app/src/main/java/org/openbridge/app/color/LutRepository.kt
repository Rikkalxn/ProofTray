package org.openbridge.app.color

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.ColorPreset
import org.openbridge.app.domain.LutAsset
import org.openbridge.app.domain.LutPreviewData
import org.openbridge.app.domain.builtInColorPresets

class LutRepository private constructor(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val root = File(context.filesDir, "color-library").apply { mkdirs() }
    private val lock = Any()
    private val handles = linkedMapOf<String, Long>()

    suspend fun luts(): List<LutAsset> = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.name.endsWith(".lut.json") }?.mapNotNull { file ->
            runCatching {
                val json = LookCodec.checkedJson(file.readText())
                LutAsset(json.getString("id"), json.getString("name"), json.getInt("dimension"), json.getInt("size"), json.getString("sourceFormat"))
            }.getOrNull()
        }?.sortedBy { it.name } ?: emptyList()
    }

    suspend fun presets(): List<ColorPreset> = withContext(Dispatchers.IO) {
        builtInColorPresets + (root.listFiles()?.filter { it.name.endsWith(".preset.json") }?.mapNotNull { file ->
            runCatching {
                val json = LookCodec.checkedJson(file.readText())
                ColorPreset(json.getString("id"), json.getString("name"), LookCodec.decode(json.getJSONObject("look")))
            }.getOrNull()
        }?.sortedBy { it.name } ?: emptyList())
    }

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        var name = "导入的 LUT"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0).take(160)
        }
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                require(result.size() + count <= 32 * 1024 * 1024) { "文件超过 32 MiB 导入上限" }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        } ?: error("无法读取 LUT 文件")
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension == "oblook" || extension == "json") {
            importPreset(bytes, name)
        } else {
            val handle = if (extension == "png") loadHald(bytes) else {
                require(extension == "cube") { "请选择 .cube、HaldCLUT .png 或 OpenBridge .oblook 预设；ZIP 请先解压" }
                NativeColor.load(bytes, true)
            }
            val provenance = JSONObject().apply {
                put("filename", name); put("license", "unspecified")
                if (extension == "cube") {
                    val comments = bytes.toString(Charsets.UTF_8).lineSequence().map { it.trimStart() }
                        .filter { it.startsWith('#') }.take(64).joinToString("\n").take(8192)
                    put("comments", comments)
                }
            }
            try { saveLut(handle, name.substringBeforeLast('.'), if (extension == "png") "HaldCLUT PNG" else "CUBE", provenance) }
            finally { NativeColor.release(handle) }
            name
        }
    }

    private fun loadHald(bytes: ByteArray): Long {
        require(bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "PNG 文件头无效" }
        require(bytes[24].toInt() == 8 && bytes[25].toInt() in setOf(2, 6)) { "当前支持 8 位 RGB/RGBA Hald PNG，暂不支持 16 位或索引色" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == bounds.outHeight && bounds.outWidth in (2..12).map { it * it * it }) {
            "Hald LUT 必须是边长等于 level³ 的方形图片，支持 level 2–12"
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
        }) ?: error("Hald PNG 解码失败")
        try {
            require(bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.colorSpace?.isSrgb == true) { "仅支持 sRGB Hald LUT" }
            return NativeColor.hald(bitmap)
        } finally { bitmap.recycle() }
    }

    private fun saveLut(handle: Long, name: String, sourceFormat: String, provenance: JSONObject? = null): String {
        val binary = NativeColor.pack(handle)
        val id = digest(binary)
        val info = NativeColor.values(handle, true)
        synchronized(lock) {
            write(File(root, "$id.oblut"), binary)
            val metadata = JSONObject().apply {
                put("id", id); put("name", name.take(128)); put("dimension", info[0].toInt()); put("size", info[1].toInt())
                put("sourceFormat", sourceFormat); put("inputColorSpace", "sRGB")
                put("provenance", cleanProvenance(provenance))
            }
            val metadataFile = File(root, "$id.lut.json")
            if (!metadataFile.exists()) write(metadataFile, metadata.toString().toByteArray())
        }
        return id
    }

    suspend fun savePreset(name: String, look: ColorLookSettings): Unit = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "预设名称不能为空" }
        look.validate()
        if (look.lutId != null) canonical(look.lutId)
        val id = UUID.randomUUID().toString()
        val record = JSONObject().apply {
            put("id", id); put("name", name.trim().take(80)); put("look", LookCodec.encode(look.copy(name = name.trim().take(80))))
        }
        synchronized(lock) { write(File(root, "$id.preset.json"), record.toString().toByteArray()) }
    }

    suspend fun exportPreset(preset: ColorPreset, destination: Uri): Unit = withContext(Dispatchers.IO) {
        preset.look.validate()
        val payload = JSONObject().apply {
            put("schema", "org.openbridge.look"); put("version", 1); put("pipelineVersion", 1); put("inputColorSpace", "sRGB")
            put("name", preset.name); put("look", LookCodec.encode(preset.look))
            if (preset.look.lutId != null) {
                val bytes = canonical(preset.look.lutId)
                put("lut", JSONObject().apply {
                    put("sha256", digest(bytes)); put("encoding", "oblut1-base64"); put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    val metadata = LookCodec.checkedJson(File(root, "${preset.look.lutId}.lut.json").readText())
                    put("provenance", cleanProvenance(metadata.optJSONObject("provenance")))
                })
            }
        }.toString(2).toByteArray()
        resolver.openOutputStream(destination, "wt")?.use { it.write(payload) } ?: error("无法保存预设文件")
    }

    private suspend fun importPreset(bytes: ByteArray, fallbackName: String): String {
        val json = LookCodec.checkedJson(bytes.toString(Charsets.UTF_8))
        require(json.optString("schema") == "org.openbridge.look" && json.optInt("version") == 1 && json.optInt("pipelineVersion") == 1) {
            "不是受支持的 OpenBridge 预设，不能把 Lightroom XMP 当作通用预设导入"
        }
        require(json.optString("inputColorSpace") == "sRGB") { "当前预设仅支持 SDR sRGB" }
        var look = LookCodec.decode(json.getJSONObject("look"))
        val packed = json.optJSONObject("lut")
        if (packed != null) {
            require(packed.getString("encoding") == "oblut1-base64") { "预设内 LUT 编码无效" }
            val binary = Base64.decode(packed.getString("data"), Base64.DEFAULT)
            val hash = digest(binary)
            require(hash == packed.getString("sha256")) { "预设中的 LUT 校验失败" }
            require(look.lutId == null || look.lutId == hash) { "预设引用与内嵌 LUT 不一致" }
            val handle = NativeColor.load(binary, false)
            val id = try {
                saveLut(handle, json.optString("name", fallbackName), "OpenBridge preset", packed.optJSONObject("provenance"))
            } finally { NativeColor.release(handle) }
            look = look.copy(lutId = id)
        } else require(look.lutId == null || File(root, "${look.lutId}.oblut").exists()) { "预设引用的 LUT 缺失，请导入完整 .oblook 文件" }
        val name = json.optString("name", fallbackName).take(80)
        savePreset(name, look)
        return name
    }

    fun previewData(id: String): LutPreviewData = synchronized(lock) {
        val handle = handle(id)
        val info = NativeColor.values(handle, true)
        LutPreviewData(id, info[0].toInt(), info[1].toInt(), info.copyOfRange(2, 5), info.copyOfRange(5, 8), NativeColor.values(handle, false))
    }

    fun apply(bitmap: Bitmap, look: ColorLookSettings) = synchronized(lock) {
        look.validate()
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) { "调色需要可写的 sRGB RGBA8 位图" }
        NativeColor.apply(look.lutId?.let(::handle) ?: 0L, bitmap, look.parameters(), bitmap.isPremultiplied)
    }

    private fun handle(id: String): Long {
        handles.remove(id)?.let { handles[id] = it; return it }
        val handle = NativeColor.load(canonical(id), false)
        handles[id] = handle
        while (handles.size > 2) {
            val oldest = handles.entries.first()
            handles.remove(oldest.key); NativeColor.release(oldest.value)
        }
        return handle
    }

    private fun canonical(id: String): ByteArray {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "LUT 标识无效" }
        val file = File(root, "$id.oblut")
        require(file.isFile && file.length() <= 16 * 1024 * 1024) { "LUT 文件不存在或过大" }
        return file.readBytes().also { require(digest(it) == id) { "LUT 文件内容已变化" } }
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    // Attribution supplied by the file author is retained as text; it is not a verified license grant.
    private fun cleanProvenance(source: JSONObject?): JSONObject = JSONObject().apply {
        put("license", source?.optString("license", "unspecified")?.take(4096) ?: "unspecified")
        for (key in listOf("filename", "author", "url", "comments")) {
            source?.optString(key)?.takeIf { it.isNotEmpty() }?.let { put(key, it.take(8192)) }
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        @Volatile private var instance: LutRepository? = null
        fun get(context: Context): LutRepository = instance ?: synchronized(this) {
            instance ?: LutRepository(context.applicationContext).also { instance = it }
        }
    }
}
