package org.prooftray.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCollectionsTest {
    private fun photo(id: String, state: DownloadState = DownloadState.REMOTE, source: String = "Z6II:123") = PhotoAsset(
        id, "prooftray-remote://$id", "DSC_0001.NEF", MediaFormat.RAW, sizeBytes = 42_000_000,
        downloadState = state, sourceId = source, folder = "card:1/parent:2", inImportList = true, cameraFileKey = "key:$id"
    )

    @Test fun photosContainLocalFilesOnly() {
        val remote = photo("remote")
        val local = photo("local", DownloadState.DOWNLOADED)
        val removed = local.copy(id = "removed", hiddenFromLibrary = true)
        val waiting = photo("waiting", DownloadState.QUEUED)
        val result = collectionAssets(listOf(remote, local, removed, waiting), PhotoBrowserState(), null)
        assertEquals(listOf(local), result)
    }

    @Test fun cameraCompanionsArePhotosAndOnlyAppExportsCountAsVersions() {
        val original = photo("raw", DownloadState.DOWNLOADED)
        val companion = original.copy(id = "portable", format = MediaFormat.JPEG, companionOfId = original.id)
        val local = original.copy(id = "local", sourceId = "document:photos", cameraFileKey = null)
        val exported = original.copy(id = "export", format = MediaFormat.JPEG, derivedFromId = original.id)
        val nested = exported.copy(id = "nested", derivedFromId = exported.id)
        val fromCompanion = exported.copy(id = "from-portable", derivedFromId = companion.id)
        val hidden = exported.copy(id = "hidden", hiddenFromLibrary = true)
        val assets = listOf(original, companion, local, exported, nested, fromCompanion, hidden)
        assertEquals("照片", LibrarySection.ORIGINALS.label)
        assertEquals(listOf(original, companion, local), collectionAssets(assets, PhotoBrowserState(), null))
        val exports = PhotoBrowserState(librarySection = LibrarySection.EXPORTS)
        assertEquals(listOf(exported, nested, fromCompanion), collectionAssets(assets, exports, null))
        assertEquals(listOf(exported, nested), ExportVersions(assets).forSource(original.id))
        assertEquals(listOf(fromCompanion), ExportVersions(assets).forSource(companion.id))
    }

    @Test fun connectedImportContainsOnlyLoadedItemsFromThatCamera() {
        val loaded = photo("loaded")
        val cached = photo("cached")
        val otherCamera = photo("other", source = "Z8:456")
        val browser = PhotoBrowserState(collection = PhotoCollection.CAMERA_IMPORT)
        val result = collectionAssets(listOf(loaded, cached, otherCamera), browser, "Z6II:123", setOf("loaded", "other"))
        assertEquals(listOf(loaded), result)
    }

    @Test fun offlinePreviewsRequireExplicitOptIn() {
        val asset = photo("remote")
        val browser = PhotoBrowserState(collection = PhotoCollection.CAMERA_IMPORT)
        assertTrue(collectionAssets(listOf(asset), browser, null).isEmpty())
        assertEquals(listOf(asset), collectionAssets(listOf(asset), browser.copy(showOfflinePreviews = true), null))
    }

    @Test fun removingLibraryEntryDoesNotMakeImportedFileNewAgain() {
        val imported = photo("remote").copy(importedAtMillis = 123L, hiddenFromLibrary = true)
        val browser = PhotoBrowserState(collection = PhotoCollection.CAMERA_IMPORT, showOfflinePreviews = true)
        assertTrue(collectionAssets(listOf(imported), browser, null).isEmpty())
        assertEquals(listOf(imported), collectionAssets(listOf(imported), browser.copy(importVisibility = ImportVisibility.IMPORTED), null))
        val cleared = imported.copy(inImportList = false)
        assertTrue(collectionAssets(listOf(cleared), browser.copy(importVisibility = ImportVisibility.ALL), null).isEmpty())
    }

    @Test fun missingFileSizeDoesNotHideAnImportCandidate() {
        val unknown = photo("unknown").copy(sizeBytes = 0, cameraFileKey = null)
        val browser = PhotoBrowserState(collection = PhotoCollection.CAMERA_IMPORT, showOfflinePreviews = true)
        assertEquals(listOf(unknown), collectionAssets(listOf(unknown), browser, null))
    }

    @Test fun filtersAreIndependentBetweenCollections() {
        val initial = PhotoBrowserState().withFilter(FilterSpec(minimumRating = 4))
        val importing = initial.copy(collection = PhotoCollection.CAMERA_IMPORT).withFilter(FilterSpec(formats = setOf(MediaFormat.RAW)))
        assertEquals(setOf(MediaFormat.RAW), importing.filter.formats)
        assertEquals(4, importing.copy(collection = PhotoCollection.LIBRARY).filter.minimumRating)
    }

    @Test fun historyUsesCameraStorageNameSizeAndCaptureTime() {
        val original = cameraFileKey("Z6II:123", "card:1", "DSC_0001.NEF", 100, 500)
        assertEquals(original, cameraFileKey("Z6II:123", "card:1", "dsc_0001.nef", 100, 500))
        assertNotEquals(original, cameraFileKey("Z6II:124", "card:1", "DSC_0001.NEF", 100, 500))
        assertNotEquals(original, cameraFileKey("Z6II:123", "card:2", "DSC_0001.NEF", 100, 500))
        assertNotEquals(original, cameraFileKey("Z6II:123", "card:1", "DSC_0001.NEF", 101, 500))
        assertNotEquals(original, cameraFileKey("Z6II:123", "card:1", "DSC_0001.NEF", 100, 501))
        assertNull(cameraFileKey("document:photos", "", "DSC_0001.NEF", 100, 500))
        assertNull(cameraFileKey("Z6II:123", "card:1", "DSC_0001.NEF", 0, 500))
    }
}
