package org.prooftray.app.camera

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SonyTransferCompletionTest {
    @Test fun completePayloadToleratesOnlyTheFinalAcknowledgementFailure() {
        for (error in listOf(IOException("closed"), SocketTimeoutException("timeout"), PtpResponseException(SONY_FILE, 0x2009))) {
            assertSame(error, finishSonyPayload(100, 100) { throw error })
        }
        assertNull(finishSonyPayload(100, 100) { })
    }

    @Test fun partialOrOversizedPayloadCannotBeReportedAsFinished() {
        for (received in listOf(0L, 99L, 101L)) {
            assertThrows(IllegalStateException::class.java) { finishSonyPayload(received, 100) { fail("Must not send an end marker") } }
        }
    }

    @Test fun cancellationAndProgrammingErrorsStillPropagate() {
        assertThrows(CancellationException::class.java) { finishSonyPayload(100, 100) { throw CancellationException("user cancelled") } }
        assertThrows(IllegalStateException::class.java) { finishSonyPayload(100, 100) { error("invalid state") } }
    }
}
