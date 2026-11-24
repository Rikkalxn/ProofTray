package org.prooftray.app.camera

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.prooftray.app.util.sha256Hex
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal const val SONY_DATES = 0x923b
internal const val SONY_LIST = 0x923c
internal const val SONY_FILE = 0x923d
internal const val SONY_PREVIEW = 0x923e
internal const val SONY_CONTENTS_CHANGED = 0xc234
internal const val SONY_CHUNK = 3 * 1024 * 1024
internal const val SONY_THUMB_PROP = 0xd8b1
internal const val SONY_SCREEN_PROP = 0xd8b0

internal data class SonyDescription(val server: Double? = null, val transfer: String? = null)

internal fun sonyDescription(bytes: ByteArray): SonyDescription {
    require(bytes.size <= 1024 * 1024) { "机身描述过大" }
    val xml = bytes.toString(Charsets.UTF_8).trim('\u0000', '\uFEFF', ' ', '\t', '\r', '\n')
    if (xml.isEmpty()) return SonyDescription()
    require(!xml.contains("<!DOCTYPE", true)) { "机身描述包含无效声明" }
    var server: Double? = null
    var transfer: String? = null
    var target = ""
    val value = StringBuilder()
    val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
    reader.setEntityResolver { _, _ -> throw SAXException("机身描述引用了外部资源") }
    reader.contentHandler = object : DefaultHandler() {
        override fun startElement(uri: String?, local: String, name: String?, attrs: Attributes?) {
            target = local
            value.setLength(0)
        }
        override fun characters(chars: CharArray, start: Int, length: Int) {
            if (target == "X_ServerVersion" || target == "X_PTP_ContentsTransferSupport") value.append(chars, start, length)
        }
        override fun endElement(uri: String?, local: String, name: String?) {
            if (local == target) when (local) {
                "X_ServerVersion" -> server = value.toString().trim().toDoubleOrNull()
                "X_PTP_ContentsTransferSupport" -> transfer = value.toString().trim()
            }
            target = ""
        }
    }
    reader.parse(InputSource(StringReader(xml)))
    return SonyDescription(server, transfer)
}

internal data class SonyFileRef(val slot: Int, val content: Long, val file: Int) {
    init { require(slot in 1..2 && content in 0..0xffff_ffffL && file in 0..0xffff) }
    val selector: Long get() = (slot.toLong() shl 24) or file.toLong()
    val id: Long get() = (selector shl 32) or content
    fun preview(detail: Boolean) = longArrayOf(content, selector, if (detail) 2 else 1)
    fun chunk(offset: Long, count: Int, reportSize: Boolean): LongArray {
        require(offset >= 0 && count in 1..SONY_CHUNK)
        return longArrayOf(content, selector or (if (reportSize) 0x10000L else 0L), offset and 0xffff_ffffL, offset ushr 32, count.toLong())
    }
    fun finish() = longArrayOf(content, selector, 0, 0, 0x8000_0001L)
}

private val ptpTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

internal data class SonyMediaFile(
    val ref: SonyFileRef, val path: String, val size: Long, val format: Long, val directory: Long,
    val createdUtc: Long, val createdLocal: Long, val modifiedUtc: Long, val media: String, val width: Int, val height: Int
) {
    fun info(): JSONObject = JSONObject().apply {
        put("name", path.substringAfterLast('/')); put("size", size); put("format", format); put("parent", directory)
        put("storage", ref.slot); put("width", width); put("height", height)
        put("captured", ptpTime.format(Instant.ofEpochMilli(createdLocal))); put("sonyTime", createdUtc)
        put("sonyMedia", media); put("sonyPath", path); put("sonyModified", modifiedUtc)
    }
}

internal data class SonyContentEntry(val localTime: Long, val files: List<SonyMediaFile>)

private fun ByteBuffer.u32() = int.toLong() and 0xffff_ffffL
private fun ByteBuffer.skipBytes(n: Int) { require(n in 0..remaining()) { "索尼目录记录不完整" }; position(position() + n) }
private fun ByteBuffer.length(limit: Int): Int = u32().also { require(it <= limit) { "索尼目录字段长度无效" } }.toInt()
private fun ByteBuffer.bytes(count: Int) = ByteArray(count).also { get(it) }

internal fun sonyExtendedProperties(bytes: ByteArray): Set<Int> {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(b.remaining() >= 10) { "索尼扩展能力描述不完整" }
    b.short // protocol version
    val count = b.length((b.remaining() - 8) / 2)
    val properties = buildSet { repeat(count) { add(b.short.toInt() and 0xffff) } }
    val controls = b.length((b.remaining() - 4) / 2)
    b.skipBytes(controls * 2)
    return properties
}

