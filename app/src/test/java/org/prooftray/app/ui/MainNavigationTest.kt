package org.prooftray.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigationTest {
    @Test fun everyLegacyOrdinalFallsBackToLibrary() {
        for (ordinal in -1..5) assertEquals(MainTab.LIBRARY, restoredMainTab(ordinal))
    }

    @Test fun currentNamesRestoreWithoutDependingOnOrder() {
        for (tab in MainTab.entries) assertEquals(tab, MainTabSaver.restore(tab.name))
    }

    @Test fun removedOrUnknownTabsFallBackSafely() {
        for (saved in listOf(null, "CAMERA", "UNKNOWN", "2", listOf(2))) {
            assertEquals(MainTab.LIBRARY, restoredMainTab(saved))
        }
    }
}
