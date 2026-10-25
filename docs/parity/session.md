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
| `OpenCode/Session/ComposerViews.swift` | 910 | `ComposerViews.kt` | done | UndimmedWhilePressed / ComposerPasteDestination / tuning presets n/a. |
| `OpenCode/Session/DictationViews.swift` | 767 | `DictationViews.kt` | partial: real dot scope (Canvas stand-in follows level until :feature:voice DictationDotScope) | DictationLabTriggers n/a (lab). Also ports Composer/MicPressGesture `MicPress`. |
| `OpenCode/Session/DraftProjects.swift` | 431 | `DraftProjects.kt` | partial: chosen-row highlight fades instead of sliding |  |
| `OpenCode/Session/FolderBrowserFeature.swift` | 137 | `FolderBrowserFeature.kt` | done | One listDirectory per opened folder, no prefetch. |
| `OpenCode/Session/SessionContextRing.swift` | 50 | `header/SessionContextRing.kt` | done |  |
| `OpenCode/Session/SessionExport.swift` | 124 | `SessionExport.kt`, `AndroidManifest.xml`, `res/xml/session_export_paths.xml` | done | Share via Intent + FileProvider (`${applicationId}.sessionexport`). |
| `OpenCode/Session/SessionFeature+Agents.swift` | 177 | `SessionFeatureAgents.kt` | done |  |
| `OpenCode/Session/SessionFeature+Attachments.swift` | 115 | `SessionFeatureAttachments.kt` | done |  |
| `OpenCode/Session/SessionFeature+Bindings.swift` | 296 | `SessionFeatureBindings.kt` | done | BindableAction → `Action.Binding(BindingAction.*)`. |
| `OpenCode/Session/SessionFeature+Browser.swift` | 381 | `SessionFeatureBrowser.kt` | done | `SessionLink`/`sessionLinkIDs`/`LinkMenuAction`/`SessionLinkTarget` from :feature:transcript; BrowserLinkPolicy/BrowserFeature/UniversalLinkClient/OpenURLClient still seams (media/system). |
| `OpenCode/Session/SessionFeature+Delivery.swift` | 359 | `SessionFeatureDelivery.kt` | done | Page reader seam sends URL+title only until :feature:media lands. |
| `OpenCode/Session/SessionFeature+Dictation.swift` | 221 | `SessionFeatureDictation.kt` | done | DictationFeature reducer/client are composer seams. |
| `OpenCode/Session/SessionFeature+History.swift` | 437 | `SessionFeatureHistory.kt` | done | Debug lab override / PerfTimeline n/a. |
| `OpenCode/Session/SessionFeature+Models.swift` | 407 | `SessionFeatureModels.kt` | done | Family drafts in memory until composer persistence lands. |
| `OpenCode/Session/SessionFeature+PendingInputs.swift` | 597 | `SessionFeaturePendingInputs.kt` | done |  |
| `OpenCode/Session/SessionFeature+Refresh.swift` | 419 | `SessionFeatureRefresh.kt` | done | PerfTimeline n/a (perfSnapshotRevision stays null). |
| `OpenCode/Session/SessionFeature+Requests.swift` | 309 | `SessionFeatureRequests.kt`, `SessionFeatureRequestAnswer.kt` | done | Composer/RequestAnswer.swift (RequestDockFeature, RequestBar, AnswerContext, answering State members) ported here: it extends SessionFeature.State. |
| `OpenCode/Session/SessionFeature+Run.swift` | 310 | `SessionFeatureRun.kt` | done |  |
| `OpenCode/Session/SessionFeature+Send.swift` | 1021 | `SessionFeatureSend.kt`, `PromptOutbox.kt`, `UndeliveredPrompts.kt`, `RunErrorState.kt` | partial: composer persistence not ported yet (family recordings go via keepDraft; persistedDraftDirectory reads the outbox only); autocomplete (experimental, off) runs against seams | Composer/PromptOutbox.swift, UndeliveredPrompts.swift and the State parts of Transcript/RunErrorState.swift live here because they carry SessionFeature types; outbox not durable across process death until composer persistence implements `OutboxPersistence`. |
| `OpenCode/Session/SessionFeature+State.swift` | 1164 | `SessionFeatureState.kt`, `SessionFeature.kt` (nested types) | done | isVoiceAvailable has no DEBUG voice-lab override. |
| `OpenCode/Session/SessionFeature+Stream.swift` | 658 | `SessionFeatureStream.kt`, `SessionFeatureNewMessages.kt` | done | Live text stays raw (Android items carry text; markdown blocks are transcript's); PERF latency mark n/a. NewMessagesMark.swift State members ported here (State extensions must live in this module). |
| `OpenCode/Session/SessionFeature.swift` | 1180 | `SessionFeature.kt`, `seams/FoundationSeams.kt` | partial: behaviour of composer/voice/media children runs through seams (transcript/system/core types are real) | BodyCache/AllCasePaths n/a. The Android-only `TranscriptFeature` child was dropped: the screen drives `TranscriptList` directly. |
| `OpenCode/Session/SessionHeaderBackdrop.swift` | 418 | `header/SessionHeaderBackdrop.kt` | done | Variable blur → M3 surfaceContainer scrim on the same strength curve; VariableBlurView/HeaderBlurProbe/ScrollEdgeElementContainer n/a. |
| `OpenCode/Session/SessionHeaderStyle.swift` | 398 | `header/SessionHeaderStyle.kt` | done | Tuning presets / TuningStore / DEBUG lab n/a. |
| `OpenCode/Session/SessionPageStack.swift` | 365 | `SessionPageStack.kt` | done | Mounted pages via SaveableStateHolder; drawer spring; in-app pan + PredictiveBackHandler honouring BackSwipeRules/yieldsBackSwipe. |
| `OpenCode/Session/SessionSheetFeature.swift` | 317 | `SessionSheetFeature.kt`, `sheet/SheetClientCalls.kt` | done |  |
| `OpenCode/Session/SessionSheetView.swift` | 646 | `SessionSheetView.kt` | partial: status orb / provider icon are slots (wired by SessionView) | SheetGlassBackground → ModalBottomSheet; ActivitySharePresenter → SessionExportClient.share; sliding highlight → fade. |
| `OpenCode/Session/SessionTranscriptView.swift` | 659 | `SessionTranscriptView.kt`, `SessionScrollEdgeBridge.kt` | partial: header reveal (`scrollEdgeBridge.scrolled`) not fed, since `TranscriptList` reports no scroll offset; image/file viewers (`onPreviewImage`/`onOpenFile`, :feature:media) only end text focus; the rows' Since You Left/agent glyph/provider mark slots are provided but `TranscriptList` shadows `LocalTranscriptRowSlots` with its own; `dismissKeyboard` on list scroll n/a | ProjectionCache (window, structural, stamp and final stages, streaming fast paths), `configuration` → `TranscriptConfiguration` fed to `TranscriptList` with header/composer/IME insets, jump requests and the jump button, every `TranscriptAction` → the same-named `SessionFeature` action (Share opens the share sheet), Working line via `TranscriptSlots`, image-link-previews layout tuning, timed run events, WorkingIndicator/WorkingLineRow/SyncingSessionTitle/CachedImage (`TranscriptImageCache` still a media seam). |
| `OpenCode/Session/SessionView+Attachments.swift` | 543 | `SessionViewAttachments.kt` | partial: map snapshot in location card (placeholder), drag-and-drop |  |
| `OpenCode/Session/SessionView+Composer.swift` | 1153 | `SessionViewComposer.kt`, `seams/ViewComposerSeams.kt` | partial: call composer swap/tray, request panel growing out of the composer (RequestDockView), autocomplete (experimental), glass hand-offs, image/file paste & drop; expand animates height only | Text field is an isolated stand-in (`SessionComposerTextField`) until :feature:composer's input merges. |
| `OpenCode/Session/SessionView+ComposerControls.swift` | 489 | `SessionViewComposerControls.kt` | partial: ComposerAgentChip (Agent Selection experiment), call tray variants |  |
| `OpenCode/Session/SessionView+Header.swift` | 464 | `SessionViewHeader.kt` | done | … menu → M3 dropdown (Export as inline sub-list); iPad sidebar n/a. |
| `OpenCode/Session/SessionView+ModelPicker.swift` | 859 | `SessionViewModelPicker.kt`, `seams/PickersSeams.kt` | partial: Grow-from-Composer morph (that style shows the Sheet picker) | Also ports Composer/ModelPicker ModelPickerSheet + ShowAllModelsRow views. Detents → partial/expanded, 800dp/s flick closes. |
| `OpenCode/Session/SessionView+PhotoSheet.swift` | 571 | `SessionViewPhotoSheet.kt` | partial: Plus Menu Sheet experiment grid/detents/flights (off by default) | System Photo Picker (≤4). |
| `OpenCode/Session/SessionView.swift` | 1030 | `SessionView.kt`, `seams/ViewMainSeams.kt`, `assets/provider-icons/*.svg` | partial: Raise to Talk + live-socket warmer, call cards/tray/floating call (voice not ported), `.requests` sheet (RequestReviewView unowned), + menu Camera/Paste rows, image/file viewers (media) | Provider icons via Coil SVG. `markVisibleSession` → `SessionNotificationsClient.setVisibleSession` on scene, visibility, cover and session changes, false on dispose. |
| `OpenCode/Session/ShellOutputFeature.swift` | 273 | `ShellOutputFeature.kt` | done | Adds TaskEnded/ShareOutputTapped actions (Android lifecycle/share). |
| `OpenCode/Session/ShellOutputView.swift` | 369 | `ShellOutputView.kt` | done | UITextView → LazyColumn keyed by byte offset; 32dp follow rule. |
| `OpenCode/Session/SinceYouLeft.swift` | 643 | `SinceYouLeft.kt` | partial: no on-device model on Android (live client unavailable → structuralFallback); SinceYouLeftLab (debug fixture) not ported; row grows with animateContentSize, not ArrivingText |  |
| `OpenCode/Session/TitleBreathing.swift` | 590 | `header/TitleBreathing.kt` | done | Shipped Breathing look ported exactly; Shimmer/Phosphor/Wave/TitleLoadingReplay are Developer Mode tuning looks (n/a). |
