package org.openbridge.app.domain

/** Keep editor visibility and conversion validation on the same set of supported input formats. */
internal val MediaFormat.supportsRendering: Boolean get() = when (this) {
    MediaFormat.JPEG, MediaFormat.PNG, MediaFormat.HEIF, MediaFormat.RAW -> true
    else -> false
}

internal val PhotoAsset.canEdit: Boolean get() = downloadState == DownloadState.DOWNLOADED && format.supportsRendering
