package org.prooftray.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.ColorPreset
import org.prooftray.app.domain.LutAsset
import org.prooftray.app.domain.LutRange
import org.prooftray.app.domain.LutUsage

class ColorImportSelectionTest {
    @Test fun reimportSelectsActualResultUsingItsSavedType() {
        val a = LutAsset("a".repeat(64), "Alpha", 3, 33, "CUBE", LutUsage.direct)
        val z = a.copy(id = "b".repeat(64), name = "Zebra", defaultUsage = LutUsage.rec709)
        val state = LibraryUiState(luts = listOf(a, z))
        val imported = state.finishColorImport(z.id)
        assertEquals(z.look().lutKey, imported.importedLookKey)
        val repeated = imported.finishColorImport(z.id)
        assertEquals(z.look().lutKey, repeated.importedLookKey)
        assertEquals(imported.colorImportRevision + 1, repeated.colorImportRevision)
    }

    @Test fun cancellationEndsTheRequestAndPresetsCanBeSelectedWithoutANewTable() {
        val preset = ColorPreset("my-preset", "Warm", ColorLookSettings(warmth = .2f))
        val imported = LibraryUiState(colorPresets = listOf(preset)).finishColorImport(preset.id)
        assertEquals("preset:my-preset", imported.importedLookKey)
        val cancelled = imported.finishColorImport(null)
        assertNull(cancelled.importedLookKey)
        assertEquals(imported.colorImportRevision + 1, cancelled.colorImportRevision)
    }

    @Test fun unsupportedTableDefaultsAndPresetsAreNotAutomaticallySelected() {
        val unknown = LutUsage("FUTURE_LOG", "REC709_G24", LutRange.FULL, LutRange.FULL)
        val table = LutAsset("a".repeat(64), "Unknown", 3, 33, "CUBE", unknown)
        val preset = ColorPreset("preset-id", "Future", table.look())
        val library = LibraryUiState(luts = listOf(table), colorPresets = listOf(preset))
        assertNull(library.finishColorImport(table.id).importedLookKey)
        assertNull(library.finishColorImport(preset.id).importedLookKey)
    }
}
