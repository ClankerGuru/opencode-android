# Parity: core

Module: `:core`. iOS folder: `OpenCode/Core/`. Visual primitives (themes, motion, buttons, tap
targets, icons, rolling number) are tracked in detail in [core-ui.md](core-ui.md); server-typed
parts of `SharedKeys.swift` and `ServerID*.swift` in [server.md](server.md).

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`.

## Architecture (no single iOS file: ports TCA / swift-dependencies / swift-sharing as the app uses them)

| iOS concept | Kotlin file(s) | Status | Notes |
| --- | --- | --- | --- |
| `@Reducer`, `Reduce`, `Scope`, `CombineReducers`, `.onChange`, `.ifLet(\.$x)`, `.forEach`, `@Presents`/`PresentationAction` | `core/.../store/Reducer.kt` | done | `inout State` → returns `Reduced(state, effect)`; `ifLet`/`forEach` cancel child effects on dismissal/removal |
| `Effect` (`.none/.run/.send/.merge/.concatenate/.cancellable/.cancel/.map/.debounce`) | `core/.../store/Effect.kt`, `EffectRunner.kt` | done | `.run` captures the creating `DependencyValues` (TCA escaping-dependency semantics) |
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
| `OpenCode/Core/AppLog.swift` | 124 | `core/.../log/AppLog.kt` | done | logcat tag `OpenCode/<category>` + in-process ring buffer for `recentLines` (the app's own subsystem only, as iOS's `OSLogStore` predicate: `opencode.perf` marks stay out); `Duration.logSummary` rounds half-even like ICU; the `OpenCodeClientError`/`RetryVerdict`/`RetryPause.Kind` `logSummary` extensions live in `:server` (see server.md) |
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
