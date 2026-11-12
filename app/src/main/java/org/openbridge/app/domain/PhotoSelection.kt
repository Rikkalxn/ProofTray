package org.openbridge.app.domain

/** Viewer actions may move the current photo outside a filter, but must not silently change the existing multi-selection. */
internal fun selectionAfterQuery(selected: Set<String>, visible: Set<String>, available: Set<String>, retained: Set<String>): Set<String> =
    selected.filterTo(linkedSetOf()) { it in available && (it in visible || it in retained) }
