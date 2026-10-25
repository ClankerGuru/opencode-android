package ai.opencode.devtools

import ai.opencode.core.log.AppLog
import ai.opencode.core.store.StoreOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.Instant

/**
 * Phase 0 developer tools: the in-process `AppLog` ring buffer (privacy-safe by construction).
 * Grow this with Android-native tools (design-system gallery, fixture shortcuts, perf overlays).
 */
@Composable
fun DevToolsView(store: StoreOf<DevToolsFeature.State, DevToolsFeature.Action>, modifier: Modifier = Modifier) {
    LaunchedEffect(store) { store.send(DevToolsFeature.Action.Task) }
    val lines = remember { AppLog.recentLines(Instant.EPOCH).asReversed() }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(lines) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
