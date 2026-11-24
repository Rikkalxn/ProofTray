package org.prooftray.app.data

import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.jpegRepresentationKey

/** Recognize camera JPEG identities without treating similarly named app exports or local imports as companions. */
internal fun migrateCameraCompanions(assets: List<PhotoAsset>): List<PhotoAsset> {
    fun fromCamera(asset: PhotoAsset) = asset.sourceId.isNotBlank() && asset.sourceId != "local" && !asset.sourceId.startsWith("document:")
    val sources = assets.filter {
        it.derivedFromId == null && it.companionOfId == null && it.cameraFileKey != null &&
            fromCamera(it) && it.format in setOf(MediaFormat.RAW, MediaFormat.JPEG)
    }.groupBy(::jpegRepresentationKey)
    return assets.map { asset ->
        if (asset.companionOfId != null || asset.format != MediaFormat.JPEG || asset.cameraFileKey != asset.id || !fromCamera(asset)) {
            return@map asset
        }
        val candidates = sources[asset.id].orEmpty().filter { it.id != asset.id }
        // Legacy copies retain the camera source and use their generated ID as cameraFileKey; app exports have a document identity.
        // A promoted copy can recover its RAW link from an exact representation key; a paired JPEG alone cannot identify that RAW.
        val source = asset.derivedFromId ?: candidates.firstOrNull { it.format == MediaFormat.RAW }?.id
        if (source == null) return@map asset
        asset.copy(derivedFromId = null, companionOfId = source)
    }
}
