# Parity: session

Module: `:feature:session`. iOS folders: `OpenCode/Session/`. 47 files, 24,329 lines.

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`. Fill in the Kotlin file(s) as you port; see [README.md](README.md).

| iOS file | Lines | Kotlin file(s) | Status | Notes |
| --- | ---: | --- | --- | --- |
| `OpenCode/Session/AgentActivity/ActivityGyro.swift` | 978 | `agentactivity/ActivityGyro.kt`, `AgentActivitySource.kt` (AgentActivity enum) | done |  |
| `OpenCode/Session/AgentActivity/AgentActivityGlyph.swift` | 348 | `AgentActivityGlyph.kt`, `agentactivity/AgentActivityOrb.kt`, `agentactivity/GlyphModels.kt` | done |  |
| `OpenCode/Session/AgentActivity/AgentActivityShaders.metal` | 172 | `agentactivity/AgentActivityShaders.kt` | partial: `agentMessageRevealGlow` not ported (belongs with AgentMessageRevealLight in :feature:transcript) | `activityOrbField` → AGSL RuntimeShader (minSdk 35, no fallback needed). |
| `OpenCode/Session/AgentActivity/AgentActivitySource.swift` | 639 | `AgentActivitySource.kt` | done | Darwin-notification bridge for AgentActivityLab not ported; `AgentActivityLab.handle(command)` takes the same commands. `AgentActivityLedger.shared.record` not yet fed by the event consumer. |
| `OpenCode/Session/AgentActivity/ShellGlyph.swift` | 245 | `ShellOutputMeter.kt`, `ShellGlyph.kt`, `SessionFeatureActivity.kt` | done | Probe cursor uses Int.MAX_VALUE (client takes Int). |
| `OpenCode/Session/AgentActivity/ShellPromptGlyph.swift` | 318 | `agentactivity/ShellPromptGlyph.kt` | done |  |
| `OpenCode/Session/BackgroundWorkMotion.swift` | 405 | `BackgroundWorkMotion.kt` | done | Tunables kept as shipped constants. |
| `OpenCode/Session/BackgroundWorkPanel.swift` | 967 | `BackgroundWorkPanel.kt` | partial: debug traces (GlassMorphTrace, stage log) not ported | Liquid Glass → one M3 surface morphing capsule↔panel; drag-to-close via nestedScroll. |
| `OpenCode/Session/ComposerViews.swift` | 910 | | todo | |
| `OpenCode/Session/DictationViews.swift` | 767 | | todo | |
| `OpenCode/Session/DraftProjects.swift` | 431 | `DraftProjects.kt` | partial: chosen-row highlight fades instead of sliding |  |
| `OpenCode/Session/FolderBrowserFeature.swift` | 137 | `FolderBrowserFeature.kt` | done | One listDirectory per opened folder, no prefetch. |
| `OpenCode/Session/SessionContextRing.swift` | 50 | `header/SessionContextRing.kt` | done |  |
| `OpenCode/Session/SessionExport.swift` | 124 | `SessionExport.kt`, `AndroidManifest.xml`, `res/xml/session_export_paths.xml` | done | Share via Intent + FileProvider (`${applicationId}.sessionexport`). |
| `OpenCode/Session/SessionFeature+Agents.swift` | 177 | | todo | |
| `OpenCode/Session/SessionFeature+Attachments.swift` | 115 | | todo | |
| `OpenCode/Session/SessionFeature+Bindings.swift` | 296 | `SessionFeatureBindings.kt` | done | BindableAction → `Action.Binding(BindingAction.*)`. |
| `OpenCode/Session/SessionFeature+Browser.swift` | 381 | | todo | |
| `OpenCode/Session/SessionFeature+Delivery.swift` | 359 | | todo | |
| `OpenCode/Session/SessionFeature+Dictation.swift` | 221 | | todo | |
| `OpenCode/Session/SessionFeature+History.swift` | 437 | | todo | |
| `OpenCode/Session/SessionFeature+Models.swift` | 407 | | todo | |
| `OpenCode/Session/SessionFeature+PendingInputs.swift` | 597 | | todo | |
| `OpenCode/Session/SessionFeature+Refresh.swift` | 419 | | todo | |
| `OpenCode/Session/SessionFeature+Requests.swift` | 309 | | todo | |
| `OpenCode/Session/SessionFeature+Run.swift` | 310 | | todo | |
| `OpenCode/Session/SessionFeature+Send.swift` | 1021 | | todo | |
| `OpenCode/Session/SessionFeature+State.swift` | 1164 | `SessionFeatureState.kt`, `SessionFeature.kt` (nested types) | done | isVoiceAvailable has no DEBUG voice-lab override. |
| `OpenCode/Session/SessionFeature+Stream.swift` | 658 | | todo | |
| `OpenCode/Session/SessionFeature.swift` | 1180 | `SessionFeature.kt`, `seams/FoundationSeams.kt` | partial: behaviour of other-module children runs through seams (composer/voice/media/transcript/system) | BodyCache/AllCasePaths n/a. |
| `OpenCode/Session/SessionHeaderBackdrop.swift` | 418 | `header/SessionHeaderBackdrop.kt` | done | Variable blur → M3 surfaceContainer scrim on the same strength curve; VariableBlurView/HeaderBlurProbe/ScrollEdgeElementContainer n/a. |
| `OpenCode/Session/SessionHeaderStyle.swift` | 398 | `header/SessionHeaderStyle.kt` | done | Tuning presets / TuningStore / DEBUG lab n/a. |
| `OpenCode/Session/SessionPageStack.swift` | 365 | `SessionPageStack.kt` | done | Mounted pages via SaveableStateHolder; drawer spring; in-app pan + PredictiveBackHandler honouring BackSwipeRules/yieldsBackSwipe. |
| `OpenCode/Session/SessionSheetFeature.swift` | 317 | `SessionSheetFeature.kt`, `sheet/SheetClientCalls.kt` | done |  |
| `OpenCode/Session/SessionSheetView.swift` | 646 | `SessionSheetView.kt` | partial: status orb / provider icon are slots (wired by SessionView) | SheetGlassBackground → ModalBottomSheet; ActivitySharePresenter → SessionExportClient.share; sliding highlight → fade. |
| `OpenCode/Session/SessionTranscriptView.swift` | 659 | | todo | |
| `OpenCode/Session/SessionView+Attachments.swift` | 543 | | todo | |
| `OpenCode/Session/SessionView+Composer.swift` | 1153 | | todo | |
| `OpenCode/Session/SessionView+ComposerControls.swift` | 489 | | todo | |
| `OpenCode/Session/SessionView+Header.swift` | 464 | | todo | |
| `OpenCode/Session/SessionView+ModelPicker.swift` | 859 | | todo | |
| `OpenCode/Session/SessionView+PhotoSheet.swift` | 571 | | todo | |
| `OpenCode/Session/SessionView.swift` | 1030 | | todo | |
| `OpenCode/Session/ShellOutputFeature.swift` | 273 | `ShellOutputFeature.kt` | done | Adds TaskEnded/ShareOutputTapped actions (Android lifecycle/share). |
| `OpenCode/Session/ShellOutputView.swift` | 369 | `ShellOutputView.kt` | done | UITextView → LazyColumn keyed by byte offset; 32dp follow rule. |
| `OpenCode/Session/SinceYouLeft.swift` | 643 | `SinceYouLeft.kt` | partial: no on-device model on Android (live client unavailable → structuralFallback); SinceYouLeftLab (debug fixture) not ported; row grows with animateContentSize, not ArrivingText |  |
| `OpenCode/Session/TitleBreathing.swift` | 590 | `header/TitleBreathing.kt` | done | Shipped Breathing look ported exactly; Shimmer/Phosphor/Wave/TitleLoadingReplay are Developer Mode tuning looks (n/a). |
