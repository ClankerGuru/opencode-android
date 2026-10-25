package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * Port of iOS `ShowAllModelsRow`: the foot of the Today and Sheet pickers while models are
 * hidden (Hide Unused Models, OC-510). Show All lists them until the picker closes.
 *
 * Draws nothing while [hiddenModelCount] is zero.
 */
@Composable
fun ShowAllModelsRow(
    hiddenModelCount: Int,
    showsAllModels: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hidden = hiddenModelCount
    if (hidden <= 0) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .openCodeTapTarget()
            .semantics { stateDescription = "$hidden hidden" }
            .testTag("model-show-all")
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (showsAllModels) "Show Fewer Models" else "Show All Models",
            style = OpenCodeTextStyles.body,
            color = OpenCodeTheme.colors.accent,
        )
        Spacer(Modifier.weight(1f).padding(start = 8.dp))
        Text("$hidden hidden", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
    }
}
