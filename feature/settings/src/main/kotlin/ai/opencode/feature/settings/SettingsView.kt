package ai.opencode.feature.settings

import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Phase 0 placeholder for iOS `SettingsView` (presented by Home as a sheet). */
@Composable
fun SettingsView(store: StoreOf<SettingsFeature.State, SettingsFeature.Action>, modifier: Modifier = Modifier) {
    LaunchedEffect(store) { store.send(SettingsFeature.Action.Task) }
    val serverName by store.observe { it.serverName }
    Column(modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        ListItem(headlineContent = { Text(serverName) }, supportingContent = { Text("Connected") })
        TextButton(onClick = { store.send(SettingsFeature.Action.ServersTapped) }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Servers")
        }
    }
}
