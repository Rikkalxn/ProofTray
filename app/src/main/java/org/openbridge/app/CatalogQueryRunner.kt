package org.openbridge.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openbridge.app.domain.CatalogEngine
import org.openbridge.app.domain.CatalogResult
import org.openbridge.app.domain.FilterSpec
import org.openbridge.app.domain.PhotoAsset

/** Owns query scheduling and stale-result rejection; selection and page state stay with the ViewModel. */
internal class CatalogQueryRunner(
    scope: CoroutineScope, private val onRunning: (Boolean) -> Unit,
    private val onResult: (CatalogResult) -> Unit, private val onFailure: (Throwable) -> Unit
) {
    private data class Input(val assets: List<PhotoAsset>, val filter: FilterSpec, val collapseStacks: Boolean)
    private val requests = MutableStateFlow(Input(emptyList(), FilterSpec(), true))

    init {
        scope.launch {
            requests.collectLatest { input ->
                onRunning(true)
                try {
                    val result = withContext(Dispatchers.Default) { CatalogEngine.query(input.assets, input.filter, input.collapseStacks) }
                    if (input == requests.value) onResult(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: LinkageError) {
                    onFailure(error)
                } catch (error: Exception) {
                    onFailure(error)
                } finally { onRunning(false) }
            }
        }
    }

    fun submit(assets: List<PhotoAsset>, filter: FilterSpec, collapseStacks: Boolean) {
        requests.value = Input(assets, filter, collapseStacks)
    }
}
