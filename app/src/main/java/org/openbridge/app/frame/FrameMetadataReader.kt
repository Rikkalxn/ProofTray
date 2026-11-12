package org.openbridge.app.frame

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.openbridge.app.domain.PhotoAsset

internal object FrameMetadataReader {
    fun read(context: Context, asset: PhotoAsset): Map<String, String> {
        val uri = Uri.parse(asset.uri)
        val original = if (uri.authority == MediaStore.AUTHORITY &&
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            MediaStore.setRequireOriginal(uri)
        } else uri
        fun exif(source: Uri): ExifInterface? = try { context.contentResolver.openInputStream(source)?.use { ExifInterface(it) } }
            catch (_: Exception) { null }
        val exif = exif(original) ?: if (original != uri) exif(uri) else null
        fun tag(key: String): String = exif?.getAttribute(key)?.trim().orEmpty()
        fun number(key: String, fallback: Double?): String {
            val value = exif?.getAttributeDouble(key, Double.NaN)?.takeIf { it.isFinite() && it > 0 } ?: fallback
            return value?.takeIf { it.isFinite() && it > 0 }?.toString().orEmpty()
        }
        // Keep EXIF's floating local time. A phone time-zone change must not change an existing camera timestamp.
        val time = tag(ExifInterface.TAG_DATETIME_ORIGINAL).takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)) }.getOrNull()
        }?.format(DateTimeFormatter.ofPattern("uuuu.MM.dd HH:mm", Locale.ROOT)) ?: asset.capturedAtMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("uuuu.MM.dd HH:mm", Locale.ROOT))
        }.orEmpty()
        val gps = runCatching { exif?.latLong }.getOrNull()?.let { it[0].toString() + "," + it[1] }.orEmpty()
        return mapOf(
            "make" to tag(ExifInterface.TAG_MAKE),
            "camera" to tag(ExifInterface.TAG_MODEL).ifBlank { asset.camera },
            "lens" to tag(ExifInterface.TAG_LENS_MODEL).ifBlank { asset.lens },
            "focal" to number(ExifInterface.TAG_FOCAL_LENGTH, asset.focalLengthMm),
            "focal35" to number(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, null),
            "aperture" to number(ExifInterface.TAG_F_NUMBER, asset.aperture),
            "shutter" to number(ExifInterface.TAG_EXPOSURE_TIME, asset.exposureSeconds),
            "iso" to number(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, asset.iso?.toDouble()),
            "time" to time, "gps" to gps, "author" to tag(ExifInterface.TAG_ARTIST), "copyright" to tag(ExifInterface.TAG_COPYRIGHT)
        ).mapValues { (_, value) -> value.take(2048) }
    }
}
