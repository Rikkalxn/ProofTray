package org.prooftray.app.ui

import androidx.compose.runtime.saveable.Saver
import org.prooftray.app.domain.PhotoCollection

internal enum class MainTab(val title: String, val glyph: AppGlyph, val collection: PhotoCollection? = null) {
    LIBRARY("图库", AppGlyph.PHOTOS, PhotoCollection.LIBRARY),
    IMPORT("导入", AppGlyph.DOWNLOAD, PhotoCollection.CAMERA_IMPORT),
    TASKS("任务", AppGlyph.TASKS), SETTINGS("设置", AppGlyph.SETTINGS)
}

// Names survive reordering. Every legacy ordinal falls back, including ordinals still valid in the shorter list.
internal fun restoredMainTab(saved: Any?): MainTab = MainTab.entries.firstOrNull { it.name == saved } ?: MainTab.LIBRARY

internal val MainTabSaver = Saver<MainTab, Any>(save = { it.name }, restore = { restoredMainTab(it) })
