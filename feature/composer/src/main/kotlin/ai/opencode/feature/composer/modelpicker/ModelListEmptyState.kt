@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.ProviderIcon as CoreProviderIcon
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * iOS `ModelListEmptyState`: the search's empty state, else **Couldn’t Load Models** with the
 * error and **Try Again** (when [includesLoadError]), else **No Models**.
 */
@Composable
fun ModelListEmptyState(
    state: ModelPickerState,
    includesLoadError: Boolean = true,
    onTryAgain: () -> Unit,
) {
    val error = state.modelsLoadError
    when {
        state.isFilteringModels -> ContentUnavailable(
            symbol = SFSymbol.Magnifyingglass,
            title = "No Results for “${state.modelFilter}”",
            description = "Check the spelling or try a new search.",
        )
        includesLoadError && error != null -> ContentUnavailable(
            symbol = SFSymbol.ExclamationmarkTriangle,
            title = "Couldn’t Load Models",
            description = error,
            action = { OpenCodePrimaryButton(text = "Try Again", onClick = onTryAgain) },
        )
        else -> ContentUnavailable(
            symbol = SFSymbol.Cpu,
            title = "No Models",
            description = "Connect a provider in OpenCode on your computer, then try again.",
        )
    }
}

/** iOS `ContentUnavailableView`: a large glyph, a title and a description, centred. */
@Composable
internal fun ContentUnavailable(
    symbol: SFSymbol,
    title: String,
    description: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OpenCodeIcon(symbol, null, size = 44.dp, tint = OpenCodeTheme.colors.secondaryText)
        Spacer(Modifier.size(12.dp))
        Text(title, style = OpenCodeTextStyles.title3, fontWeight = FontWeight.Bold, color = OpenCodeTheme.colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.size(6.dp))
        Text(description, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.size(16.dp))
            action()
        }
    }
}
