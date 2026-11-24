package org.prooftray.app.domain

/** Clearing the ledger never changes local files/rows. Orphan queued candidates become remote once no imports are active. */
internal fun resetImportHistory(assets: List<PhotoAsset>, importsActive: Boolean): List<PhotoAsset> {
    check(!importsActive) { "请先完成或取消导入任务，再清除导入历史" }
    return assets.map { asset ->
        if (asset.downloadState == DownloadState.DOWNLOADED) asset
        else asset.copy(importedAtMillis = null, jpegCopyImportedAtMillis = null, downloadState = DownloadState.REMOTE)
    }
}
