# Parity: core

Module: `:core`. iOS folder: `OpenCode/Core/`. Visual primitives (themes, motion, buttons, tap
targets, icons, rolling number) are tracked in detail in [core-ui.md](core-ui.md); server-typed
parts of `SharedKeys.swift` and `ServerID*.swift` in [server.md](server.md).

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`.

## Architecture (no single iOS file: ports TCA / swift-dependencies / swift-sharing as the app uses them)

| iOS concept | Kotlin file(s) | Status | Notes |
| --- | --- | --- | --- |
| `@Reducer`, `Reduce`, `Scope`, `CombineReducers`, `.onChange`, `.ifLet(\.$x)`, `.forEach`, `@Presents`/`PresentationAction` | `core/.../store/Reducer.kt` | done | `inout State` → returns `Reduced(state, effect)`; both `ifLet` forms and `forEach` namespace nested cancellation IDs by the complete navigation path (slot + child identity) and cancel all child work on removal/replacement |
| `Effect` (`.none/.run/.send/.merge/.concatenate/.cancellable/.cancel/.map/.debounce`) | `core/.../store/Effect.kt`, `EffectRunner.kt` | done | `.run` captures the creating `DependencyValues` (TCA escaping-dependency semantics); cancellation uses `(navigation path, id)`. Deliberate contract difference from TCA 1.26.2's prefix registration: an explicit parent cancel affects only its own level, not descendants; navigation teardown still cancels the whole child subtree |
| `Store`, `store.scope`, view observation | `core/.../store/Store.kt` | done | main-thread `send`; `collectState()` / `observe {}` for Compose |
| `TestStore` (exhaustive) | `core/.../store/TestStore.kt` | done | `send/receive/skipReceivedActions/finish`, `exhaustive = false` for non-exhaustive |
| `@Dependency`, `DependencyKey`, `withDependencies`, `liveValue/testValue` | `core/.../dependencies/DependencyValues.kt` | done | thread/coroutine-local container; test values cached per test container |
| `\.continuousClock`, `\.date`, `\.uuid` (+ `TestClock`, `ImmediateClock`, `.incrementing`) | `core/.../dependencies/StandardDependencies.kt` | done | plus `AppContext` (the Android application context) |
| iOS `Tunable` / `TunableSpring` / `TuningPresets` / `TuningStore` / `TuningContext` / `AnimationTimeScale` / `TuningAccess` / `InternalBuild` (from `OpenCode/Tuning/`, `Diagnostics/`) | `core/.../tuning/Tunable.kt`, `TuningStore.kt`, `AnimationTimeScale.kt`, `TuningAccess.kt` | done | moved down from `:devtools` so every feature declares phone-judged values as `Tunable`s with iOS names/defaults (see devtools.md). Declarations stay one line for `scripts/tune.py bake` |
| `@Shared`, `SharedKey`, `.appStorage/.fileStorage/.inMemory`, `$x.withLock` | `core/.../sharing/Shared.kt`, `LiveSharedBytes.kt` | done | DataStore Preferences / JSON files / in-memory; `Shared` equality is by key identity |

## Files

| iOS file | Lines | Kotlin file(s) | Status | Notes |
| --- | ---: | --- | --- | --- |
| `OpenCode/Core/ActiveScene.swift` | 143 | `feature/system/.../SceneWindowClient.kt` | n/a | iPad multi-window scene tracking; Android has one activity (multi-window needs nothing app-side). `SceneWindow` (`isFront`) is ported in `:feature:system` as `SceneWindowClient` (see system.md) |
| `OpenCode/Core/AppGroup.swift` | 11 | — | n/a | App Group container for the share extension; the Android share target runs in the same app process and storage |
| `OpenCode/Core/AppLog.swift` | 124 | `core/.../log/AppLog.kt` | done | logcat tag `OpenCode/<category>` + in-process ring buffer for `recentLines` (the app's own subsystem only, as iOS's `OSLogStore` predicate: `opencode.perf` marks stay out); `Duration.logSummary` rounds half-even like ICU; unhandled effects log only throwable/cause class names and the top frame's class/method/line (no messages, file names or full stacks); the `OpenCodeClientError`/`RetryVerdict`/`RetryPause.Kind` `logSummary` extensions live in `:server` (see server.md) |
| `OpenCode/Core/AudioSessionOwner.swift` | 171 | `feature/voice/.../AudioSessionOwner.kt` | done | moved down to `:feature:voice` (see voice.md): shared-holder audio focus / `AudioManager` mode ownership for calls, dictation and media |
| `OpenCode/Core/BackSwipe.swift` | 390 | see core-ui.md | — | |
| `OpenCode/Core/BackgroundTaskClient.swift` | 42 | `core/.../dependencies/BackgroundClients.kt` | n/a: Android has no `beginBackgroundTask` grace period; a backgrounded process keeps running until the cached-app freezer stops it, and only a foreground service or WorkManager job extends it | `BackgroundTaskClient` keeps the API as a logged marker so callers port unchanged; `BackgroundTimeClient` holds for its duration; durable work (outbox sends) uses WorkManager in its feature |
| `OpenCode/Core/BackgroundTimeClient.swift` | 34 | `core/.../dependencies/BackgroundClients.kt` | done | holds for the duration (process keeps running while cached) |
| `OpenCode/Core/CancellableTaskGate.swift` | 47 | — | n/a | `URLSessionDataTask` ↔ continuation gate; OkHttp `Call` + `suspendCancellableCoroutine` in `:server` transport |
| `OpenCode/Core/DecodedImageCache.swift` | 92 | `feature/media/.../MediaFoundation.kt` | done | moved to `:feature:media` (see media.md) |
| `OpenCode/Core/Glass.swift` | 38 | see core-ui.md | — | |
| `OpenCode/Core/HapticsClient.swift` | 496 | `core/.../haptics/HapticsClient.kt`, `feature/voice/.../VoiceCallSounds.kt` (`VoiceCallHaptic`, `VoiceHapticDesign`) | done | `Haptic` vocabulary, `HapticsClient.play/prepare`, `HapticEffect` (= `.haptic(_:trigger:condition:)`), foreground + system-setting gating, the diagnostics trace line with `AudioSessionHaptics` (audio mode from `AudioManager`; `:app` wires `dictating`/`call` from the window stores). Voice call cues (`voiceTick/HangUp/Glide`, Core Haptics patterns → `VibrationEffect` compositions) live in `:feature:voice`. `HapticLabCaption` is DEBUG lab tooling (n/a) |
| `OpenCode/Core/Keychain.swift` | 79 | `core/.../sharing/Shared.kt` (`SharedKey.keychain`), `LiveSharedBytes.kt` | done | JSON-encoded value sealed with an AES-256-GCM Android Keystore key in `noBackupFilesDir/keychain/` |
| `OpenCode/Core/KeychainStorage.swift` | 84 | `core/.../sharing/LiveSharedBytes.kt`, `InMemorySharedBytes` | done | test containers get in-memory storage, as `.inMemory()` |
| `OpenCode/Core/Motion/MotionConstants.swift` | 130 | see core-ui.md | — | |
| `OpenCode/Core/Motion/MotionDisplayLink.swift` | 90 | see core-ui.md | — | |
| `OpenCode/Core/Motion/PresenceGate.swift` | 146 | see core-ui.md | — | |
| `OpenCode/Core/Motion/SpringSolver.swift` | 222 | see core-ui.md | — | |
| `OpenCode/Core/RollingNumber/RollingNumber.swift` | 928 | see core-ui.md | — | |
| `OpenCode/Core/RollingNumber/RollingNumberShaders.metal` | 33 | see core-ui.md | — | |
| `OpenCode/Core/ServerID.swift` | 65 | see server.md | — | ported into `:server` |
| `OpenCode/Core/ServerIDClient.swift` | 55 | see server.md | — | ported into `:server` |
| `OpenCode/Core/SharedKeys.swift` | 748 | `core/.../sharing/SharedKeys.kt`, `server/.../ServerSharedKeys.kt`, `feature/voice/.../VoiceSharedKeys.kt` | done | primitive keys in `:core`; server-typed keys + `CachedHomeSnapshot`/`CachedTranscriptSnapshots`/`TranscriptCachePersistence` in `:server`; voice-typed keys in `:feature:voice`. Same ids and defaults |
| `OpenCode/Core/TapTarget.swift` | 37 | see core-ui.md | — | |
| `OpenCode/Core/Theme/Theme.swift` | 792 | see core-ui.md | — | |
| `OpenCode/Core/Theme/ThemePalette+Generated.swift` | 397 | see core-ui.md | — | |

## Tests

Ported from iOS `OpenCodeTests/` into `core/src/test` (JVM, same names). Shared fixtures:
`core/src/testFixtures` (`ai.opencode.core.testing`: `TestIsolationGuard`, `TestIsolationRule`,
`eventually`); see AGENTS.md "Tests". Under unit tests `DependencyValues.root` resolves test values
(`isProcessTesting`) and the guard resets it after each test.

| iOS file | Kotlin | Ported | Left elsewhere / deleted (reason) |
| --- | --- | --- | --- |
| `TestIsolationGuardTests.swift` | `testing/TestIsolationGuardTests.kt` | 4 (+1 in `:server`) | deleted: keyboard frame, window/first responder, `UIView` animations (UIKit), seeded order (Swift Testing hook) |
| `DiagnosticsRecorderTests.swift` | `diagnostics/DiagnosticsRecorderTests.kt` | 12 of 13 | `headerResolutionNotifiesObserversWhenChanged` → `:feature:session` (`SessionHeaderStyle`); `RealtimeTransportDiagnostic` half → `:feature:voice` |
| `PrivacyManifestTests.swift` | `diagnostics/DiagnosticsRecorderTests.kt` | 1 of 2 | manifest plist test deleted (iOS `PrivacyInfo.xcprivacy`) |
| `ExperimentalFeatureTests.swift` | `experiments/ExperimentalFeatureTests.kt` | 8 | `VoiceAudioDemoMode` half → `:feature:voice` |
| `HapticsAuditTests.swift` | `haptics/HapticsAuditTests.kt` | 2 (`LiveHapticsTests` source scan; `aRequestArrivingInTheBackgroundPlaysNothing` under Robolectric in `LiveHapticsFrameworkTests`: `LiveHaptics` gated by `ProcessLifecycleOwner`, shadow `Vibrator`) | `HapticsAuditTests` suite → `:feature:session`/`:feature:drawer`; `VoiceCallHaptic` fallbacks → `:feature:voice`, ported there |
| `AudioSessionHapticsTests.swift` | `haptics/AudioSessionHapticsTests.kt` | 1 (`traceLineIsContentFreeAndNamesWhatMutesHaptics`) | deleted: `LiveDictationAudioSessionTests` (6, opt-in suite on the simulator's real microphone and `AVAudioSession`) |
| `TuningTests.swift` | `tuning/TuningTests.kt` | 27 | TuningFeature/Wire/Placement/summon gesture → `:devtools`; drawer/composer/transcript tunables → features; MotionXRay not ported; CALayer checks deleted |
| `MotionSystemTests.swift` | `motion/MotionSystemTests.kt` | 5 | empty-state → `:feature:transcript`; drawer shortcuts (4) → `:feature:drawer`; window probe deleted |
| `RollingNumberTests.swift` | `ui/RollingNumberTests.kt` | 8 of 9 | `rollingTitleSurvivesPixelSnapping` deleted (UIWindow pixel snapping) |
| `ThemePaletteTests.swift` | `ui/ThemePaletteTests.kt` | 5 of 8 | appearance settings → `:feature:settings`/`:feature:drawer` (ported there: `appearanceSettingsActions…`, Home half); `legacyTranscriptRhythmStorageIsCleanedUp` deleted (Android never wrote the key) |
| `PerfFrameWindowTests.swift` | `diagnostics/PerfFrameWindowTests.kt` | 6 of 9 | deleted: `infoPlistEnablesProMotion120HzOnPhone` (Info.plist), `proMotion120HzFrameRateRangeAndClockIntervals` (live `CADisplayLink`; its `DrawerSpring` half → `:feature:drawer`), `transcriptFollowerDisplayLinkDoesNotRequestAbove60Hz` (`CADisplayLink` rate range) |
| `PerfFreshTrackerTests.swift` | `diagnostics/PerfFreshTrackerTests.kt` | 4 | – |
| `MotionWatchTests.swift` (+`MotionAuditDetectorTests`) | `diagnostics/MotionWatchTests.kt` | 9 | `recordsReplayContext…` keeps everything but its `replayContext` asserts (`PlaygroundReplayContext` is the Mac playgrounds', n/a) |
| `TraceTests.swift` (`DiagnosticsJournalTests`, `InternalBuildTests`, `ProblemReportBundleTests`, `DiagnosticsPrivacyTests`, `CrashReportTests`) | `diagnostics/TraceTests.kt` | 14 | `TraceUploadsTests`, `screenshotsOnly…`, `diagnosticsIndexesMetricsByRequest` → `:devtools`; `VoiceTimelineTests` → `:feature:voice`; `TeamFeedbackTests` deleted (devtools.md) |
| `LivingOrbTests.swift` | `experiments/LivingOrbTests.kt` | 1 (`flagRestoresEveryExistingCallStyleWhenOff`, `ExperimentalCallStyle.of`) | orb → `:feature:voice`; style setting → `:feature:settings` |
| `SubagentStackTests.swift` | `ui/SubagentStackTests.kt` | 1 (`backRulesLetTheEdgeWinAndContentKeepItsClaims`; iOS `BackSwipeSpring.goesBack` is `BackSwipeRules.goesBack`, decision distance passed as iOS's 8) | stack → `:feature:drawer`; `descendantsFollowTheFamilyTree` → `:server` |
| `GlassShapeTests.swift` | `ui/GlassShapeTests.kt` | 2 of 3 | `GlassShape.clamped` → `OpenCodeShapeGeometry.clamped`; `tuningTabsKeepTheirWholeHitTargetOnScreen` → `:devtools` |

Round 2 (ui helper): no `:core` production bugs found. `InternalBuild.detect(isDebug, installingPackageName)` was pulled out of
`isEnabled` (unchanged behaviour) so `InternalBuildTests` can check it. 113 `:core` tests in total, all passing
(`./gradlew :core:testDebugUnitTest`). Robolectric (test-only) runs only `LiveHapticsFrameworkTests`; `LiveHaptics` is `internal` (was
`private`) so it can.
