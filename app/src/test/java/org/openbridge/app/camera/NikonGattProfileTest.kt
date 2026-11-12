package org.openbridge.app.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import no.nordicsemi.android.ble.observer.ConnectionObserver

class NikonGattProfileTest {
    @Test fun fullProfileEnablesNotificationsBeforeIndications() = runBlocking {
        val packets = mutableListOf<Pair<String, Boolean>>()
        subscribeNikonProfile({ true }) { id, indication -> packets += id to indication; 0 }
        assertEquals(listOf("2008" to false, "200a" to false, "2081" to false,
            "2000" to true, "2020" to true, "2021" to true), packets)
    }

    @Test fun requiredSubscriptionsWaitForAcknowledgementAndSkipAnOrphanControlCharacteristic() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val acknowledge = CompletableDeferred<Unit>()
        val packets = mutableListOf<String>()
        val setup = launch {
            subscribeNikonProfile({ it == "2021" }) { id, _ ->
                packets += id
                if (id == "2008") { entered.complete(Unit); acknowledge.await() }
                0
            }
        }
        entered.await()
        assertEquals(listOf("2008"), packets)
        acknowledge.complete(Unit)
        setup.join()
        assertEquals(listOf("2008", "2000"), packets)
    }

    @Test fun controlBusyUsesTheReferenceRecoveryWindow() = runBlocking {
        var controlWrites = 0
        var authenticationIndications = 0
        subscribeNikonProfile({ false }) { id, _ ->
            if (id == "2000") { authenticationIndications++; 0 }
            else if (++controlWrites == 1) 0x81 else 0
        }
        assertEquals(51, controlWrites)
        assertEquals(1, authenticationIndications)
    }

    @Test fun rejectedControlNotificationStopsBeforeAuthentication() {
        val packets = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { subscribeNikonProfile({ true }) { id, _ -> packets += id; 5 } }
        }
        assertEquals(listOf("2008"), packets)
    }

    @Test fun cancellationDuringControlSubscriptionNeverStartsTheNextDescriptor() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val packets = mutableListOf<String>()
        val job = launch {
            subscribeNikonProfile({ true }) { id, _ -> packets += id; entered.complete(Unit); pending.await(); 0 }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(listOf("2008"), packets)
    }

    @Test fun connectionReasonsDoNotBorrowGattRetrySemantics() {
        assertTrue(retryableBleFailure(nikonDisconnectFailure(ConnectionObserver.REASON_TIMEOUT)))
        assertTrue(retryableBleFailure(nikonDisconnectFailure(ConnectionObserver.REASON_TERMINATE_PEER_USER)))
        assertFalse(retryableBleFailure(nikonDisconnectFailure(ConnectionObserver.REASON_CANCELLED)))
        assertFalse(retryableBleFailure(nikonDisconnectFailure(ConnectionObserver.REASON_NOT_SUPPORTED)))
        assertFalse(retryableBleFailure(BleTransportException(2)))
    }

    @Test fun optionalNotificationCanBeAbsentButAuthenticationDescriptorMustComplete() {
        val packets = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { subscribeNikonProfile({ true }) { id, _ -> packets += id; if (id == "2008") 0 else null } }
        }
        assertEquals(listOf("2008", "200a", "2081", "2000"), packets)
    }
}
