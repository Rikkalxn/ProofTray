package org.prooftray.app.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.ExportDialogDraft
import org.prooftray.app.domain.FrameTemplate
import org.prooftray.app.domain.PhotoEditTab
import org.prooftray.app.frame.FrameTemplates
import org.prooftray.app.frame.initialFrameTemplate

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
    private val colorUndo = mutableStateOf<List<ColorLookSettings>>(emptyList())
    private val colorRedo = mutableStateOf<List<ColorLookSettings>>(emptyList())
    private var colorGesture: ColorLookSettings? = null
    internal val canUndoColor: Boolean get() = colorUndo.value.isNotEmpty() || colorGesture?.let { it != look.value } == true
    internal val canRedoColor: Boolean get() = colorRedo.value.isNotEmpty()

    internal fun changeLook(value: ColorLookSettings) {
        if (value == look.value) return
        if (colorGesture == null) colorUndo.value = (colorUndo.value + look.value).takeLast(50)
        colorRedo.value = emptyList()
        look.value = value
    }

    internal fun beginColorGesture() { if (colorGesture == null) colorGesture = look.value }
    internal fun endColorGesture() {
        val before = colorGesture ?: return
        colorGesture = null
        if (before != look.value) colorUndo.value = (colorUndo.value + before).takeLast(50)
    }

    internal fun undoColor() {
        endColorGesture()
        val previous = colorUndo.value.lastOrNull() ?: return
        colorUndo.value = colorUndo.value.dropLast(1)
        colorRedo.value = (colorRedo.value + look.value).takeLast(50)
        look.value = previous
        lookKey.value = null
    }

    internal fun redoColor() {
        endColorGesture()
        val next = colorRedo.value.lastOrNull() ?: return
        colorRedo.value = colorRedo.value.dropLast(1)
        colorUndo.value = (colorUndo.value + look.value).takeLast(50)
        look.value = next
        lookKey.value = null
    }
    private data class Snapshot(val look: ColorLookSettings, val frame: FrameTemplate?, val historyRevision: Int)
    private fun snapshot() = Snapshot(look.value, frame.value, frameState.value.getOrNull()?.historyRevision ?: 0)
    private val openingSnapshot = snapshot()

    internal val hasChanges: Boolean get() = snapshot() != openingSnapshot

    internal fun requestClose(): Boolean {
        endColorGesture()
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
