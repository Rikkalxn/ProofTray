package org.openbridge.app.data

import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.PhotoAsset

/** Camera refreshes keep local files and user marks; only an explicit queue reset drops a queued marker. */
internal fun mergeCameraAsset(previous: PhotoAsset?, incoming: PhotoAsset, resetQueued: Boolean): PhotoAsset {
    if (previous == null) return incoming
    if (previous.downloadState == DownloadState.DOWNLOADED && incoming.downloadState != DownloadState.DOWNLOADED) {
        return previous.copy(inImportList = incoming.inImportList, previewUri = incoming.previewUri ?: previous.previewUri,
            previewRevision = incoming.previewRevision ?: previous.previewRevision,
            sourceOrientation = previous.sourceOrientation ?: incoming.sourceOrientation)
    }
    val queued = previous.downloadState == DownloadState.QUEUED && incoming.downloadState == DownloadState.REMOTE && !resetQueued
    val keepGeometry = incoming.sourceOrientation == null && previous.sourceOrientation != null
    return incoming.copy(rating = previous.rating, pickState = previous.pickState, tags = previous.tags, stackId = previous.stackId,
        sourceOrientation = incoming.sourceOrientation ?: previous.sourceOrientation,
        width = if (keepGeometry) previous.width ?: incoming.width else incoming.width,
        height = if (keepGeometry) previous.height ?: incoming.height else incoming.height,
        derivedFromId = if (previous.downloadState == DownloadState.DOWNLOADED) previous.derivedFromId else incoming.derivedFromId,
        downloadState = if (queued) DownloadState.QUEUED else incoming.downloadState,
        hiddenFromLibrary = previous.hiddenFromLibrary && incoming.downloadState != DownloadState.DOWNLOADED,
        previewUri = incoming.previewUri ?: previous.previewUri, previewRevision = incoming.previewRevision ?: previous.previewRevision)
}
