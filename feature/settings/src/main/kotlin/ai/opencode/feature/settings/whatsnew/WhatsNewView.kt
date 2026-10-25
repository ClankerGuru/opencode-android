package ai.opencode.feature.settings.whatsnew

import ai.opencode.feature.settings.SettingsPageScaffold
import ai.opencode.feature.settings.SettingsStore
import androidx.compose.runtime.Composable

/** STUB: port of iOS `Settings/WhatsNew/WhatsNewView.swift` list page (being ported). */
@Composable
fun WhatsNewListPage(store: SettingsStore) {
    SettingsPageScaffold(title = "What’s New", store = store) {}
}

/** STUB: one entry's page. */
@Composable
fun WhatsNewDetailPage(store: SettingsStore, entryID: String) {
    SettingsPageScaffold(title = "", store = store) {}
}
