@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.ProviderIcon as CoreProviderIcon
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.composer.R
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The search's empty state, else **Couldn’t Load Models** with the error and **Try Again** (when
 * [includesLoadError]), else **No Models**.
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
            title = stringResource(R.string.composer_model_no_results_title, state.modelFilter),
            description = stringResource(R.string.composer_model_no_results_message),
        )
        includesLoadError && error != null -> ContentUnavailable(
            symbol = SFSymbol.ExclamationmarkTriangle,
            title = stringResource(R.string.composer_model_load_failed_title),
            description = error,
            action = { OpenCodePrimaryButton(text = stringResource(CoreR.string.common_try_again), onClick = onTryAgain) },
        )
        else -> ContentUnavailable(
            symbol = SFSymbol.Cpu,
            title = stringResource(R.string.composer_model_empty_title),
            description = stringResource(R.string.composer_model_empty_message),
        )
    }
}

/** A large glyph, a title and a description, centred. */
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
