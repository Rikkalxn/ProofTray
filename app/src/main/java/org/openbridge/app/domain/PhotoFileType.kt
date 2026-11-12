package org.openbridge.app.domain

import java.util.Locale

private val rawMime = mapOf(
    "arw" to "image/x-sony-arw", "nef" to "image/x-nikon-nef", "nrw" to "image/x-nikon-nrw", "dng" to "image/x-adobe-dng",
    "cr2" to "image/x-canon-cr2", "cr3" to "image/x-canon-cr3", "raf" to "image/x-fuji-raf", "rw2" to "image/x-panasonic-rw2",
    "orf" to "image/x-olympus-orf", "pef" to "image/x-pentax-pef", "srw" to "image/x-samsung-srw"
)

/** File extensions take precedence over the generic MIME type some camera and document providers report for RAW. */
internal fun photoMimeType(name: String): String? = when (val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "jpg", "jpeg", "jpe" -> "image/jpeg"
    "png" -> "image/png"
    "heic" -> "image/heic"
    "heif", "hif" -> "image/heif"
    "tif", "tiff" -> "image/tiff"
    else -> rawMime[extension]
}

internal fun photoMediaFormat(name: String, providerMime: String? = null): MediaFormat {
    return when (val mime = photoMimeType(name) ?: providerMime?.lowercase(Locale.ROOT)) {
        "image/jpeg" -> MediaFormat.JPEG
        "image/png" -> MediaFormat.PNG
        "image/heic", "image/heif" -> MediaFormat.HEIF
        "image/tiff" -> MediaFormat.TIFF
        else -> if (mime == "image/x-raw" || (mime != null && rawMime.containsValue(mime))) MediaFormat.RAW else MediaFormat.OTHER
    }
}
