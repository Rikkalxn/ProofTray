package org.openbridge.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resolution and user edits never enter the key. Cancellation must not publish a partial/fallback recipe. */
internal class RawToneCache {
    private val mutex = Mutex()
    private val entries = linkedMapOf<RawToneKey, RawToneProfile>()

    suspend fun resolve(key: RawToneKey?, read: (RawToneKey) -> RawToneProfile?, write: (RawToneKey, RawToneProfile) -> Unit,
        create: suspend () -> RawToneProfile): RawToneProfile = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (key != null) entries[key]?.let { return@withLock it }
        val profile = key?.let(read) ?: create().also { value ->
            currentCoroutineContext().ensureActive()
            if (key != null && cacheable(value)) write(key, value)
        }
        if (key != null && cacheable(profile)) {
            entries[key] = profile
            while (entries.size > 32) entries.remove(entries.keys.first())
        }
        profile
    }

    private fun cacheable(profile: RawToneProfile): Boolean = profile.fallback !in setOf(RawToneFallback.UNAVAILABLE, RawToneFallback.NO_PREVIEW)
}
