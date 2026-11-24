package org.prooftray.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.prooftray.app.domain.ColorLookSettings
import org.prooftray.app.domain.PhotoEditTab

@Composable
internal fun EditSessionBar(state: LibraryUiState, actions: LibraryActions, canEdit: Boolean, onEdit: (PhotoEditTab) -> Unit) {
    if (state.look.isIdentity && state.frame == null) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.look.isIdentity) EditSessionItem("调色 · ${state.look.name}", canEdit, { onEdit(PhotoEditTab.COLOR) }) {
            actions.onSetLook(ColorLookSettings())
        }
        state.frame?.let { frame ->
            EditSessionItem("边框 · ${frame.name}", canEdit, { onEdit(PhotoEditTab.FRAME) }) { actions.onSetFrame(null) }
        }
    }
}

@Composable
private fun EditSessionItem(label: String, enabled: Boolean, onClick: () -> Unit, onClear: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.widthIn(max = 180.dp)) {
                Text(label, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) { AppIcon(AppGlyph.CLOSE, "清空$label") }
        }
    }
}
