package org.openbridge.app.camera

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.cameraFileKey
import org.openbridge.app.domain.cameraCaptureKey

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

internal fun cameraPhoto(
    info: JSONObject, handle: Long, sourceId: String, cameraName: String, preview: (String) -> String? = { null }
): RemotePhoto? {
    val name = info.getString("name")
    val format = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> MediaFormat.JPEG
        "nef", "nrw" -> MediaFormat.RAW
        else -> return null
    }
    val storage = info.getLong("storage")
    val parent = info.getLong("parent")
    val captured = info.optString("captured")
    val bytes = info.getLong("size")
    val identity = "$sourceId|$storage|$parent|$name|$captured|$bytes"
    val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    val time = runCatching {
        LocalDateTime.parse(captured.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
    val previewUri = preview(id)
    val asset = PhotoAsset(
        id = id, uri = previewUri ?: "openbridge-remote://$id", name = name, format = format, sizeBytes = bytes,
        capturedAtMillis = time, width = info.getInt("width").takeIf { it > 0 }, height = info.getInt("height").takeIf { it > 0 },
        camera = cameraName, sourceId = sourceId, folder = "card:$storage/parent:$parent", downloadState = DownloadState.REMOTE,
        cameraFileKey = cameraFileKey(sourceId, "card:$storage", name, bytes, time), inImportList = true, previewUri = previewUri,
        cameraCaptureKey = cameraCaptureKey(sourceId, name, time)
    )
    return RemotePhoto(asset, handle, storage, sourceId, captured)
}
