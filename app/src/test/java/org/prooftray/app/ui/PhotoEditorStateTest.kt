package org.prooftray.app.ui

import org.junit.Assert.*
import org.junit.Test
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.ExportDialogDraft
import org.prooftray.app.domain.ExportFormat
import org.prooftray.app.domain.ExportPreferences
import org.prooftray.app.domain.PhotoEditTab
import org.prooftray.app.frame.FrameTemplates

class PhotoEditorStateTest {
    @Test fun browsingAnUnchangedDraftClosesWithoutConfirmation() {
        val session = PhotoEditorState(listOf("a", "b"), "a", PhotoEditTab.COLOR, ColorLookSettings(exposure = 1f), FrameTemplates.blank())
        session.tab.value = PhotoEditTab.FRAME
        session.index.intValue = 1
        session.frameState.value.getOrThrow().selectedId = "selection"
        assertFalse(session.hasChanges)
        assertTrue(session.requestClose())
        assertFalse(session.confirmingClose.value)
    }

    @Test fun changingAndRestoringTheLookUsesTheOpeningSnapshot() {
        val look = ColorLookSettings(name = "打开时的调色", exposure = 0.5f)
        val session = PhotoEditorState(listOf("a"), "a", PhotoEditTab.COLOR, look, null)
        session.look.value = look.copy(exposure = 1f)
        assertFalse(session.requestClose())
        assertTrue(session.confirmingClose.value)
        session.look.value = look
        assertTrue(session.requestClose())
        assertFalse(session.confirmingClose.value)
    }

    @Test fun restoringTheFrameStillWarnsBeforeLosingItsUndoHistory() {
        val template = FrameTemplates.blank()
        val session = PhotoEditorState(listOf("a"), "a", PhotoEditTab.FRAME, ColorLookSettings(), template)
        val editor = session.frameState.value.getOrThrow()
        editor.clear()
        assertFalse(session.requestClose())
        editor.undo()
        assertEquals(template, session.frame.value)
        assertTrue(editor.canRedo)
        assertFalse(session.requestClose())
    }

    @Test fun closeFinishesAnActiveGestureBeforeCheckingTheDraft() {
        val session = PhotoEditorState(listOf("a"), "a", PhotoEditTab.FRAME, ColorLookSettings(), FrameTemplates.blank())
        val editor = session.frameState.value.getOrThrow()
        editor.beginGesture()
        editor.change { it.copy(custom = "修改文字") }
        assertFalse(editor.canUndo)
        assertFalse(session.requestClose())
        assertTrue(editor.canUndo)
    }

    @Test fun removingPhotosClampsTheRetainedPageToTheLastRemainingPhoto() {
        val session = PhotoEditorState(listOf("a", "b", "c"), "c", PhotoEditTab.COLOR, ColorLookSettings(), null)
        session.clampIndex(2)
        assertEquals(1, session.index.intValue)
        session.clampIndex(1)
        assertEquals(0, session.index.intValue)
        session.clampIndex(0)
        assertEquals(0, session.index.intValue)
    }

    @Test fun returningToTheRetainedSessionKeepsDraftsPositionAndUndoHistory() {
        val template = FrameTemplates.blank()
        val session = PhotoEditorState(listOf("a", "b"), "a", PhotoEditTab.COLOR, ColorLookSettings(), template)
        val host = LibraryUiState(editor = session)
        val frame = session.frameState.value.getOrThrow()
        session.look.value = ColorLookSettings(name = "调整", exposure = 1f)
        session.tab.value = PhotoEditTab.FRAME
        session.index.intValue = 1
        frame.change { it.copy(custom = "保留文字") }
        assertFalse(session.requestClose())

        val rebound = checkNotNull(host.copy(isLoading = true).editor)
        assertSame(session, rebound)
        assertTrue(rebound.confirmingClose.value)
        assertTrue(rebound.hasChanges)
        assertEquals(1f, rebound.look.value.exposure)
        assertEquals(PhotoEditTab.FRAME, rebound.tab.value)
        assertEquals(1, rebound.index.intValue)
        assertEquals("保留文字", rebound.frame.value?.custom)
        rebound.frameState.value.getOrThrow().undo()
        assertEquals(template, rebound.frame.value)
        assertFalse(rebound.requestClose())
    }

    @Test fun exportDetourKeepsOutputChoicesAndDoesNotChangeTheLibrarySelection() {
        val choices = ExportDialogDraft(ExportPreferences(format = ExportFormat.PNG), color = false, frame = true)
        val session = PhotoEditorState(listOf("c"), "c", PhotoEditTab.FRAME, ColorLookSettings(), null, choices)
        session.exporting.value = true
        val host = LibraryUiState(selectedIds = setOf("a", "b"), editor = session, exportIds = listOf("c"))
        val rebound = host.copy(isLoading = true)
        assertEquals(setOf("a", "b"), rebound.selectedIds)
        assertEquals(listOf("c"), rebound.exportIds)
        assertEquals(choices, rebound.editor?.exportDraft?.value)
        assertTrue(checkNotNull(rebound.editor).exporting.value)
    }

    @Test fun lateResourceImportInvalidatesTheCurrentFramePanel() {
        val session = PhotoEditorState(listOf("a"), "a", PhotoEditTab.FRAME, ColorLookSettings(), FrameTemplates.blank())
        val importingPanel = session.frameState.value.getOrThrow()
        session.tab.value = PhotoEditTab.COLOR
        session.tab.value = PhotoEditTab.FRAME
        importingPanel.resourcesChanged()
        assertEquals(1, session.frameState.value.getOrThrow().resourcesRevision)
    }
}