internal data class SonyScalar(val enabled: Int, val value: Long) {
    // ContentsInfoListEnableStatus uses wire value 1 in the low byte; 3 is the official client's internal enum index.
    val contentsReadable: Boolean get() = enabled in 1..2 && (value and 0xffL) == 1L
    val supportsFileSizeReport: Boolean get() = enabled == 1 && (value and 0xffff_ffffL).toInt() >= 101
}

internal fun sonyScalar(bytes: ByteArray, code: Int): SonyScalar? {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(b.remaining() >= 6 && (b.short.toInt() and 0xffff) == code) { "索尼属性描述不匹配" }
    val type = b.short.toInt() and 0xffff
    val width = when (type) { 1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; 7, 8 -> 8; else -> return null }
    b.get() // writable
    val enabled = b.get().toInt() and 255
    b.skipBytes(width) // default value precedes the current value
    require(b.remaining() >= width) { "索尼属性值不完整" }
    val value = when (type) {
        1 -> b.get().toLong(); 2 -> b.get().toLong() and 255
        3 -> b.short.toLong(); 4 -> b.short.toLong() and 0xffff
        5 -> b.int.toLong(); 6 -> b.u32()
        else -> b.long
    }
    return SonyScalar(enabled, value)
}

internal fun validateSonyReadMetadata(params: LongArray, expectedSize: Long, previousStamp: Long, reportSize: Boolean): Long? {
    if (params.size < 2) return null
    fun longParam(index: Int) = params[index] or (params[index + 1] shl 32)
    val stamp = longParam(0)
    // A first-chunk size report validates the expected size; later replies validate the previous modification timestamp.
    if (reportSize && params.size >= 4) {
        check(longParam(2) == expectedSize) { "机身文件大小已变化，请刷新后重新选择原片" }
    } else check(previousStamp <= 0 || stamp <= previousStamp) { "传输期间机身上的文件已修改，请刷新后重新选择原片" }
    return stamp.takeIf { it > 0 }
}

internal fun sonyDates(data: ByteArray): List<Long> {
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    require(buf.remaining() >= 4) { "索尼拍摄日期列表不完整" }
    val n = buf.length(buf.remaining() / 8)
    require(buf.remaining() >= n * 8) { "索尼拍摄日期列表不完整" }
    return List(n) { buf.long }.distinct()
}

/** A page groups the files of a capture; JPEG and ARW may have the same content ID and different file IDs. */
internal fun sonyContentPage(data: ByteArray, slot: Int): List<SonyContentEntry> {
    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    require(b.remaining() >= 148) { "索尼照片目录不完整" }
    b.skipBytes(12) // management version, reserved bytes and database update time
    val media = b.bytes(128).sha256Hex()
    val reportedSlot = b.u32()
    require(reportedSlot == 0L || reportedSlot == slot.toLong()) { "机身返回了另一卡槽的照片目录" }
    return try {
        List(b.length(100)) {
            b.skipBytes(4)
            val content = b.u32()
            val directory = b.u32()
            b.skipBytes(16) // file number, group type, group ID and representative flag
            val utc = b.long
            val modified = b.long
            val local = b.long
            b.skipBytes(16) // local modification time, rating and protection
            val dummy = b.int != 0
            b.skipBytes(b.length(b.remaining()))
            val fileCount = b.length(4096)
            val files = buildList {
                repeat(fileCount) {
                    val file = b.short.toInt() and 0xffff
                    b.skipBytes(2)
                    val path = b.bytes(b.length(minOf(16384, b.remaining()))).toString(Charsets.UTF_8).trimEnd('\u0000').replace('\\', '/')
                    val format = b.u32()
                    val size = b.long
                    b.skipBytes(32)
                    val image = b.int != 0
                    val width = if (image) b.int else 0
                    val height = if (image) b.int else 0
                    if (b.int != 0) b.skipBytes(76)
                    if (b.int != 0) b.skipBytes(16)
                    if (!dummy && size >= 0 && path.substringAfterLast('/').isNotBlank()) {
                        val ref = SonyFileRef(slot, content, file)
                        add(SonyMediaFile(ref, path, size, format, directory, utc, local, modified, media, width, height))
                    }
                }
            }
            SonyContentEntry(local, files)
        }
    } catch (failure: java.nio.BufferUnderflowException) { throw IllegalArgumentException("索尼照片目录记录被截断", failure) }
}

/** MTP object properties containing previews use an AUINT8 dataset: four-byte count, then exactly that many image bytes. */
internal fun sonyPreviewProperty(data: ByteArray): ByteArray? {
    if (data.size < 4 || ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).u32() != (data.size - 4).toLong()) return null
    return data.copyOfRange(4, data.size)
}
