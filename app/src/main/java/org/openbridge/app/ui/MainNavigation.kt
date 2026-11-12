package org.openbridge.app.ui

import androidx.compose.runtime.saveable.Saver
import org.openbridge.app.domain.PhotoCollection

internal enum class MainTab(val title: String, val glyph: BridgeGlyph, val collection: PhotoCollection? = null) {
    LIBRARY("图库", BridgeGlyph.PHOTOS, PhotoCollection.LIBRARY),
    IMPORT("导入", BridgeGlyph.DOWNLOAD, PhotoCollection.CAMERA_IMPORT),
    TASKS("任务", BridgeGlyph.TASKS), SETTINGS("设置", BridgeGlyph.SETTINGS)
}

// Names survive reordering. Every legacy ordinal falls back, including ordinals still valid in the shorter list.
internal fun restoredMainTab(saved: Any?): MainTab = MainTab.entries.firstOrNull { it.name == saved } ?: MainTab.LIBRARY

internal val MainTabSaver = Saver<MainTab, Any>(save = { it.name }, restore = { restoredMainTab(it) })
