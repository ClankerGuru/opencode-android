# Parity: settings

Module: `:feature:settings`. iOS folders: `OpenCode/Settings/`. 38 files, 10,562 lines.

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`. Fill in the Kotlin file(s) as you port; see [README.md](README.md).

| iOS file | Lines | Kotlin file(s) | Status | Notes |
| --- | ---: | --- | --- | --- |
| `OpenCode/Settings/AppearanceSettingsView.swift` | 517 | `feature/settings/.../AppearanceSettingsView.kt`, `feature/drawer/.../AppearanceTranscriptPreview.kt` | done | Mode segmented control, Theme row + swatch (stacked at accessibility sizes), Themes list, swatch tile, live re-theme via `OpenCodeAppTheme`; haptics in `HomeSettingsFeature`. The Themes stage is pinned and draws real `TranscriptMessageRow`s (same preview entries, server, tuning, readable column), provided by Home through `LocalAppearanceTranscriptPreview` because `:feature:transcript` is a sibling of Settings; type capped at xLarge, inert, one TalkBack element; 0.22 s ease-in-out theme cross-fade. `ThemesSoftEdgeBackdrop`: canvas through the fade origin, a progressive blur over the 14 + 22 band (an AGSL `RenderEffect` on the list's own layer: radius 12 over the inset, smoothstep falloff across the feather), smoothstep gradient feather; opaque under high contrast. No 6 pt pull under the bar (Android's bar is opaque) |
| `OpenCode/Settings/ComposerSettingsView.swift` | 24 | `feature/settings/.../ComposerSettingsView.kt` | removed in 302b95e7: done | footer “⌘↩︎ always sends.” → “Ctrl+Enter always sends on a hardware keyboard.”; + `DefaultAgentSection` from `Composer/AgentPicker.swift` (keys `default-agent`, `primary-agents.json` moved to `:core`/`:server`) |
| `OpenCode/Settings/ConnectionCard.swift` | 511 | `feature/settings/.../connection/ConnectionCard.kt` | done | card, `ConnectionStatusPresentation`, status label (word swaps on `.smooth(duration: 0.42)`), Retry reservation (inline action: 48 dp target, dims to 55 % pressed as `RetryStyle`), `ServerPage`; `credentialsRejectedStatus = 401`; one monitor per Settings store shared by card and page |
| `OpenCode/Settings/ConnectionHealth.swift` | 404 | `feature/settings/.../connection/ConnectionHealth.kt` | sync: done | incl. `BeatShape`, `ConnectionTrace`, `ServerCardName` |
| `OpenCode/Settings/ConnectionMonitor.swift` | 439 | `feature/settings/.../connection/ConnectionMonitor.kt` | sync: done | single-shot `GET /api/info` probe (4 s, retry `.once`, `ServerContact` noted), machine names under `connection-card-machine-names`. `ConnectionLabScenario` n/a: no lab launch arguments on Android; fixture servers drive the states |
| `OpenCode/Settings/ConnectionScope.swift` | 305 | `feature/settings/.../connection/ConnectionScope.kt` | done | Canvas + AGSL at 30 fps, paused when not resumed; reduce motion draws a still trace |
| `OpenCode/Settings/ConnectionScopeShaders.metal` | 66 | `feature/settings/.../connection/ConnectionScopeShaders.kt` | done | AGSL `RuntimeShader` |
| `OpenCode/Settings/Experimental/ExperimentalFeature.swift` | 74 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/AgentSelectionExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/BackgroundCallIntentExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/CallTrayExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/ComposerAutocompleteExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/HideModelsExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/ImageLinkPreviewsExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/InAppBrowserExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/InboxSidebarExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/LivingOrbExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/NewMessagesDividerExperiment.swift` | 12 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/PlusMenuSheetExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/ProjectIconsExperiment.swift` | 11 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/ServerDetailsExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/ServerFlowExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/ServerVoiceExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/SessionLiveActivityExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/SoundEffectsExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/VoiceSessionDelegatorExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/Experimental/Features/WelcomePreviewExperiment.swift` | 10 |  | todo |  |
| `OpenCode/Settings/ExperimentalFeatures.swift` | 244 | `core/.../sharing/ExperimentalFeature.kt` (enum, `experimental(f)`, `ExperimentalFeatures`; same file as the voice area's), `core/.../experiments/ExperimentalFeatures.kt` (group, `key`, `ExperimentalGroup`, `ExperimentalCallStyle`, `ModelPickerStyle`) | sync: done | moved down to `:core` (every feature reads it). `OPENCODE_LAB_EXPERIMENTS` lab overrides n/a (no launch environment on Android) |
| `OpenCode/Settings/ExperimentalSettingsView.swift` | 209 | `feature/settings/.../ExperimentalSettingsView.kt` | sync: done | groups with on-counts, Model Picker, Call Style and (with Living Orb on) Orb Style menus (`VoiceSharedKeys.livingOrbStyle`, `HomeSettingsFeature.LivingOrbStyleChanged`), focus scroll (row centred, as `scrollTo(anchor: .center)`) + 1.2 s tint, then a 0.6 s ease-out fade; also hosts the Developer page (`:devtools` `DevToolsView`, the Android stand-in for iOS `DiagnosticsView`, with Playgrounds under Tuning via `onOpenPlaygrounds`) and `PlaygroundsSettingsView` |
| `OpenCode/Settings/ModelVisibilitySettings.swift` | 273 | `feature/settings/.../ModelVisibilitySettings.kt` | done | `ModelVisibility` type in `:server`; provider search ranks with `ModelSearch` (moved to `:server`); provider rows draw `:core`'s `ProviderIcon` (20 dp, secondary); iOS accessibility identifiers → `testTag`s. `.screenName` (Scrawl tag) has no Android counterpart |
| `OpenCode/Settings/NotificationSettingsView.swift` | 101 | `feature/settings/.../NotificationSettingsView.kt` | sync: done | permission (`:feature:system`'s `NotificationPermission`) comes from Home (`NotificationsChanged`); enabling/opening settings is `SettingsDelegate.EnableNotificationsTapped`; notify-plugin section hidden as on iOS (`showsNotifyPlugin = false`); “Open iOS Settings”/“in iOS Settings” → “Android Settings” |
| `OpenCode/Settings/Onboarding/OnboardingCamera.swift` | 140 | `feature/settings/.../onboarding/OnboardingCamera.kt` | done | live camera via `servers/QRCameraPreview` (`once = false`) |
| `OpenCode/Settings/Onboarding/OnboardingPlaygroundControls.swift` | 119 | `feature/settings/.../onboarding/OnboardingPlaygroundControls.kt` | done |  |
| `OpenCode/Settings/Onboarding/OnboardingPlaygroundFeature.swift` | 372 | `feature/settings/.../onboarding/OnboardingPlaygroundFeature.kt` | done | + `OnboardingCameraClient` |
| `OpenCode/Settings/Onboarding/OnboardingPlaygroundView.swift` | 602 | `feature/settings/.../onboarding/OnboardingPlaygroundView.kt` | sync: done | “OpenCode on iPhone” → “OpenCode on Android”; `OnboardingSkeleton` splits the free height between the blocks above and below the centre as iOS's two `maxHeight: .infinity` frames, gives a block that needs more its height and scrolls when nothing fits (no clipped subtitle at large text); the shared aperture follows the active page's centre slot and the field waits for its first bounds |
| `OpenCode/Settings/Onboarding/SignalField.swift` | 357 | `feature/settings/.../onboarding/SignalField.kt` | done | Tunables baked to iOS defaults; no slow-motion time scale |
| `OpenCode/Settings/Onboarding/SignalFieldShaders.metal` | 101 | `feature/settings/.../onboarding/SignalFieldShaders.kt` | done | AGSL `RuntimeShader` |
| `OpenCode/Settings/Onboarding/WelcomeBrowser.swift` | 333 |  | todo |  |
| `OpenCode/Settings/Onboarding/WelcomeCarriers.swift` | 269 |  | todo |  |
| `OpenCode/Settings/Onboarding/WelcomeDesktop.swift` | 396 |  | todo |  |
| `OpenCode/Settings/Onboarding/WelcomeFeature.swift` | 99 |  | todo |  |
| `OpenCode/Settings/Onboarding/WelcomeMockParts.swift` | 277 |  | todo |  |
| `OpenCode/Settings/Onboarding/WelcomePhone.swift` | 320 |  | todo |  |
| `OpenCode/Settings/Onboarding/WelcomeScene.swift` | 319 |  | todo |  |
| `OpenCode/Settings/Onboarding/WelcomeStory.swift` | 328 |  | todo |  |
| `OpenCode/Settings/Onboarding/WelcomeView.swift` | 303 |  | todo |  |
| `OpenCode/Settings/PairingScanner.swift` | 259 | `feature/settings/.../servers/PairingScanner.kt`, `servers/QRCameraPreview.kt`, `servers/ServerCameraClient.kt` | done | CameraX + ZXing; no camera → paste; denied → Open Settings + paste; raw text handed to `:app` (`OpenCodePairingLink.decode`) |
| `OpenCode/Settings/ServerBrand.swift` | 219 | `feature/settings/.../serverdetails/ServerBrand.kt` | sync: done | brand table, fills/glyph colours, iOS lookup order (known MCP match or `provider-<integrationID>`, monogram tile only when the mark isn't bundled), monogram hash and status ring ported; marks are `:core`'s bundled SVGs (`BrandAssets`, `TemplateAssetImage`) tinted with the glyph colour; `muted` desaturates the whole tile (fill, glyph) like `.saturation(0)` |
| `OpenCode/Settings/ServerConnectView.swift` | 204 | `feature/settings/.../servers/ServerConnectView.kt` | removed in c75d4a1e: done | Scan Code / Enter Address rows swap with `animateItem` on `.smooth(duration: 0.3)`; `ServerTitleTunables` live here (baked defaults) |
| `OpenCode/Settings/ServerDetails.swift` | 303 | `feature/settings/.../serverdetails/ServerDetails.kt` | done | + `OpenURLClient` (opens sign-in pages in the browser); `Disappeared` cancels read, polling and sign-in. Settings' optional navigation scopes cancel reads on removal/server replacement; only server-side OAuth cleanup runs in the parent, replacing global child-ID cancellation |
| `OpenCode/Settings/ServerDetailsTrays.swift` | 322 | `feature/settings/.../serverdetails/ServerDetailsTrays.kt` | done | “in Safari” → “in your browser”; the MCP tray's server page pushes and pops inside the sheet (slide on the 0.42 s push spring, cross-fade under reduce motion); pages are opaque and paint to the sheet's bottom, and the sheet holds its height through the slide (grows at once, shrinks after 0.42 s) as iOS's tray stays at its detent; rows animate on iOS's 0.3 s spring |
| `OpenCode/Settings/ServerDetailsView.swift` | 370 | `feature/settings/.../serverdetails/ServerDetailsView.kt` | sync: done | sheets are Material bottom sheets (no zoom-out-of-tile transition: Material sheets rise from the bottom); banners and tiles move on iOS's 0.4 s spring, the MCP error on its 0.3 s one |
| `OpenCode/Settings/ServerPulseScope.swift` | 167 | `feature/settings/.../servers/ServerPulseScope.kt` | removed in c75d4a1e: done |  |
| `OpenCode/Settings/ServerQuietStyle.swift` | 272 | `feature/settings/.../servers/ServerQuietStyle.kt` | removed in c75d4a1e: done | `ServerWireframeMark` n/a (unused on iOS); `ServerCommandRow` shrinks to 0.8× (`TextAutoSize`, iOS `minimumScaleFactor`), selectable, `.smooth(duration: 0.25)` Copy/Copied |
| `OpenCode/Settings/ServerTitleFeature.swift` | 257 | `feature/settings/.../servers/ServerTitleFeature.kt` | removed in c75d4a1e: done | incl. `ServerCameraClient`; `:app`/drawer present `ServerTitleView` and handle its delegates |
| `OpenCode/Settings/ServerTitleView.swift` | 323 | `feature/settings/.../servers/ServerTitleView.kt` | removed in c75d4a1e: done |  |
| `OpenCode/Settings/SessionsSettingsView.swift` | 39 |  | todo |  |
| `OpenCode/Settings/SettingsPresentation.swift` | 397 | `feature/settings/.../SettingsPresentation.kt` | sync: done | overlay push with 30 % parallax, 12 % dim, edge shadow, touch shield; predictive back at the root follows the finger (its handler registers with Settings’ content each time it mounts, so it outranks the handlers Home registered beneath; cancel springs back, commit closes from the finger’s position); reduce motion cross-fades (0.3 s ease-in-out); the push spring follows Developer Mode slow motion; What’s New → Settings root cross-fades (`SettingsView`). `SettingsNavigator` → `SettingsFeature.path` + `NavDisplay`. Drawer wraps Home with it |
| `OpenCode/Settings/SettingsRows.swift` | 141 | `feature/settings/.../SettingsRows.kt` | done | + inset-grouped section chrome (`settingsSection`, toggles, buttons, value rows); `SettingsMenuRow` stacks its value under the title at accessibility sizes (`isAccessibilitySize`, fontScale ≥ 28/17) |
| `OpenCode/Settings/SettingsServers.swift` | 137 | `feature/settings/.../connection/SettingsServers.kt` | done | local `switchableServers` (drawer is above Settings); long press opens the row menu |
| `OpenCode/Settings/SettingsView.swift` | 1172 | `feature/settings/.../SettingsView.kt`, `SettingsFeature.kt`, `AppVersion.kt`, `VoiceSettingsFeature.kt` (`VoiceProviderGroup`, `VoiceSettingsFeature`), `VoiceSettingsView.kt` (`VoiceSettingsView`/`AdvancedSettingsView`, `CallVoicePicker`, Call History), `ExperimentalSettingsView.kt` (`PlaygroundsSettingsView`) | sync: done | root page, stack, delegates to Home; `SettingsNavigator` → `SettingsFeature.path`. Voice page: dot scope (`DotScopeView`), Enable Voice, Server Voice rows, Model menu, key row + editor (`RealtimePreflightClient`, `VoiceKeyFingerprint`, 800 ms debounce), Voice picker with previews (`VoicePreviewClient`), Raise to Talk (`RaiseToEarClient.isSupported`), Call History (`VoiceCallLogList` → `VoiceCallReplayView` as `SettingsPage.CallReplay`; Developer Mode’s web links hidden, no `DeveloperMode.webURL` on Android). “This iPhone” → “This Phone”. At accessibility sizes the Model, key and key-editor rows stack as on iOS; the key editor opens with `.snappy(duration: 0.2)` (at once under reduce motion) |
| `OpenCode/Settings/SoundEffectsSettings.swift` | 34 |  | todo |  |
| `OpenCode/Settings/VoiceDelegatorSettings.swift` | 271 |  | todo |  |
| `OpenCode/Settings/VoiceKeyEditor.swift` | 89 |  | todo |  |
| `OpenCode/Settings/VoiceKeySource.swift` | 53 | `feature/voice/.../VoiceRoute.kt` (`VoiceKeySource`), `feature/settings/.../VoiceKeySource.kt` (`VoiceKeyStatus`) | done | `VoiceKeySource` moved down to `:feature:voice` (`VoiceRoute.keySource` returns it) |
| `OpenCode/Settings/VoiceProviderPage.swift` | 249 |  | todo |  |
| `OpenCode/Settings/VoiceServerPicker.swift` | 67 | `feature/settings/.../VoiceServerPicker.kt` | sync: done | state extensions (`connectedServer`, `broker`, `voiceServerOptions`, `voiceServerSelection`, `keySource`, `voiceServerValue`), `checkVoiceServers` (`ServerVoiceClient.models`), `ServerVoiceRows`; “This iPhone” → “This Phone” |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-06-01-voice-messages.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-07-01-attachment-menu-hold.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-08-01-voice-webrtc-weak-connection.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-08-02-mute-sessions.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-08-03-pair-from-anywhere.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-08-04-drawer-peek.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-08-05-dictation-keeps-going.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-08-06-siri-shortcuts-spotlight.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-09-01-working-orb.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-09-02-shell-glyph.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-09-03-math-and-diagrams.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-09-04-always-notify.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-09-05-server-switcher.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-10-01-share-opened-files.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-10-02-call-voice.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-10-03-drawer-one-edge.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-11-01-paste-as-a-card.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-11-02-ride-out-drops.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-11-03-new-session-recent-projects.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-11-04-stop-runs-your-steer.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-01-browser-push-in.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-02-answer-from-the-bar.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-03-new-messages-divider.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-04-model-list-and-level-meter.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-05-run-error-line.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-06-transcript-keyboard-space.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-07-share-sheet-sends.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-08-app-icon-badge.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-09-drawer-keeps-keyboard.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-10-whats-new-page.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-11-catch-up-returns-to-drawer.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-12-transcript-day-stamps.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-12-13-rich-link-previews.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-13-01-rolling-numbers.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-13-02-grouped-steering.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-13-03-inline-tool-rows.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-13-04-mac-motion-playgrounds.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-13-05-catch-up-flicks.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-14-01-settings-grouped-by-purpose.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-14-02-send-ats-recovery.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-14-03-keyboard-menus-and-header.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-14-04-open-links-in-apps.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-14-05-session-list-quiet-hierarchy.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-14-06-queued-messages-fitted-sheet.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-14-07-control-center-sessions.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-15-01-settings-keyboard-down.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-15-02-session-list-sidebar-grey.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-15-03-voice-varied-acknowledgements.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-15-04-smoother-streaming.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-15-05-model-list-sheet.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-15-06-queued-pill-whole-title.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-16-01-plus-menu-photo-sheet.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-16-02-composer-large-text-targets.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-16-03-project-pick-haptic.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-16-04-fork-opens-copy.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-16-05-transcript-row-targets.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-16-06-image-previews.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-16-07-connection-error-details.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-16-08-outage-reconnect-delay.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-17-01-every-running-subagent.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-17-02-session-view-refined-standard.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-17-03-bottom-follow-on-load.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-17-04-drafts-stay-put.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-18-01-themes.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-18-02-return-key-sends.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-18-03-quote-replies.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-18-04-launch-bloom.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-18-05-drag-to-pin.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-19-01-call-tray-colour-blend.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-19-02-optical-insets.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-19-03-smart-dictation-audio.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-19-04-interrupted-hand.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-19-05-autocomplete-fast-scroll.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-19-06-experimental-groups.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-19-07-model-picker-standard-sheet.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-19-08-problem-reports-reach-session.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-19-09-team-scrawl-feedback.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-19-10-scrawl-faster-start.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-01-call-tray-docks-in-drawer.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-02-markdown-streams-smoothly.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-03-server-voice-experiment.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-04-return-newline-default.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-05-drawer-session-alignment.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-06-call-tray-switch-continuity.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-07-voice-delegation-visible-on-admission.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-08-inline-image-links.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-09-smooth-link-preview-arrival.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-10-subagent-capsule-press.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-11-advanced-settings-open.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-12-revert-to-here.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-13-drag-from-transcript.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-14-tuning-glass-tap-targets.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-15-new-session-project-continuity.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-16-simple-session-loading.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-17-voice-route-consistency.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-20-18-call-tray-edge-continuity.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-01-call-tray-keyboard-return.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-02-drawer-title-fade.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-03-call-sounds-from-the-tap.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-04-calm-wireframe-launch.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-05-request-dictation-last-line.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-06-first-run-pairing-fixes.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-07-welcome-pairing-copy.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-08-session-id-links.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-09-uikit-drawer-experiment.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-10-ipad-multiple-windows.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-11-plain-launch.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-12-breath-ping-call-sounds.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-21-13-smoother-session-list.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-22-01-whats-new-actions.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-22-02-project-icons-current-discovery.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-22-03-new-divider-off-page.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-22-04-catch-up-all-caught-up-row.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-23-01-session-mentions.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-23-02-gpt-live-own-key.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-23-03-pair-opens-drawer.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-23-04-voice-server-picker.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-24-01-living-orb-floats.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-24-02-new-messages-divider-on.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-24-03-composer-keyboard-morph.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-24-04-composer-photo-viewer.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-24-05-plus-menu-photo-picker.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-24-06-project-icons-lit.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-24-07-session-live-activity.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-24-08-sign-in-failed-edit-server.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-25-01-voice-app-screenshot.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-25-02-voice-background-results.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-25-03-sound-effects-experiment.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-26-01-settings-sessions-page.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-26-02-project-icons-default.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-26-03-plus-menu-over-keyboard.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-26-04-living-orb-connect-motion.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-26-05-living-orb-presets.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-26-06-send-after-a-pause.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-27-01-steadier-round-trip.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-27-02-drawer-drag-follows-finger.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-27-03-inbox-sidebar-experiment.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-28-01-first-pairing-ready-session.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-28-01-voice-provider-page.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-28-02-welcome-preview.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-29-01-composer-text-selection.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-30-01-voice-waits-for-you.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-30-02-wisp-call-sounds.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-30-03-wisp-orb.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-31-01-encrypted-notification-previews.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-31-02-move-session-folder-gone.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/Entries/2026-10-31-03-notification-plugin-setup.swift` | 3 |  | todo |  |
| `OpenCode/Settings/WhatsNew/WhatsNewActions.swift` | 84 | `feature/settings/.../whatsnew/WhatsNewActions.kt` | sync: done | voice availability is `:feature:voice`'s `VoiceRoutePreferences.isAvailable(server)` |
| `OpenCode/Settings/WhatsNew/WhatsNewEntries.swift` | 154 | `feature/settings/.../whatsnew/WhatsNewEntries.kt` | sync: done |  |
| `OpenCode/Settings/WhatsNew/WhatsNewFeature.swift` | 166 | `feature/settings/.../whatsnew/WhatsNewFeature.kt` | done | `settingsPage` folds into Settings' stack; `WhatsNewAcrossHomes` is ported in `:app` (`AppFeatureWhatsNew.kt`, applied as `keepingWhatsNewAcrossHomes()`; see app.md); lab launch state n/a (no XCUITest lab env on Android) |
| `OpenCode/Settings/WhatsNew/WhatsNewList.swift` | 131 | `feature/settings/.../whatsnew/WhatsNewList.kt` | removed in 3f425e0c: done | 118 of 119 entries; `uikit-drawer-experiment` dropped (iOS UIKit drawer A/B); `ipad-multiple-windows` → “Multiple Windows on Tablets” (iPad → tablet). iOS-only copy reworded for Android: `control-center-sessions` (Quick Settings tiles), `siri-shortcuts-spotlight` (App Actions and app shortcuts; no Spotlight detail), `return-newline-default` (Ctrl+Enter), iPhone/iPad → phone/tablets in `voice-server-picker`, `drawer-session-alignment`, `smart-dictation-audio`, `server-switcher`, `pair-from-anywhere`; every other entry verbatim. `session-title-first-prompt` is Android-first (the title fallback; iOS has it in its draft PR `feat/session-title-fallback`) |
| `OpenCode/Settings/WhatsNew/WhatsNewView.swift` | 553 | `feature/settings/.../whatsnew/WhatsNewView.kt` | done | the entry page's bar title fades in over 0.15 s ease-out once its own title scrolls under the bar (`SettingsPageScaffold(titleVisible)`); button hint is TalkBack's click label |

Also here: `HomeSettingsFeature.kt` (iOS `Drawer/HomeSettingsFeature.swift`, moved down; see drawer.md): every action but `developerModeChanged`/`motionWatchChanged`/`touchProbeChanged`/`voiceDemoModeChanged` (iOS sends them from `DiagnosticsView`; Android's `:devtools` `DevToolsFeature` owns Developer Mode and Demo Call Audio, Motion Watch and Touch Probe are iOS-only) and the Tuning mirrors of `plusMenuSheet`/`composerAutocomplete` (the composer reads the keys). Settings-owned Android extras: `SettingsPresentation.kt` is the overlay Home wraps itself in.

## Tests

iOS `OpenCodeTests` files that test `:feature:settings` code, ported into `feature/settings/src/test`
(JVM, `./gradlew :feature:settings:testDebugUnitTest`). Test class and method names match iOS;
parameterized iOS tests loop over their arguments inside one test. Shared support is
`SettingsTestSupport.kt` (a per-test in-memory `SharedStore` in place of iOS's process-wide
`@Shared` values, `LockIsolated`, a haptics recorder). Shared values aren't in Android's reducer
state (states read the keys live), so where iOS asserts `$shared.withLock` inside a step, the
port checks the key after the send. Tests whose store or subject belongs to another module are
left to that module's port ("skipped"); "dropped" tests can't exist on Android.

| iOS file | Kotlin file | Ported | Skipped (owner) | Dropped (reason) |
| --- | --- | ---: | --- | --- |
| `OnboardingPlaygroundFeatureTests.swift` | `onboarding/OnboardingPlaygroundFeatureTests.kt` | 10 | – | – (Close: Android's view dismisses, so the test checks no effect keeps running instead of counting `dismiss`) |
| `PlaygroundFixtureTests.swift` | – | 0 | – | all 7: `PlaygroundFixtureEngine` presets/replays/session host and `TuningFeature`'s playground selection are n/a on Android (devtools.md: Session Lab and `session-lab.py` checkpoints replace the in-app fixture playgrounds) |
| `ServerDetailsTests.swift` | `serverdetails/ServerDetailsTests.kt` | 11 | `decodesEveryMCPStatus`, `decodesCredentialAndEnvironmentAccounts`, `onlyMCPConnectIsRetriedWhenUnsent` (`:server` internal payloads / `mcpConnectionIdempotency`) | – |
| `WhatsNewTests.swift` (`WhatsNewTests`) | `whatsnew/WhatsNewTests.kt` | 14 (3 are the Settings halves of Home tests: `homePushes…`, `readingItInSettings…`, `openPushes…`, against `SettingsFeature`, where Android keeps the page) | `tryItTypesAPrompt…`, `closingSettingsWithoutWhatsNew…`, `closingWhatsNewDropsItsSettingsPage`, `tryItTypesThePrompt…OnThisServer…`, `tryItKeepsTheOpenDrafts…`, `tryItAgain…`, `tryItTwice…`, `tryItKeepsASavedDrafts…` (`:feature:drawer`) | – |
| `WhatsNewTests.swift` (`WhatsNewTryItTests`) | `whatsnew/WhatsNewTests.kt` (`WhatsNewTryItTests`) | 3 (Settings halves of `aNewSessionWhileOffline…`, `aCallBeforeVoiceIsSetUp…`; `settingsAskedForOverWhatsNew…`) | 4 `modelList…` (`:feature:drawer`), `whatsNewSurvivesAHomeReplacement` (`:app`, ported there) | – |
| `WhatsNewTests.swift` (`WhatsNewDotSymbolTests`) | `whatsnew/WhatsNewTests.kt` (`WhatsNewDotSymbolTests`, Robolectric) | 1 (`DotField` over the `plus` Material Symbol drawable, native graphics with the module's resources; `DotField` is `internal`, was `private`) | – | – |
| `WhatsNewAddedIDs.swift` | `whatsnew/WhatsNewAddedIDs.kt` | data | – | `uikit-drawer-experiment` left out (its entry isn't on Android's list) |
| `ModelVisibilityTests.swift` | `ModelVisibilityTests.kt` | 4 (Settings › Models, against `ModelVisibilityFeature`) | 6 Rules (`:server`, ported there), 5 Pickers (`:feature:session`) | – |
| `ServerQuietStyleTests.swift` | `servers/ServerQuietStyleTests.kt` | 2 | – | `markIntroWindsBack…`, `markFrontOnIs…`: `ServerMarkGeometry` (the wireframe mark, unused on iOS) isn't ported |
| `ConnectionHealthTests.swift` | `connection/ConnectionHealthTests.kt` | 28 | `routes`, `routesClassifyParsedAddressesNotSpellings` (`ConnectionRoute`, `reachabilityRank`, `isLoopback` live in `:server`) | – (the probe test answers through an OkHttp interceptor; its `NetworkRetry.policy == .once` check can't be observed on OkHttp's dispatcher thread) |
| `ThemePaletteTests.swift` | `ThemePaletteTests.kt` | 1 (`appearanceSettingsActions…` through `HomeSettingsFeature`, minus its `TranscriptTunables`/`MarkdownStyle` half, `:feature:transcript`) | 5 + legacy (`:core`, see core.md) | `appearanceTunablesRegisterSoftEdgeControls` (tuning store not ported; the soft edge's 22/12 are baked) |
| `CallVoiceTests.swift` | `CallVoiceTests.kt` | 3 (`CallVoicePicker.rowValue` half of `anUnsupportedChoice…`, `keyRowSwitches…`, `previewRequiresAKey…`) | rest (`:feature:voice`) | – |
| `VoiceServerPickerTests.swift` | `VoiceServerPickerTests.kt` | 7 (4 `VoiceSettingsFeature`, `homeSavesTheChoice…`, Settings halves of `everyEntryPointRoutes…`, `thisIPhoneUsesThePhonesOwnKey`) | rest (`:feature:voice`); `SessionFeature.State` checks (`:feature:session`) | – |
| `VoiceRouteTests.swift` | `VoiceRouteTests.kt` | 2 (Settings/`VoiceRoutePreferences` halves of `inactiveGemini…`, `grokSuppresses…`) | rest (`:feature:voice`); `SessionFeature.State` checks (`:feature:session`) | – |
| `VoiceRoutingBaselineTests.swift` | `VoiceRoutingBaselineTests.kt` | 1 | – | – |
| `HomeChildFeatureTests.swift` | `HomeChildFeatureTests.kt` | 2 (`HomeSettingsFeature`) | `HomeNotificationsFeature` and other Home children (`:feature:drawer`) | – |
| `ServerSwitchingTests.swift` | `servers/ServerSwitchingTests.kt` | 5 (`ServerTitleFeature` alone) | `AppFeature` tests (`:app`), header tests (`:server`), ported there | – |
| `ServerTitleRecoveryTests.swift` | `servers/ServerTitleRecoveryTests.kt` | 2 (`ServerTitleFeature` alone) | `AppFeature` tests (`:app`, ported there) | – |
| `AppFeatureTests.swift` | `AppFeatureTests.kt` | 1 (`appVersionPrefersOpenCodeVersion`: the label; Android reads the package version, no Info.plist key) | the rest (`:app`, `:server`) | – |

Production bugs the ported tests found: none (every ported test passed against the existing port).

Total: 98 settings tests, all passing. Robolectric (test-only, `isIncludeAndroidResources`) runs only `WhatsNewDotSymbolTests`.
