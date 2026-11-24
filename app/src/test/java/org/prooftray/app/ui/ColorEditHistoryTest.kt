package org.prooftray.app.ui

import org.junit.Assert.*
import org.junit.Test
import org.prooftray.app.domain.ColorAdjustments
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.PhotoEditTab

class ColorEditHistoryTest {
    private fun session() = PhotoEditorState(listOf("photo"), "photo", PhotoEditTab.COLOR, ColorLookSettings(), null)

    @Test fun oneDragIsOneUndoStepAndRedoRestoresTheWholeRecipe() {
        val state = session()
        state.beginColorGesture()
        for (step in 1..20) state.changeLook(state.look.value.copy(adjustments = ColorAdjustments(shadows = step / 20f)))
        state.endColorGesture()
        val changed = state.look.value
        assertTrue(state.hasChanges)
        state.undoColor()
        assertEquals(ColorLookSettings(), state.look.value)
        assertFalse(state.canUndoColor)
        assertFalse(state.hasChanges)
        state.redoColor()
        assertEquals(changed, state.look.value)
        assertTrue(state.hasChanges)
    }

    @Test fun aNewEditAfterUndoDropsOnlyRedoHistory() {
        val state = session()
        state.changeLook(state.look.value.copy(exposure = 1f))
        state.changeLook(state.look.value.copy(tint = .3f))
        state.undoColor()
        assertTrue(state.canRedoColor)
        state.changeLook(state.look.value.copy(warmth = -.2f))
        assertFalse(state.canRedoColor)
        state.undoColor()
        assertEquals(1f, state.look.value.exposure, 0f)
        assertEquals(0f, state.look.value.warmth, 0f)
        assertNull(state.frame.value)
    }

    @Test fun closingFinishesTheGestureAndStillOffersDiscard() {
        val state = session()
        state.beginColorGesture()
        state.changeLook(state.look.value.copy(tint = .5f))
        assertFalse(state.requestClose())
        assertTrue(state.confirmingClose.value)
        state.undoColor()
        assertTrue(state.requestClose())
    }

    @Test fun unchangedGesturesDoNotCreateUndoEntries() {
        val state = session()
        state.beginColorGesture()
        state.changeLook(ColorLookSettings())
        state.endColorGesture()
        assertFalse(state.canUndoColor)
        assertFalse(state.hasChanges)
    }
}
