package ai.opencode.feature.settings

import ai.opencode.core.experiments.ExperimentalFeature
import androidx.compose.runtime.Composable

// STUBS for the coordinator-owned pages, replaced one by one.
@Composable fun AppearanceSettingsView(store: SettingsStore) = SettingsPageScaffold("Appearance", store) {}
@Composable fun ThemesSettingsView(store: SettingsStore) = SettingsPageScaffold("Themes", store) {}
@Composable fun ComposerSettingsView(store: SettingsStore) = SettingsPageScaffold("Composer", store) {}
@Composable fun ModelVisibilitySettingsView(store: SettingsStore) = SettingsPageScaffold("Models", store) {}
@Composable fun VoiceSettingsView(store: SettingsStore) = SettingsPageScaffold("Voice", store) {}
@Composable fun CallVoicePickerView(store: SettingsStore) = SettingsPageScaffold("Voice", store) {}
@Composable fun CallHistoryView(store: SettingsStore) = SettingsPageScaffold("Call History", store) {}
@Composable fun NotificationSettingsView(store: SettingsStore) = SettingsPageScaffold("Notifications", store) {}
@Composable fun ExperimentalSettingsView(store: SettingsStore, focus: ExperimentalFeature?) = SettingsPageScaffold("Advanced", store) {}
@Composable fun DeveloperSettingsView(store: SettingsStore) = SettingsPageScaffold("Developer", store) {}
@Composable fun PlaygroundsSettingsView(store: SettingsStore) = SettingsPageScaffold("Playgrounds", store) {}
