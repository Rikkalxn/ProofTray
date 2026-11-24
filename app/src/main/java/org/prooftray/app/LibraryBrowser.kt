package org.prooftray.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.prooftray.app.domain.CatalogResult
import org.prooftray.app.domain.FilterSpec
import org.prooftray.app.domain.ImportVisibility
import org.prooftray.app.domain.LibrarySection
import org.prooftray.app.domain.PhotoBrowserState
import org.prooftray.app.domain.PhotoCollection
import org.prooftray.app.domain.SortOrder
import org.prooftray.app.domain.collectionAssets
import org.prooftray.app.domain.selectionAfterQuery
import org.prooftray.app.domain.visiblePhotoIds
import org.prooftray.app.ui.LibraryUiState

/** Owns navigation, filtering and selection for the library and camera import lists. */
internal class LibraryBrowser(
    scope: CoroutineScope, private val state: MutableStateFlow<LibraryUiState>, private val setCameraSort: (SortOrder) -> Unit,
    onQueryRunning: (Boolean) -> Unit, onError: (String) -> Unit
) {
    private var retainedSelection = emptySet<String>()
    private val query = CatalogQueryRunner(scope, onQueryRunning, ::applyCatalog) { error ->
        onError(if (error is LinkageError) "C++ 筛选库未能加载，请检查应用的原生库打包：${error.message}"
            else "筛选失败：${error.message ?: "无法读取目录"}")
    }

    fun setCollection(collection: PhotoCollection) = updateBrowser(state.value.browser.copy(collection = collection), changePage = true)

    fun setLibrarySection(section: LibrarySection, sourceId: String?) {
        val current = state.value.browser
        val parent = sourceId.takeIf { section == LibrarySection.EXPORTS }
        updateBrowser(current.copy(collection = PhotoCollection.LIBRARY, librarySection = section, exportSourceId = parent,
            versionsFilter = if (parent == current.exportSourceId) current.versionsFilter else FilterSpec()), changePage = true)
    }

    fun setFilter(filter: FilterSpec) = updateBrowser(state.value.browser.withFilter(filter))
    fun setImportVisibility(visibility: ImportVisibility) = updateBrowser(state.value.browser.copy(importVisibility = visibility))
    fun setOfflinePreviews(visible: Boolean) = updateBrowser(state.value.browser.copy(showOfflinePreviews = visible))

    private fun updateBrowser(next: PhotoBrowserState, changePage: Boolean = false) {
        val previous = state.value.browser
        if (changePage && next == previous) return
        retainedSelection = emptySet()
        state.update { current ->
            val catalog = if (changePage) CatalogResult(emptyList(), 0, 0) else current.catalog
            current.copy(browser = next, selectedIds = emptySet(), catalog = catalog)
        }
        if (next.collection == PhotoCollection.CAMERA_IMPORT && (changePage || previous.importFilter.sortOrder != next.importFilter.sortOrder)) {
            setCameraSort(next.importFilter.sortOrder)
        }
        refresh(catalogCleared = changePage)
    }

    fun setCollapseStacks(enabled: Boolean) {
        state.update { it.copy(collapseStacks = enabled) }
        refresh()
    }

    fun refresh(catalogCleared: Boolean = false) {
        val current = state.value
        val source = current.camera.sourceId.takeIf { current.camera.connected }
        val members = collectionAssets(current.assets, current.browser, source, current.camera.catalogIds, current.settings.effectiveManualVariant)
        query.submit(members, current.filter, current.collapseStacks, catalogCleared)
    }

    private fun applyCatalog(catalog: CatalogResult) {
        val visible = catalog.visiblePhotoIds()
        state.update { current ->
            val available = current.assets.mapTo(hashSetOf()) { it.id }
            current.copy(catalog = catalog, selectedIds = selectionAfterQuery(current.selectedIds, visible, available, retainedSelection))
        }
    }

    fun toggleSelection(id: String) {
        val current = state.value
        if (id !in current.selectedIds && current.catalog.groups.none { group -> group.members.any { it.id == id } }) return
        retainedSelection -= id
        state.update { it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id) }
    }

    fun selectVisible() {
        retainedSelection = emptySet()
        state.update { it.copy(selectedIds = it.catalog.visiblePhotoIds()) }
    }

    fun clearSelection() {
        retainedSelection = emptySet()
        state.update { it.copy(selectedIds = emptySet()) }
    }

    fun deselect(ids: Set<String>) {
        retainedSelection -= ids
        state.update { it.copy(selectedIds = it.selectedIds - ids) }
    }

    fun retainViewerSelection(ids: Set<String>) {
        retainedSelection += ids.intersect(state.value.selectedIds)
    }
}
