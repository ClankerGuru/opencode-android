package ai.opencode.feature.composer

import ai.opencode.core.store.StoreOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Phase 0 placeholder for this area's root composable. See [ComposerFeature]. */
@Composable
fun ComposerView(store: StoreOf<ComposerFeature.State, ComposerFeature.Action>, modifier: Modifier = Modifier) {
    LaunchedEffect(store) { store.send(ComposerFeature.Action.Task) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("ComposerFeature", style = MaterialTheme.typography.titleMedium)
    }
}
