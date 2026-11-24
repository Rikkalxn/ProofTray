package org.prooftray.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothRetryBudgetTest {
    @Test fun repeatedObserversDoNotResetFailureOrBackoff() {
        val budget = BluetoothRetryBudget()
        budget.failed(1000, 30000)
        assertEquals(30000L, budget.state().delayMillis(1000))
        assertEquals(1, budget.state().failures)
        budget.failed(40000, 30000)
        assertEquals(60000L, budget.state().delayMillis(40000))
        budget.failed(110000, 30000)
        assertTrue(budget.state().exhausted)
        budget.reset()
        assertFalse(budget.state().exhausted)
        assertEquals(0L, budget.state().delayMillis(200000))
    }

    @Test fun unsupportedPendingItemsRequireAnExplicitRearm() {
        val budget = BluetoothRetryBudget()
        budget.block("unsupported pending items")
        assertTrue(budget.state().exhausted)
        assertEquals("unsupported pending items", budget.state().blockedReason)
        budget.reset()
        assertFalse(budget.state().exhausted)
    }
}
