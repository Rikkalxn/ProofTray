package org.prooftray.app.domain

import org.prooftray.app.util.sha256Hex

enum class DownloadVariant { ORIGINAL, CAMERA_JPEG }
enum class AutoDownloadKind { CAMERA_JPEG, JPEG, RAW, BOTH }

fun representationKey(originalKey: String, variant: DownloadVariant): String {
    if (variant == DownloadVariant.ORIGINAL) return originalKey
    return "$originalKey:camera-jpeg-v1".toByteArray().sha256Hex()
}

fun jpegRepresentationKey(asset: PhotoAsset): String =
    representationKey(asset.cameraCaptureKey ?: asset.cameraFileKey ?: asset.id, DownloadVariant.CAMERA_JPEG)

/** Handles are only compared inside one live PTP session. A new connection always establishes a new baseline. */
class NewObjectTracker {
    private var known: Set<Long>? = null
    private var revision: String? = null
    private val pending = linkedSetOf<Long>()
    var baselineReset: Boolean = false
        private set

    fun observe(handles: Set<Long>, sourceRevision: String = ""): List<Long> {
        val previous = known
        baselineReset = previous == null || revision != sourceRevision || !handles.containsAll(previous)
        if (baselineReset) pending.clear() else pending.addAll(handles - previous.orEmpty())
        known = handles.toSet()
        revision = sourceRevision
        return pending.toList()
    }

    fun acknowledge(handle: Long) { pending.remove(handle) }
}

fun acceptsAutoFormat(format: MediaFormat, kind: AutoDownloadKind): Boolean = when (kind) {
    AutoDownloadKind.JPEG -> format == MediaFormat.JPEG
    AutoDownloadKind.RAW -> format == MediaFormat.RAW
    AutoDownloadKind.BOTH, AutoDownloadKind.CAMERA_JPEG -> format == MediaFormat.JPEG || format == MediaFormat.RAW
}
