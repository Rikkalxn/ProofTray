package org.openbridge.app.ui

import org.junit.Assert.*
import org.junit.Test
import org.openbridge.app.domain.FrameTemplate
import org.openbridge.app.domain.FrameInsets
import org.openbridge.app.frame.FrameTemplates

class FrameEditorStateTest {
    @Test fun linkingInsetsKeepsSpaceForContentAndCanBeUndone() {
        val base = FrameTemplates.blank()
        val template = base.copy(design = base.design.copy(canvas = base.design.canvas.copy(insets = FrameInsets(0.02f, 0.03f, 0.15f, 0.04f))))
        val editor = FrameEditorState(template)
        editor.setUniformInsets(600, 400, true)
        assertEquals(FrameInsets(0.15f, 0.15f, 0.15f, 0.15f), editor.template.design.canvas.insets)
        editor.undo()
        assertEquals(template, editor.template)
        editor.redo()
        assertEquals(FrameInsets(0.15f, 0.15f, 0.15f, 0.15f), editor.template.design.canvas.insets)
        editor.setUniformInsets(600, 400, false)
        assertFalse(editor.uniformInsets)
        assertEquals(FrameInsets(0.15f, 0.15f, 0.15f, 0.15f), editor.template.design.canvas.insets)
    }

    @Test fun clearingAFrameIsUndoableAndNotifiesTheSharedDraft() {
        val template = FrameTemplates.blank()
        var draft: FrameTemplate? = template
        val editor = FrameEditorState(template) { draft = it }
        editor.clear()
        assertNull(draft)
        assertFalse(editor.enabled)
        editor.undo()
        assertEquals(template, draft)
        assertTrue(editor.enabled)
        editor.redo()
        assertNull(draft)
    }
    @Test fun choosingTheInitialBlankTemplateEnablesItOnlyAfterUserAction() {
        val template = FrameTemplates.blank()
        var draft: FrameTemplate? = null
        val editor = FrameEditorState(template, initiallyEnabled = false) { draft = it }
        assertFalse(editor.enabled)
        editor.replace(template)
        assertEquals(template, draft)
        editor.undo()
        assertNull(draft)
    }
}
