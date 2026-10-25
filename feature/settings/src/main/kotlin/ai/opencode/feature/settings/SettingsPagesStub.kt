package ai.opencode.feature.settings

import ai.opencode.core.experiments.ExperimentalFeature
import androidx.compose.runtime.Composable

// STUBS for the coordinator-owned pages, replaced one by one.
@Composable fun VoiceSettingsView(store: SettingsStore) = SettingsPageScaffold("Voice", store) {}
@Composable fun CallVoicePickerView(store: SettingsStore) = SettingsPageScaffold("Voice", store) {}
@Composable fun CallHistoryView(store: SettingsStore) = SettingsPageScaffold("Call History", store) {}
