package ai.opencode.feature.composer

import ai.opencode.core.ui.OpenCodeIcon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Android divergence: the + menu is a Material modal bottom sheet of the menu's rows, where iOS
 * morphs a panel out of the + button (`AttachmentMenuOverlay`). The phase stays the menu's
 * own ([AttachmentMenuPhase]); the sheet animates in when [isOpen] turns on and slides away when
 * it turns off, however the menu closed. A swipe down, a scrim tap or Back call [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentMenuSheet(
    isOpen: Boolean,
    items: List<AttachmentMenuItem>,
    onChoose: (AttachmentMenuItem) -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(isOpen) {
        if (isOpen) {
            shown = true
        } else if (shown) {
            sheetState.hide()
            shown = false
        }
    }
    if (!shown) return
    OpenCodeBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("attachment-menu-sheet"),
    ) {
        for (item in items) {
            ListItem(
                headlineContent = { Text(item.title) },
                leadingContent = { OpenCodeIcon(item.systemImage, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(role = Role.Button) {
                    if (item == AttachmentMenuItem.Paste) onPaste() else onChoose(item)
                },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
