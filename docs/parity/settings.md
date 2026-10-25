# Parity: settings

Module: `:feature:settings`. iOS folders: `OpenCode/Settings/`. 38 files, 10,562 lines.

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`. Fill in the Kotlin file(s) as you port; see [README.md](README.md).

| iOS file | Lines | Kotlin file(s) | Status | Notes |
| --- | ---: | --- | --- | --- |
| `OpenCode/Settings/AppearanceSettingsView.swift` | 517 | | todo | |
| `OpenCode/Settings/ComposerSettingsView.swift` | 24 | | todo | |
| `OpenCode/Settings/ConnectionCard.swift` | 511 | | todo | |
| `OpenCode/Settings/ConnectionHealth.swift` | 383 | | todo | |
| `OpenCode/Settings/ConnectionMonitor.swift` | 439 | | todo | |
| `OpenCode/Settings/ConnectionScope.swift` | 305 | | todo | |
| `OpenCode/Settings/ConnectionScopeShaders.metal` | 66 | | todo | |
| `OpenCode/Settings/ExperimentalFeatures.swift` | 333 | `core/.../experiments/ExperimentalFeatures.kt` | done | moved down to `:core` (every feature reads it). `ExperimentalFeature.key` = iOS `.experimental(f)`, `ModelPickerStyle.key`, `ExperimentalFeatures` dependency. `OPENCODE_LAB_EXPERIMENTS` lab overrides n/a (no launch environment on Android) |
| `OpenCode/Settings/ExperimentalSettingsView.swift` | 211 | | todo | |
| `OpenCode/Settings/ModelVisibilitySettings.swift` | 273 | | todo | |
| `OpenCode/Settings/NotificationSettingsView.swift` | 107 | | todo | |
| `OpenCode/Settings/Onboarding/OnboardingCamera.swift` | 140 | | todo | |
| `OpenCode/Settings/Onboarding/OnboardingPlaygroundControls.swift` | 119 | | todo | |
| `OpenCode/Settings/Onboarding/OnboardingPlaygroundFeature.swift` | 372 | | todo | |
| `OpenCode/Settings/Onboarding/OnboardingPlaygroundView.swift` | 601 | | todo | |
| `OpenCode/Settings/Onboarding/SignalField.swift` | 357 | | todo | |
| `OpenCode/Settings/Onboarding/SignalFieldShaders.metal` | 101 | | todo | |
| `OpenCode/Settings/PairingScanner.swift` | 259 | | todo | |
| `OpenCode/Settings/ServerBrand.swift` | 218 | | todo | |
| `OpenCode/Settings/ServerConnectView.swift` | 204 | | todo | |
| `OpenCode/Settings/ServerDetails.swift` | 303 | | todo | |
| `OpenCode/Settings/ServerDetailsTrays.swift` | 322 | | todo | |
| `OpenCode/Settings/ServerDetailsView.swift` | 368 | | todo | |
| `OpenCode/Settings/ServerPulseScope.swift` | 167 | | todo | |
| `OpenCode/Settings/ServerQuietStyle.swift` | 272 | | todo | |
| `OpenCode/Settings/ServerTitleFeature.swift` | 257 | | todo | |
| `OpenCode/Settings/ServerTitleView.swift` | 323 | | todo | |
| `OpenCode/Settings/SettingsPresentation.swift` | 333 | | todo | |
| `OpenCode/Settings/SettingsRows.swift` | 141 | | todo | |
| `OpenCode/Settings/SettingsServers.swift` | 137 | | todo | |
| `OpenCode/Settings/SettingsView.swift` | 1153 | | todo | |
| `OpenCode/Settings/VoiceKeySource.swift` | 53 | | todo | |
| `OpenCode/Settings/VoiceServerPicker.swift` | 109 | | todo | |
| `OpenCode/Settings/WhatsNew/WhatsNewActions.swift` | 84 | | todo | |
| `OpenCode/Settings/WhatsNew/WhatsNewEntries.swift` | 150 | | todo | |
| `OpenCode/Settings/WhatsNew/WhatsNewFeature.swift` | 166 | | todo | |
| `OpenCode/Settings/WhatsNew/WhatsNewList.swift` | 131 | | todo | |
| `OpenCode/Settings/WhatsNew/WhatsNewView.swift` | 553 | | todo | |
