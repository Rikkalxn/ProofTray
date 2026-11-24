package org.openbridge.app.domain

internal fun CatalogResult.visiblePhotoIds(): Set<String> = buildSet {
    for (group in groups) group.members.mapTo(this) { it.id }
}

internal fun CatalogResult.selectedPhotos(ids: Set<String>): List<PhotoAsset> =
    groups.asSequence().flatMap { it.members }.filter { it.id in ids }.distinctBy { it.id }.toList()

/** Viewer actions may move the current photo outside a filter, but must not silently change the existing multi-selection. */
internal fun selectionAfterQuery(selected: Set<String>, visible: Set<String>, available: Set<String>, retained: Set<String>): Set<String> =
    selected.filterTo(linkedSetOf()) { it in available && (it in visible || it in retained) }
