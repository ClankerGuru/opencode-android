# Parity: voice

Module: `:feature:voice`. iOS folders: `OpenCode/Voice/`. 68 files, 26,557 lines.

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`. Fill in the Kotlin file(s) as you port; see [README.md](README.md).

| iOS file | Lines | Kotlin file(s) | Status | Notes |
| --- | ---: | --- | --- | --- |
| `OpenCode/Core/AudioSessionOwner.swift` | 171 | `AudioSessionOwner.kt`, `VoiceAudioEngine.kt` | done | Moved to :feature:voice for composer/dictation reuse. Shared-holder activation/release, epoch-guarded retries and generation cancellation; live controller maps to Android focus/mode/communication device. |
| Android ongoing-call foreground service (Live Activity equivalent) | — | `VoiceCallService.kt`, `AndroidManifest.xml` | done | Microphone FGS, ongoing chronometer, Connecting…/Reconnecting…/Call ended, Mute/End commands guarded by call ID. Reducer starts/updates/stops via VoiceCallServiceClient and collects VoiceCallCommands; app owns runtime permission prompts. |
| `OpenCode/Voice/CallTray.swift` | 1109 | | todo | |
| `OpenCode/Voice/CallTrayBarLayout.swift` | 324 | | todo | |
| `OpenCode/Voice/CallTrayDock.swift` | 573 | | todo | |
| `OpenCode/Voice/CallTrayViews.swift` | 786 | | todo | |
| `OpenCode/Voice/CallVoice.swift` | 483 | `CallVoice.kt`, `VoiceHttp.kt` | done | Preview via `MediaPlayer` over the clip's bytes; cache in cacheDir/OpenCode/voice-previews. `VoiceLabDemo` preview key/demo fallbacks not ported (VoiceLabDemo is a lab demo). |
| `OpenCode/Voice/DotScopeShaders.metal` | 218 | | todo | |
| `OpenCode/Voice/DotScopeSimulation.swift` | 809 | `DotScopeSimulation.kt` | done | Fixed 240 Hz simulation, packed xyzw render field, grid, continuous scanner origins, scheduler and cue vocabulary. Frame-subdivision checks passed on JVM. |
| `OpenCode/Voice/DotScopeView.swift` | 610 | | todo | |
| `OpenCode/Voice/FloatingCall.swift` | 1020 | | todo | |
| `OpenCode/Voice/FloatingCallFace.swift` | 596 | | todo | |
| `OpenCode/Voice/FloatingOrbShaders.metal` | 270 | | todo | |
| `OpenCode/Voice/GeminiLiveClient.swift` | 876 | `GeminiLiveProtocol.kt` | partial: protocol only (GeminiLiveProtocol.kt); socket/client and GeminiCallCoordinator pending wave 2/3 |  |
| `OpenCode/Voice/LiveCallCoordinator+Appends.swift` | 662 | | todo | |
| `OpenCode/Voice/LiveCallCoordinator+Events.swift` | 391 | | todo | |
| `OpenCode/Voice/LiveCallCoordinator.swift` | 557 | | todo | |
| `OpenCode/Voice/LiveClient.swift` | 336 | | todo | |
| `OpenCode/Voice/LiveProtocol.swift` | 671 | `LiveProtocol.kt` | done | VoiceEngine/VoiceProvider live here as in iOS (VoiceProvider.name → `displayName`); `VoiceKeyFingerprint` moved down from Settings/SettingsView.swift. ByteArray payload cases compare by content. |
| `OpenCode/Voice/LiveRTC.swift` | 1200 | | todo | |
| `OpenCode/Voice/LivingOrbField.swift` | 157 | | todo | |
| `OpenCode/Voice/LivingOrbShaders.metal` | 33 | | todo | |
| `OpenCode/Voice/LivingOrbStyle.swift` | 27 | | todo | |
| `OpenCode/Voice/LivingOrbTunables.swift` | 80 | | todo | |
| `OpenCode/Voice/LivingOrbView.swift` | 93 | | todo | |
| `OpenCode/Voice/RaiseToEar.swift` | 237 | `RaiseToEar.kt` | done | Proximity plus gravity/filtered accelerometer; 350/800/2500/100 ms timing, exact held-up thresholds, proximity-only screen-off lock scoped to monitor. Device validation pending. |
| `OpenCode/Voice/RealtimeClient.swift` | 437 | `RealtimePreflight.kt`, `RealtimeClient.kt` | partial: preflight only (lines 1–126: `RealtimePreflightClient`, `RealtimePreflightError` with its exact copy, OpenAI model GET, xAI `GET /v1/api-key` check; the fingerprint skip is in `VoiceFeatureStart.kt`) plus `RealtimeClientError` (in `RealtimeClient.kt`, which the reducer needs for `.dropped`); `RealtimeConnection`, `RealtimeClient`, the WebSocket and the rest belong to the transport wave | Preflight requests go through `OpenCodeTransport.request` with a 15 s timeout and no retry, as iOS's `URLSession.shared`; any `IOException` is “No connection.”. The lab preflight (`VoiceLabDemo.preflight`) comes from `VoiceLabFlags` until VoiceLabDemo is ported. |
| `OpenCode/Voice/RealtimeProtocol.swift` | 347 | `RealtimeProtocol.kt` | done | `RealtimeJSON` = kotlinx `JsonElement` with `RealtimeJSONs` builders (integral numbers encode as Swift writes them). |
| `OpenCode/Voice/ReplyWatch.swift` | 725 | `ReplyWatch.kt` | done | Immutable value: `transition(event)` returns the next watch and its effects (iOS `step` mutates); `noteUserActed`/`setSessionActive` return copies. `QuietGate` is declared here so it keeps the name `ReplyWatch.QuietGate`. |
| `OpenCode/Voice/ScreenAwakeClient.swift` | 41 | `ScreenAwakeClient.kt` | partial: app Activity registry wiring | Hold-counted FLAG_KEEP_SCREEN_ON; app must attach/detach resumed/paused activities to VoiceScreenActivityRegistry. Existing flags are not cleared. |
| `OpenCode/Voice/ServerVoiceClient.swift` | 415 | `ServerVoiceClient.kt` | done | Server transport as iOS (no NetworkRetry, as iOS); `voiceServerID` is VoiceDelegatorWorkspace.serverID's hash; `VoiceServerChoice` key in VoiceSharedKeys. |
| `OpenCode/Voice/VoiceActionClaim.swift` | 56 | `VoiceActionClaim.kt` | done |  |
| `OpenCode/Voice/VoiceAudioClient.swift` | 575 | `VoiceAudioClient.kt` | done | PCM16 24 kHz, levels, adaptive 200–500 ms scheduler, per-item tracker, prestart/cancel ownership, hardware/errors, rendered-gap counters. |
| `OpenCode/Voice/VoiceAudioEngine.swift` | 1145 | `VoiceAudioEngine.kt` | done | AudioRecord/Track threads, AEC/NS/AGC, shared focus/mode/device, capture gate, mute, route ramps, interruptions, teardown; external-owner ADM and VoiceAudioLabHooks seams for later waves. Hardware validation pending; ObjC exception catcher n/a. |
| `OpenCode/Voice/VoiceAudioQuality.swift` | 150 | `VoiceAudioQuality.kt` | done | Five-second windows, arrival/capture/send percentiles, mid-speech starvation only, lifetime totals, address-free route metadata. |
| `OpenCode/Voice/VoiceBrokerTransport.swift` | 49 | | todo | |
| `OpenCode/Voice/VoiceCallClient.swift` | 215 | `VoiceCallClient.kt` | partial: liveValue is the inert test value until VoiceCallHost (wave 3) | `start` returns a `Flow`; `quietGate` lives next to ReplyWatch.QuietGate; lab rotate override via `VoiceLabEnvironment`. |
| `OpenCode/Voice/VoiceCallCoordinator.swift` | 425 | | todo | |
| `OpenCode/Voice/VoiceCallDiagnostics.swift` | 46 | `VoiceCallDiagnostics.kt` | partial: persistent diagnostics sink wiring | Event names, close/loss classification and code-only AppLog; app/devtools installs recorder sinks through the dependency key. |
| `OpenCode/Voice/VoiceCallEvent.swift` | 25 | `VoiceCallEvent.kt` | done |  |
| `OpenCode/Voice/VoiceCallHost.swift` | 251 | | todo | |
| `OpenCode/Voice/VoiceCallLog.swift` | 358 | `VoiceCallLog.kt` | partial: Developer Mode upload adapter | Tracks/notes, network monitoring, 40-log private storage, bounded/redacted snapshots complete; VoiceCallLogUploadClient is an inert app/devtools installation seam. |
| `OpenCode/Voice/VoiceCallLogView.swift` | 346 | | todo | |
| `OpenCode/Voice/VoiceCallLogging.swift` | 90 | `VoiceCallLogging.kt` | partial: after-reducer adapter | All Swift log-action semantics ported; VoiceFeature wave maps its post-reduction State/Action and clears callLogID when apply returns true. |
| `OpenCode/Voice/VoiceCallSounds.swift` | 676 | `VoiceCallSounds.kt` | done | Breath Ping DSP and baked callSound.* defaults; six prototype buffers match lengths/peaks/sample positions within 1e-7; call clock, synchronized cues/haptics, telemetry, end tail. |
| `OpenCode/Voice/VoiceControlSend.swift` | 9 | `VoiceControlSend.kt`, `RealtimeTransportDiagnostic.kt` | done | `RealtimeTransportDiagnostic` ported from RealtimeClient.swift (minus `errorCategory`: DiagnosticsErrorCategory isn't ported); transport errors implement `CarriesRealtimeTransportDiagnostic`. |
| `OpenCode/Voice/VoiceDelegator.swift` | 453 | `VoiceDelegator.kt` | done | Responses API over OkHttp (server transport pool, 60 s) + NetworkRetry idempotent; rounds results are `Result` with `OpenCodeClientError` failures. |
| `OpenCode/Voice/VoiceDelegatorWorkspace.swift` | 989 | `VoiceDelegatorWorkspace.kt`, `VoiceAdmission.kt`, `VoiceLogUploader.kt` | done | Actor → one confined dispatcher. Upward refs: `SessionFeature.PendingPrompt` → `VoiceAdmittedPrompt` (`Effects.sent`; `VoiceAdmissionRecorder` key for :app), `ComposerFileAttachment` → `OpenCodeFileReference`, `DeveloperMode` → `VoiceLogUploader` key (inert live; :app/devtools installs), `serverID` → `voiceServerID`. Tool JSON: sorted keys, `\/` escaped like JSONSerialization. |
| `OpenCode/Voice/VoiceFeature+Admission.swift` | 17 | `VoiceFeatureAdmission.kt` | done | `SessionFeature.PendingPrompt` + `PromptOutbox.recordAdmitted` → `VoiceAdmittedPrompt` + `VoiceAdmissionRecorder` (inert live value; :app installs the composer's outbox). |
| `OpenCode/Voice/VoiceFeature+Context.swift` | 83 | `VoiceFeatureContext.kt`, `VoiceFeature.kt` (`savedServers`) | done | `bounded` → `withTimeoutOrNull`. |
| `OpenCode/Voice/VoiceFeature+Delegation.swift` | 471 | `VoiceFeatureDelegation.kt` | done | `Effect.runOwningRetries`/`Send.leavingRetryScope` → `runOwningRetries` (`withRetryPolicy(RetryPolicy.once)`) and `Send.leavingRetryScope()` (`NetworkRetry.outsideScope`). Typed `throws(OpenCodeClientError)` → catch, non-client errors wrapped as `OpenCodeClientError`. |
| `OpenCode/Voice/VoiceFeature+Instructions.swift` | 90 | `VoiceFeatureInstructions.kt` | done | |
| `OpenCode/Voice/VoiceFeature+Start.swift` | 185 | `VoiceFeatureStart.kt` | done | `async let preflight` → `coroutineScope { async { … } }`. `haptics.voice(.callStarted)`/`cancelPendingVoiceStart` → `VoiceCallSounds.voice`/`cancelPendingStart`. Also sets the Android call presence ID. `VoiceLabFlags` (internal, same file) reads the `VoiceLabDemo` env hooks the reducer needs (`OPENCODE_LAB_VOICE_DEMO`, `_REALTIME_URL`, `_LIVE_URL`, `_OPENAI_KEY`, `_VOICE_ENGINE`) until VoiceLabDemo.kt exists. |
| `OpenCode/Voice/VoiceFeature.swift` | 969 | `VoiceFeature.kt` | done | Immutable `State`; `phase`/`isAtEar` `didSet`s → `withPhase`/`withAtEar`; `mutating func`s return copies (`retarget`, `retargetToDraft`, `moveDraft`, `clearLiveReply`, `releaseUnadmitted`); `persistTranscript` writes the shared transcripts. `@Shared` fields are getters over `shared(…)`. `VoiceCallLogging` runs after the reducer through its adapter (clears `callLogID`). End's press → `VoiceCallSounds.cancelPressed()` (`.cancel` + landing skip). Android additions: the call owns its foreground service (`callPresenceID`, start on start, update on phase/mute/reconnecting, “Call ended” for 3 s on failure/drop, stop on hang-up or Close) and collects its Mute/End commands (`Action.CallCommand`, `CancelID.CallCommands`/`PresenceLinger`). `Action.Delegate` carries `DelegateAction`. |
| `OpenCode/Voice/VoiceHapticDesign.swift` | 250 | `VoiceHapticDesign.kt` | done | Measured RMS envelopes, exact control/scan/audio patterns, late trimming and minimumGap. |
| `OpenCode/Voice/VoiceHapticEngine.swift` | 223 | `VoiceHapticEngine.kt` | done | Mixes overlapping patterns into amplitude waveforms; background suspension and bounded finish. Continuous sharpness has no API 35 equivalent (curve preserved in design); vocabulary fallback via HapticsClient. |
| `OpenCode/Voice/VoiceLabDemo.swift` | 399 | | todo | |
| `OpenCode/Voice/VoiceLabTriggers.swift` | 42 | `VoiceLabTriggers.kt` | done | `VoiceLabTrigger` enum only; the `VoiceLabTriggers` view modifier (it drives `StoreOf<SessionFeature>`) moved to `:feature:session`. |
| `OpenCode/Voice/VoiceQuietGate.swift` | 153 | `VoiceQuietGate.kt` (+ type in `ReplyWatch.kt`) | done | Policy (`delay`, `answerDelay`, `userSpeechHold`, `holdReason`, `QuietGate.live`) as extensions; instants are `Clock.now()` `Duration`s; `note…` return the next gate. Also `VoiceCallTiming.quietGate` (iOS VoiceCallClient.swift). |
| `OpenCode/Voice/VoiceRoute.swift` | 122 | `VoiceRoute.kt` | done | `VoiceRoute.Server` → `ServerRoute`; `VoiceKeySource` moved down from Settings/VoiceKeySource.swift. |
| `OpenCode/Voice/VoiceRoutePreferences.swift` | 35 | `VoiceRoutePreferences.kt` | done | Holds the `Shared` references (equal by identity); lab overrides via `VoiceLabFlags`. |
| `OpenCode/Voice/VoiceSessionBoard.swift` | 191 | `VoiceSessionBoard.kt` | done | Home's `voiceSessionBoard` extension → `VoiceSessionBoard.make(…)` that Drawer calls. |
| `OpenCode/Voice/VoiceSessionCoordinator.swift` | 497 | | todo | |
| `OpenCode/Voice/VoiceTimeline.swift` | 117 | `VoiceTimeline.kt`, `VoiceDiagnostics.kt` | done | `DiagnosticsClient.voice` and `LabMarks.mark` → voice-owned `VoiceDiagnostics` key (inert live value; devtools/app may install one). |
| `OpenCode/Voice/VoiceTranscript.swift` | 712 | `VoiceTranscript.kt` | done | Immutable tail (mutating methods return the new tail); handoff JSON written exactly as Swift's pretty encoder; `VoiceViewingPage` declared here (Browser is in :feature:media). |
| `OpenCode/Voice/VoiceTurnHistory.swift` | 118 | `VoiceTurnHistory.kt`, `LiveCallCoordinator.kt` (constants only) | done | Immutable; LiveCallCoordinator holds only `claimLeadPrefix`/`earlierConnectionLead` until wave 3. |
| `OpenCode/Voice/VoiceTurnTaking.swift` | 176 | `VoiceTurnTaking.kt` | done | Immutable; methods return (state, result). |
| `OpenCode/Voice/VoiceUplinkWatch.swift` | 52 | `VoiceUplinkWatch.kt` | done | Immutable; methods return (watch, change). |
| `OpenCode/Voice/VoiceView.swift` | 1199 | | todo | |
| `OpenCode/Voice/VoiceWorkClient.swift` | 515 | `VoiceWorkClient.kt` | done | `VoiceWorkCoordinator` actor → Mutex-guarded class; `PromptOutbox.newSessionID()` → `ServerIDClient.sessionID()`; `OpenCodeMarkdownBlock.markdown(from:)` → `AssistantMarkdown.text` (raw Markdown). Character counts are UTF-16 lengths (Swift counts graphemes). |
