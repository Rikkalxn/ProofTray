package org.openbridge.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openbridge.app.domain.PreviewDimensions

class CameraPreviewPolicyTest {
    @Test fun nikonCanProbeLargeThumbnailWhenDeviceInfoOmitsIt() {
        val commands = previewCommands(false, setOf(GET_THUMB), nikon = true, rejected = emptySet())
        assertEquals(listOf(GET_LARGE_THUMB, GET_THUMB), commands.map { it.operation })
        assertTrue(commands.first().parameters(42).contentEquals(longArrayOf(42)))
    }

    @Test fun unknownVendorDoesNotReceiveAnUnadvertisedNikonCommand() {
        val commands = previewCommands(true, setOf(GET_THUMB), nikon = false, rejected = emptySet())
        assertEquals(listOf(GET_THUMB), commands.map { it.operation })
    }

    @Test fun explicitlyUnsupportedOperationsAreNotRetriedForEachPhoto() {
        val commands = previewCommands(true, setOf(GET_LARGE_THUMB, GET_FHD_PICTURE), true, setOf(GET_LARGE_THUMB))
        assertEquals(listOf(GET_FHD_PICTURE, GET_THUMB), commands.map { it.operation })
    }

    @Test fun gridUsesVgaAndDetailedViewUsesFullHdPortableCopy() {
        val supported = setOf(GET_LARGE_THUMB, GET_FHD_PICTURE, GET_LSS_IMAGE)
        val grid = previewCommands(false, supported, true, emptySet())
        val detail = previewCommands(true, supported, true, emptySet())
        assertTrue(grid.single { it.operation == GET_LSS_IMAGE }.parameters(42).contentEquals(longArrayOf(42, 1, 0)))
        assertTrue(detail.single { it.operation == GET_LSS_IMAGE }.parameters(42).contentEquals(longArrayOf(42, 3, 0)))
        assertEquals(GET_LARGE_THUMB, grid.first().operation)
        assertEquals(GET_LARGE_THUMB, detail.first().operation)
    }

    @Test fun successfulTinyImageStillNeedsAnUpgrade() {
        assertFalse(PreviewDimensions(190, 60).sufficient(false))
        assertFalse(PreviewDimensions(190, 60).sufficient(true))
        assertTrue(PreviewDimensions(640, 424).sufficient(false))
        assertFalse(PreviewDimensions(640, 424).sufficient(true))
        assertTrue(PreviewDimensions(1920, 1280).sufficient(true))
        assertTrue(PreviewDimensions(1080, 1620).sufficient(true))
        assertFalse(PreviewDimensions(1920, 60).sufficient(true))
    }

    @Test fun smallerDetailCacheMustNotReplaceLargerGridCache() {
        val tinyDetail = PreviewDimensions(160, 120)
        val largeGrid = PreviewDimensions(640, 424)
        assertTrue(largeGrid.improves(tinyDetail))
        assertFalse(tinyDetail.improves(largeGrid))
        assertFalse(PreviewDimensions(-1, -1).improves(null))
    }

    @Test fun missingPreviewCanFallBackButConnectionAndBusyErrorsCannot() {
        assertTrue(canTryAnotherPreview(0x2010))
        assertTrue(canTryAnotherPreview(0xa00f))
        assertFalse(canTryAnotherPreview(0x2019))
        assertFalse(canTryAnotherPreview(0x2009))
        assertFalse(canTryAnotherPreview(0x2003))
    }
}
