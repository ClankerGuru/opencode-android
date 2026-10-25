package ai.opencode.feature.session

import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.feature.composer.ComposerView
import ai.opencode.feature.transcript.TranscriptView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 0 placeholder for iOS `SessionView`: header, transcript and composer.
 * [navigationIcon] is the drawer/back affordance supplied by Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionView(
    store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
) {
    LaunchedEffect(store) { store.send(SessionFeature.Action.Task) }
    val title by store.observe { it.title }
    val transcript = remember(store) { store.scope({ it.transcript }, SessionFeature.Action::Transcript) }
    val composer = remember(store) { store.scope({ it.composer }, SessionFeature.Action::Composer) }
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = navigationIcon) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().navigationBarsPadding()) {
            TranscriptView(transcript, Modifier.weight(1f).fillMaxWidth())
            ComposerView(composer, Modifier.fillMaxWidth().height(72.dp))
        }
    }
}
