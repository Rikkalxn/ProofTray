package org.openbridge.app.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.ExportDialogDraft
import org.openbridge.app.domain.FrameTemplate
import org.openbridge.app.domain.PhotoEditTab
import org.openbridge.app.frame.FrameTemplates
import org.openbridge.app.frame.initialFrameTemplate

/** Retained by LibraryViewModel during configuration changes. Only small drafts live here, never bitmaps, views or Activity contexts. */
class PhotoEditorState(
    val ids: List<String>, initialId: String, initialTab: PhotoEditTab, initialLook: ColorLookSettings, initialFrame: FrameTemplate?,
    initialExport: ExportDialogDraft? = null, deletedFrameIds: Set<String> = emptySet()
) {
    internal val look = mutableStateOf(initialLook.takeIf { it.usage.unavailableReason == null } ?: ColorLookSettings())
    internal val frame = mutableStateOf(initialFrame)
    internal val lookKey = mutableStateOf<String?>(null)
    internal val awaitingImport = mutableStateOf<Long?>(null)
    internal val tab = mutableStateOf(initialTab)
    internal val index = mutableIntStateOf(ids.indexOf(initialId).coerceAtLeast(0))
    internal val reload = mutableIntStateOf(0)
    internal val exporting = mutableStateOf(false)
    internal val exportDraft = mutableStateOf(initialExport)
    internal val seenDeletions = mutableStateOf(deletedFrameIds)
    internal val frameState = mutableStateOf(createFrameEditor())
    internal val confirmingClose = mutableStateOf(false)
    private data class Snapshot(val look: ColorLookSettings, val frame: FrameTemplate?, val historyRevision: Int)
    private fun snapshot() = Snapshot(look.value, frame.value, frameState.value.getOrNull()?.historyRevision ?: 0)
    private val openingSnapshot = snapshot()

    internal val hasChanges: Boolean get() = snapshot() != openingSnapshot

    internal fun requestClose(): Boolean {
        frameState.value.getOrNull()?.endGesture()
        confirmingClose.value = hasChanges
        return !confirmingClose.value
    }

    internal fun clampIndex(count: Int) { index.intValue = index.intValue.coerceIn(0, (count - 1).coerceAtLeast(0)) }

    private fun createFrameEditor() = runCatching {
        FrameEditorState(initialFrameTemplate(frame.value ?: FrameTemplates.blank()), initiallyEnabled = frame.value != null) {
            frame.value = it
        }
    }

    internal fun retryFrame() { frameState.value = createFrameEditor() }
}
