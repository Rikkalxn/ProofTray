package org.prooftray.app.camera

import org.prooftray.app.util.sha256Hex
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject
import org.prooftray.app.domain.DownloadState
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.cameraFileKey
import org.prooftray.app.domain.cameraCaptureKey
import org.prooftray.app.domain.photoMediaFormat

internal data class CameraBrowseSession(val link: PtpLink, val sourceId: String, val cameraName: String, val generation: Int)

internal fun cameraSerial(value: String): String? {
    val serial = value.trim { it.isWhitespace() || it == '\u0000' }.takeIf { it.isNotBlank() } ?: return null
    if (serial.length > 64 || serial.any { it.code !in 33..126 }) return null
    if (serial.all { it == '0' } || serial.equals("unknown", true) || serial.equals("N/A", true)) return null
    return serial
}

internal fun cameraSerialMatches(first: String?, second: String?): Boolean {
    if (first == null || second == null) return true
    if (first.all(Char::isDigit) && second.all(Char::isDigit)) return first.trimStart('0') == second.trimStart('0')
    return first.equals(second, true)
}

internal fun cameraPhoto(info: JSONObject, handle: Long, session: CameraBrowseSession, preview: (String) -> String?): RemotePhoto? =
    cameraPhoto(info, handle, session.sourceId, session.cameraName, preview)

internal fun cameraObjectId(info: JSONObject, sourceId: String): String {
    val storage = info.getLong("storage")
    val size = info.getLong("size")
    val media = info.optString("sonyMedia")
    val key = if (media.isNotEmpty()) "$sourceId|$storage|$media|${info.getString("sonyPath")}|${info.getLong("sonyModified")}|$size"
        else "$sourceId|$storage|${info.getLong("parent")}|${info.getString("name")}|${info.optString("captured")}|$size"
    return key.toByteArray().sha256Hex()
}

internal fun cameraPhoto(
    info: JSONObject, handle: Long, sourceId: String, cameraName: String, preview: (String) -> String? = { null }
): RemotePhoto? {
    val name = info.getString("name")
    val format = photoMediaFormat(name)
    if (format == MediaFormat.OTHER || info.optInt("format") == 0x3001) return null
    val storage = info.getLong("storage")
    val parent = info.getLong("parent")
    val captured = info.optString("captured")
    val bytes = info.getLong("size")
    val sonyMedia = info.optString("sonyMedia")
    val sonyPath = info.optString("sonyPath")
    val id = cameraObjectId(info, sourceId)
    val time = if (info.has("sonyTime")) info.getLong("sonyTime") else runCatching {
        LocalDateTime.parse(captured.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
    val card = if (sonyMedia.isEmpty()) "card:$storage" else "card:$storage/$sonyMedia"
    val folder = if (sonyPath.isEmpty()) "$card/parent:$parent" else "$card/${sonyPath.substringBeforeLast('/', "")}"
    val previewUri = preview(id)
    val asset = PhotoAsset(
        id = id, uri = previewUri ?: "openbridge-remote://$id", name = name, format = format, sizeBytes = bytes,
        capturedAtMillis = time, width = info.getInt("width").takeIf { it > 0 }, height = info.getInt("height").takeIf { it > 0 },
        camera = cameraName, sourceId = sourceId, folder = folder, downloadState = DownloadState.REMOTE,
        cameraFileKey = cameraFileKey(sourceId, if (sonyPath.isEmpty()) card else folder, name, bytes, time),
        inImportList = true, previewUri = previewUri,
        cameraCaptureKey = cameraCaptureKey(sourceId, name, time)
    )
    return RemotePhoto(asset, handle, storage, sourceId, captured)
}
