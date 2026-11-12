package org.openbridge.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PickState

class PhotoOrientationTest {
    private fun raw(tag: Int? = 6, width: Int = 6000, height: Int = 4000) = PhotoAsset(
        "photo", "content://photos/1", "DSC0001.NEF", MediaFormat.RAW, width = width, height = height, sourceOrientation = tag
    )

    @Test fun matchingTagStillRepairsOldUnorientedDimensions() {
        val original = SourceOrientation(6, 6000, 4000)
        val corrected = original.applyTo(raw())
        assertEquals(4000, corrected.width)
        assertEquals(6000, corrected.height)
        assertEquals(corrected, original.applyTo(corrected))
        assertEquals(6, previewOrientation(1, corrected.sourceOrientation, 1920, 1280, original.displayWidth, original.displayHeight))
    }

    @Test fun freshOriginalReplacesAStaleNormalTag() {
        val original = SourceOrientation(8, 6000, 4000)
        val corrected = original.applyTo(raw(tag = 1))
        assertEquals(8, corrected.sourceOrientation)
        assertEquals(4000, corrected.width)
        assertEquals(8, previewOrientation(null, corrected.sourceOrientation, 1920, 1280, original.displayWidth, original.displayHeight))
    }

    @Test fun bakedPortraitPreviewIsNotRotatedTwice() {
        val original = SourceOrientation(6, 6000, 4000)
        assertEquals(1, previewOrientation(1, 6, 1280, 1920, original.displayWidth, original.displayHeight))
        assertEquals(1, previewOrientation(6, 6, 1280, 1920, original.displayWidth, original.displayHeight))
        assertEquals(raw(width = 4000, height = 6000), original.applyTo(raw(width = 4000, height = 6000)))
    }

    @Test fun incompleteGeometryDoesNotToggleDimensionsOnRepeatedLoads() {
        val original = SourceOrientation(6, null, null)
        val old = raw()
        assertEquals(old, original.applyTo(old))
        assertEquals(old, original.applyTo(original.applyTo(old)))
        val unknown = raw(tag = null)
        val resolved = original.applyTo(unknown)
        assertEquals(unknown.width, resolved.width)
        assertEquals(resolved, original.applyTo(resolved))
    }

    @Test fun geometryRepairPreservesResolutionAndUserMetadata() {
        val original = raw().copy(rating = 5, pickState = PickState.PICKED, tags = setOf("保留"), stackId = "stack",
            importedAtMillis = 123L, jpegCopyImportedAtMillis = 456L, inImportList = true, derivedFromId = "parent")
        val measured = SourceOrientation(6, 640, 480).applyTo(original).copy(rating = 0, tags = emptySet(), importedAtMillis = 999L)
        assertEquals(original.copy(width = 4000, height = 6000), original.withGeometryFrom(measured))
    }

    @Test fun delayedUpdateCannotOverwriteAReplacedOrRemovedSource() {
        val original = raw()
        val resolved = SourceOrientation(6, 6000, 4000).applyTo(original)
        val replaced = original.copy(uri = "content://photos/2")
        assertEquals(replaced, replaced.withGeometryFrom(resolved))
        val remote = original.copy(downloadState = DownloadState.REMOTE)
        assertEquals(remote, remote.withGeometryFrom(resolved))
    }
}
