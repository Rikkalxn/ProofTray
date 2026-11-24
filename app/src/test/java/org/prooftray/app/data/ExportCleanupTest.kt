package org.prooftray.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportCleanupTest {
    @Test fun lostAccessDuringRecoveryLeavesOutputUntouched() {
        val result = cleanPendingMedia({ throw SecurityException("temporarily unavailable") }) {
            throw AssertionError("must not delete without a readable pending flag")
        }
        assertEquals(OutputState.UNKNOWN, result)
    }

    @Test fun onlyConfirmedPendingRowsCanBeRemoved() {
        for (state in listOf(OutputState.PUBLISHED, OutputState.UNKNOWN, OutputState.ABSENT)) {
            assertEquals(state, cleanPendingMedia({ state }) { throw AssertionError("unexpected delete for $state") })
        }
        assertEquals(OutputState.ABSENT, cleanPendingMedia({ OutputState.PENDING }) { 1 })
    }

    @Test fun aRowPublishedBetweenInspectionAndConditionalDeleteIsRecovered() {
        val observations = listOf(OutputState.PENDING, OutputState.PUBLISHED).iterator()
        assertEquals(OutputState.PUBLISHED, cleanPendingMedia(observations::next) { 0 })
    }
}
