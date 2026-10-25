package ai.opencode.feature.settings.connection

import ai.opencode.feature.settings.SettingsPageScaffold
import ai.opencode.feature.settings.SettingsStore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** STUB: port of iOS `Settings/ConnectionCard.swift` `ConnectionCard` (being ported). */
@Composable
fun ConnectionCard(store: SettingsStore, modifier: Modifier = Modifier) {
    Text("Connection", modifier)
}

/** STUB: port of iOS `ConnectionCard.swift` `ServerPage` (being ported). */
@Composable
fun ServerPage(store: SettingsStore) {
    SettingsPageScaffold(title = "Server", store = store) {}
}
