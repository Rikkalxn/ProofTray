package org.openbridge.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoSelectionTest {
    @Test fun viewerEditKeepsPreviouslySelectedPhotoEvenWhenRatingNoLongerMatches() {
        assertEquals(setOf("a", "b"), selectionAfterQuery(setOf("a", "b"), setOf("b", "c"), setOf("a", "b", "c"), setOf("a")))
    }

    @Test fun viewerEditNeverSelectsAPhotoOrRestoresADeletedFile() {
        assertEquals(setOf("b"), selectionAfterQuery(setOf("a", "b"), setOf("b"), setOf("b", "c"), setOf("a", "c")))
    }

    @Test fun changingFilterUsesTheExistingVisibleSelectionRule() {
        assertEquals(setOf("b"), selectionAfterQuery(setOf("a", "b"), setOf("b"), setOf("a", "b"), emptySet()))
    }
}
