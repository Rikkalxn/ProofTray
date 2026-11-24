package org.openbridge.app

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openbridge.app.domain.CatalogResult
import org.openbridge.app.domain.FilterSpec

class CatalogQueryRunnerTest {
    @Test fun newPageReceivesResultWhenItsQueryMatchesThePreviousPage() = runBlocking {
        val results = Channel<CatalogResult>(Channel.UNLIMITED)
        val runner = CatalogQueryRunner(this, onRunning = {}, onResult = { results.trySend(it) }, onFailure = { throw it })
        try {
            withTimeout(5000) {
                val first = results.receive()
                runner.submit(emptyList(), FilterSpec(), collapseStacks = true, catalogCleared = true)
                assertEquals(first, results.receive())
            }
        } finally { coroutineContext.cancelChildren() }
    }
}
