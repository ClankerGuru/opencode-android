package ai.opencode.feature.voice

import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Placeholder for the call's view until the UI wave ports `VoiceView.swift`. See [VoiceFeature]. */
@Composable
fun VoiceView(store: StoreOf<VoiceFeature.State, VoiceFeature.Action>, modifier: Modifier = Modifier) {
    // The call bar's appearance starts the call once (iOS `.startIfNeeded`).
    LaunchedEffect(store) { store.send(VoiceFeature.Action.StartIfNeeded) }
    val status by store.observe { it.statusText }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(status, style = MaterialTheme.typography.titleMedium)
    }
}
