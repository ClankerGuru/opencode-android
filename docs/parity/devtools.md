# Parity: devtools

Module: `:devtools`. iOS folders: `OpenCode/Tuning/`, `OpenCode/Diagnostics/`. 44 files, 16,495 lines.

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`. Fill in the Kotlin file(s) as you port; see [README.md](README.md).

| iOS file | Lines | Kotlin file(s) | Status | Notes |
| --- | ---: | --- | --- | --- |
| `OpenCode/Diagnostics/Diagnostics.swift` | 698 | `core/.../diagnostics/DiagnosticsRecorder.kt` | partial: no journal (previous-run tail) | moved to `:core` (`:server` records into it). `DiagnosticsErrorCategory`, `DiagnosticsEvent` (all fields, iOS JSON names), recorder (5,000 events / 15 min default-on in internal builds, else opt-in 2,000 / 10 min; `diagnostics.enabled`/`diagnostics.includeTranscripts`), marks/voice/socket/request/metrics/decode/stream records, navigation epochs, excerpts, `exportText`/`exportJSON`, `redactedPath` (`/auth/<route>/<redacted>`, `/api/fs/read/<redacted>`, query names only), coalesced 0.25 s `changes`. n/a: header-style resolution (session area), MetricKit bundle |
| `OpenCode/Diagnostics/DiagnosticsClient.swift` | 105 | | todo | |
| `OpenCode/Diagnostics/DiagnosticsJournal.swift` | 227 | | todo | |
| `OpenCode/Diagnostics/DiagnosticsNetwork.swift` | 113 | `core/.../diagnostics/DiagnosticsRecorder.kt`, `server/.../DiagnosticsInstrumentation.kt` | done | failure phases, interface/family/scope enums, `DiagnosticsNetworkAddress` scope rules, stream names and `DiagnosticsStreamProgress` (30 s resume). `URLSessionTaskMetrics` → OkHttp `EventListener` (DNS/connect/TLS/TTFB/download/upload, header/body bytes, protocol, reuse); in-flight count in the interceptor |
| `OpenCode/Diagnostics/DiagnosticsView.swift` | 421 | | todo | |
| `OpenCode/Diagnostics/GlassMorphTrace.swift` | 491 | | todo | |
| `OpenCode/Diagnostics/LabMarks.swift` | 221 | `core/.../diagnostics/LabMarks.kt` | partial: trace payload helpers (`promotionTrace`, `motionTrace`, `transcriptSnapshot`, …) and `observe`/`forward` not ported | moved to `:core`. `mark`/`markOnce` (AppLog.perf line, Perfetto instant, diagnostics mark, POST to the lab endpoint with `footprintKB` = PSS), `post(event:)`, `needsNetworkMetrics`. Endpoint = intent extra `lab-marks-url` (debug builds only). `PerfTrace` adds `android.os.Trace` sections/async intervals/counters |
| `OpenCode/Diagnostics/LabTapAudit.swift` | 294 | | todo | |
| `OpenCode/Diagnostics/MetricKitClient.swift` | 482 | | todo | |
| `OpenCode/Diagnostics/PerfFrameSampler.swift` | 201 | | todo | |
| `OpenCode/Diagnostics/PerfTimeline.swift` | 457 | | todo | |
| `OpenCode/Diagnostics/ScrawlTags.swift` | 73 | | todo | |
| `OpenCode/Diagnostics/TouchProbe.swift` | 407 | | todo | |
| `OpenCode/Diagnostics/Traces/DeveloperMode.swift` | 131 | | todo | |
| `OpenCode/Diagnostics/Traces/HomeFeature+Traces.swift` | 57 | | todo | |
| `OpenCode/Diagnostics/Traces/MotionWatch.swift` | 413 | | todo | |
| `OpenCode/Diagnostics/Traces/ProblemReport.swift` | 312 | | todo | |
| `OpenCode/Diagnostics/Traces/TeamFeedback.swift` | 499 | | todo | |
| `OpenCode/Diagnostics/Traces/TraceTriggers.swift` | 672 | | todo | |
| `OpenCode/Diagnostics/Traces/TraceUploads.swift` | 537 | | todo | |
| `OpenCode/Tuning/AnimationTimeScale.swift` | 309 | `core/.../tuning/AnimationTimeScale.kt` | done | moved to `:core`. Virtual clock, `scaledDelta`, `wallSeconds`, `setScale` (access-gated), `normalizeScale`, `requestedLabScale`. `window.layer.speed` → applied in `Motion.*`/`MotionSpring.spec` (stiffness × s²), tween durations ÷ s, `motionFrameLoop` dt × s. n/a: Darwin-notification lab bridge |
| `OpenCode/Tuning/MotionXRay+Autocomplete.swift` | 108 | | todo | |
| `OpenCode/Tuning/MotionXRay+Findings.swift` | 382 | | todo | |
| `OpenCode/Tuning/MotionXRay+Surfaces.swift` | 477 | | todo | |
| `OpenCode/Tuning/MotionXRay.swift` | 993 | | todo | |
| `OpenCode/Tuning/Playgrounds/PlaygroundBridgeAndPresets.swift` | 247 | | todo | |
| `OpenCode/Tuning/Playgrounds/PlaygroundFixtureEngine+Autocomplete.swift` | 72 | | todo | |
| `OpenCode/Tuning/Playgrounds/PlaygroundFixtureEngine+ClientAdapter.swift` | 332 | | todo | |
| `OpenCode/Tuning/Playgrounds/PlaygroundFixtureEngine+Composer.swift` | 573 | | todo | |
| `OpenCode/Tuning/Playgrounds/PlaygroundFixtureEngine+Drawer.swift` | 216 | | todo | |
| `OpenCode/Tuning/Playgrounds/PlaygroundFixtureEngine+Replays.swift` | 321 | | todo | |
| `OpenCode/Tuning/Playgrounds/PlaygroundFixtureEngine+TranscriptPresets.swift` | 722 | | todo | |
| `OpenCode/Tuning/Playgrounds/PlaygroundFixtureEngine.swift` | 199 | | todo | |
| `OpenCode/Tuning/Playgrounds/PlaygroundHostView.swift` | 897 | | todo | |
| `OpenCode/Tuning/Playgrounds/PlaygroundScenario.swift` | 46 | | todo | |
| `OpenCode/Tuning/Playgrounds/PlaygroundSessionHost.swift` | 273 | | todo | |
| `OpenCode/Tuning/Tunable.swift` | 538 | `core/.../tuning/Tunable.kt` | done | moved to `:core`. `Tunable` (Double/Float/Dp/Int/Boolean/`TunableChoice` overloads), `TunableSpring` (→ `MotionSpring`), `TuningTitle`, `TuningContext` (+ iOS contexts), `TuneValue`/`TuneKind`, `TunableDescriptor` (`accepting`, `valueText`, `effectiveRange`, step grid, `randomValue`, `alternateValue`, `decimal`), `TuningPreset(s)`. `swiftLiteral` → `kotlinLiteral`; `.tuningContext(_:)` → `TuningContextEffect` |
| `OpenCode/Tuning/TuningFeature.swift` | 632 | | todo | |
| `OpenCode/Tuning/TuningGestures.swift` | 98 | | todo | |
| `OpenCode/Tuning/TuningOverlay.swift` | 179 | | todo | |
| `OpenCode/Tuning/TuningPanelView.swift` | 781 | | todo | |
| `OpenCode/Tuning/TuningPlacement.swift` | 106 | | todo | |
| `OpenCode/Tuning/TuningRemote.swift` | 485 | | todo | |
| `OpenCode/Tuning/TuningStore.swift` | 698 | `core/.../tuning/TuningStore.kt` | done | moved to `:core`. Same registry semantics (register on first read while on, sanitize stale overrides, presets ordering, hidden groups, contexts, dice + undo, batch `set`, events as `SharedFlow`), persisted keys `tuning-overrides`/`tuning-hidden-groups` (SharedPreferences `tuning`). Observation → Compose snapshot state. Copy as Swift → `kotlinSnippet`; bake → `scripts/tune.py bake`. n/a: `labHeaderStyle`, playground status/jumps/captures (Mac page events) |
