package org.prooftray.app.frame

import org.prooftray.app.io.writeBytesAtomically
import org.prooftray.app.io.readBounded
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import org.prooftray.app.util.sha256Hex
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.prooftray.app.domain.FrameSnapshot
import org.prooftray.app.domain.FrameTemplate
import org.prooftray.app.domain.fontIds
import org.prooftray.app.domain.imageIds

internal data class FramePreset(val id: String, val name: String, val template: FrameTemplate?, val error: String? = null)
internal data class FrameFont(val id: String, val name: String)

/** Only templates created by this feature live here. Immutable resources remain available to already queued exports. */
internal class FrameRepository(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "frames").apply { mkdirs() }
    private val templates = File(root, "templates").apply { mkdirs() }
    private val images = File(root, "images").apply { mkdirs() }
    private val fonts = File(root, "fonts").apply { mkdirs() }
    private val fontCache = LruCache<String, Typeface>(64)
    private val pixelCache = ConcurrentHashMap<String, Long>()

    fun presets(): List<FramePreset> = FrameTemplates.all.map { FramePreset(it.id, it.name, it, problem(it)) } +
        templates.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.map { file ->
            try {
                val text = file.inputStream().use { it.readBounded(FrameCodec.MAX_JSON).toString(Charsets.UTF_8) }
                val template = FrameCodec.decode(FrameCodec.checkedJson(text))
                FramePreset(template.id, template.name, template, problem(template))
            } catch (error: Exception) { FramePreset(file.nameWithoutExtension, "不可用模板", null, error.message ?: "无法读取模板") }
        }

    fun save(template: FrameTemplate, name: String, replace: Boolean = false): FrameTemplate {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 80) { "模板名称需为 1–80 个字符" }
        val id = if (replace && template.id.startsWith("user.")) template.id else "user." + UUID.randomUUID()
        val saved = template.copy(id = id, name = title, family = "我的模板", revision = if (replace) template.revision + 1 else 1)
        writeAtomic(templateFile(id), FrameCodec.encode(saved).toString().toByteArray(Charsets.UTF_8))
        return saved
    }

    fun delete(id: String) {
        require(id.startsWith("user.")) { "内置模板不能删除" }
        check(templateFile(id).let { !it.exists() || it.delete() }) { "未能删除模板" }
    }

    fun problem(template: FrameTemplate): String? {
        if (template.imageIds().any { !imageFile(it).isFile }) return "缺少模板图片，请重新导入模板包"
        if (template.fontIds().any { !fontFile(it).isFile }) return "缺少字体，可在编辑器中替换字体"
        val pixels = template.imageIds().sumOf { id ->
            pixelCache.getOrPut(id) {
                val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imageFile(id).absolutePath, info)
                if (info.outWidth !in 1..2048 || info.outHeight !in 1..2048) Long.MAX_VALUE / 16
                else info.outWidth.toLong() * info.outHeight
            }
        }
        if (pixels > 8_388_608) return "模板图片总像素超过上限"
        return null
    }

    fun snapshot(template: FrameTemplate, values: Map<String, String>): FrameSnapshot {
        FrameCodec.validate(template)
        problem(template)?.let { error(it) }
        val captured = if (template.showGps) values.toMap() else values - "gps"
        return FrameSnapshot(template, captured, logoFor(template, captured["make"].orEmpty()))
    }

    fun logoFor(template: FrameTemplate, make: String): String? {
        val key = brandKey(make)
        if (key.isEmpty()) return null
        val id = template.logos.entries.firstOrNull { brandKey(it.key) == key }?.value ?: readMap("logos.json")[key]
        return id?.takeIf { validId(it) && imageFile(it).isFile }
    }

    fun bindLogo(make: String, id: String) {
        val key = brandKey(make)
        require(key.isNotBlank() && key.length <= 96 && imageFile(id).isFile) { "请先选择品牌图片" }
        synchronized(writeLock) { writeMap("logos.json", readMap("logos.json") + (key to id)) }
    }

    /** Capture only the referenced brand image; never include the user's photograph or resolved EXIF in a template package. */
    fun portable(template: FrameTemplate, make: String): FrameTemplate {
        val logo = logoFor(template, make) ?: return template
        return template.copy(logos = template.logos + (brandKey(make) to logo)).also(FrameCodec::validate)
    }

    fun importImage(uri: Uri): String {
        val data = app.contentResolver.openInputStream(uri)?.use { it.readBounded(4 * MIB) } ?: error("无法读取图片")
        checkImage(data)
        val temporary = File.createTempFile("frame-image-", ".tmp", app.cacheDir)
        val bitmap: Bitmap
        try {
            temporary.writeBytes(data)
            bitmap = decodeImage(temporary)
        } finally { temporary.delete() }
        val png = try { ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "图片转换失败" }
            out.toByteArray().also { require(it.size <= 4 * MIB) { "图片转换后超过 4 MiB，请使用较小的图片" } }
        } } finally { bitmap.recycle() }
        val id = png.sha256Hex()
        writeResource(imageFile(id), png)
        return id
    }

    fun importFont(uri: Uri): FrameFont {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBounded(32 * MIB) } ?: error("无法读取字体")
        require(bytes.size >= 12 && (bytes.take(4) == listOf<Byte>(0, 1, 0, 0) || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "OTTO")) {
            "请选择 TTF 或 OTF 字体"
        }
        val temporary = File.createTempFile("frame-font-", ".tmp", app.cacheDir)
        try {
            temporary.writeBytes(bytes)
            checkNotNull(Typeface.Builder(temporary).build()) { "字体文件无效" }
            val id = bytes.sha256Hex()
            writeResource(fontFile(id), bytes)
            val name = displayName(uri).substringBeforeLast('.').take(80).ifBlank { "自定义字体" }
            synchronized(writeLock) { writeMap("fonts.json", readMap("fonts.json") + (id to name)) }
            return FrameFont(id, name)
        } finally { temporary.delete() }
    }

    fun fonts(): List<FrameFont> = listOf(FrameFont("system:sans-serif", "无衬线"), FrameFont("system:serif", "衬线"),
        FrameFont("system:monospace", "等宽")) + readMap("fonts.json").filterKeys { validId(it) && fontFile(it).isFile }
        .map { FrameFont(it.key, it.value) }

    fun typeface(id: String, weight: Int, italic: Boolean): Typeface {
        val key = id + ":" + weight + ":" + italic
        return synchronized(fontCache) {
            fontCache.get(key) ?: (if (id.startsWith("system:")) {
                Typeface.create(Typeface.create(id.removePrefix("system:"), Typeface.NORMAL), weight, italic)
            } else {
                require(fontFile(id).isFile) { "字体未安装，请在图层设置中替换字体" }
                checkNotNull(Typeface.Builder(fontFile(id)).setWeight(weight).setItalic(italic).build()) { "字体文件无效" }
            }).also { fontCache.put(key, it) }
        }
    }

    fun readImage(id: String): Bitmap = decodeImage(imageFile(id))

    fun importTemplate(uri: Uri): FrameTemplate {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBounded(16 * MIB) } ?: error("无法读取模板")
        val files = if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) unzip(bytes)
            else mapOf("template.json" to bytes)
        val json = files["template.json"] ?: error("模板包缺少 template.json")
        val template = FrameCodec.decode(FrameCodec.checkedJson(json.toString(Charsets.UTF_8)))
        val expected = template.imageIds().associateWith { "resources/" + it + ".png" }
        require(files.keys.all { it == "template.json" || it in expected.values }) { "模板包包含不支持的文件" }
        var imagePixels = 0L
        val declarations = FrameCodec.checkedJson(json.toString(Charsets.UTF_8)).optJSONArray("resources")
        if (files.size > 1) {
            require(declarations != null && declarations.length() == expected.size) { "图片资源清单不完整" }
            val seen = mutableSetOf<String>()
            for (i in 0 until declarations.length()) {
                val item = declarations.getJSONObject(i)
                val id = item.getString("id")
                require(seen.add(id) && item.getString("sha256") == id && item.getString("path") == expected[id]) { "图片清单无效" }
            }
        }
        expected.forEach { (id, path) ->
            val image = files[path]
            if (image == null) require(imageFile(id).isFile) { "缺少图片资源，请导入完整模板包" }
            else {
                require(image.sha256Hex() == id) { "模板图片校验失败" }
                imagePixels += checkImage(image, pngOnly = true)
            }
        }
        require(imagePixels <= 8_388_608) { "模板图片总像素超过上限" }
        // Validate the whole archive before installing anything. Resource names are content hashes, never ZIP paths.
        expected.forEach { (id, path) -> files[path]?.let { writeResource(imageFile(id), it) } }
        return save(template, template.name)
    }

    fun writePackage(template: FrameTemplate, output: OutputStream) {
        problem(template)?.let { error(it) }
        val json = FrameCodec.encode(template).toString(2).toByteArray(Charsets.UTF_8)
        require(json.size <= FrameCodec.MAX_JSON) { "模板信息过大" }
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("template.json", json)
            var total = json.size
            template.imageIds().sorted().forEach { id ->
                val bytes = imageFile(id).inputStream().use { it.readBounded(4 * MIB) }
                total += bytes.size
                require(total <= 16 * MIB) { "模板资源过大" }
                check(bytes.sha256Hex() == id) { "模板图片已损坏" }
                entry("resources/" + id + ".png", bytes)
            }
        }
    }

    fun shareFile(template: FrameTemplate): File {
        val directory = File(app.cacheDir, "frame-share").apply { mkdirs() }
        val name = template.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "边框模板" }
        return File(directory, name + "-" + UUID.randomUUID().toString().take(8) + ".obframepack").also { file ->
            try { file.outputStream().use { writePackage(template, it) } }
            catch (error: Throwable) { file.delete(); throw error }
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(files.size < 16 && !entry.isDirectory && entry.name !in files) { "模板包条目过多或重复" }
                require(entry.name == "template.json" || Regex("resources/[a-f0-9]{64}\\.png").matches(entry.name)) { "模板包路径无效" }
                val data = zip.readBounded(if (entry.name == "template.json") FrameCodec.MAX_JSON else 4 * MIB)
                total += data.size
                require(total <= 32 * MIB) { "模板解压后过大" }
                files[entry.name] = data
                zip.closeEntry()
            }
        }
        return files
    }

    private fun checkImage(data: ByteArray, pngOnly: Boolean = false): Long {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        require(options.outWidth in 1..2048 && options.outHeight in 1..2048) { "模板图片尺寸不能超过 2048 × 2048" }
        require(options.outMimeType in if (pngOnly) setOf("image/png") else setOf("image/png", "image/jpeg", "image/webp")) {
            "模板图片仅支持 PNG、JPEG、静态 WebP"
        }
        // Animated WebP is not a template resource.
        require(!(options.outMimeType == "image/webp" && data.toString(Charsets.ISO_8859_1).contains("ANIM"))) { "不支持动态图片" }
        return options.outWidth.toLong() * options.outHeight
    }

    private fun decodeImage(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        require(info.size.width in 1..2048 && info.size.height in 1..2048) { "模板图片尺寸无效" }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }

    private fun templateFile(id: String): File {
        require(Regex("user\\.[A-Za-z0-9-]{1,80}").matches(id)) { "模板标识无效" }
        return File(templates, id + ".json")
    }
    private fun imageFile(id: String): File { require(validId(id)) { "图片标识无效" }; return File(images, id + ".png") }
    private fun fontFile(id: String): File { require(validId(id)) { "字体标识无效" }; return File(fonts, id + ".font") }
    private fun validId(id: String): Boolean = Regex("[a-f0-9]{64}").matches(id)
    private fun writeResource(file: File, bytes: ByteArray) = synchronized(writeLock) { if (!file.exists()) writeAtomic(file, bytes) }
    private fun readMap(name: String): Map<String, String> = try {
        val json = JSONObject(File(root, name).readText())
        json.keys().asSequence().associateWith { json.getString(it) }
    } catch (_: Exception) { emptyMap() }
    private fun writeMap(name: String, values: Map<String, String>) = writeAtomic(File(root, name), JSONObject(values).toString().toByteArray())

    private fun displayName(uri: Uri): String = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }.orEmpty()

    companion object {
        private const val MIB = 1024 * 1024
        private val writeLock = Any()
        fun brandKey(value: String): String = when (val key = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")) {
            "nikon corporation" -> "nikon"; "sony corporation" -> "sony"; "canon inc." -> "canon"; else -> key
        }
        private fun writeAtomic(file: File, data: ByteArray) = file.writeBytesAtomically(data)
    }
}
