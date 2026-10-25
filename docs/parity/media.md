# Parity: media

Module: `:feature:media`. iOS folders: `OpenCode/Media/`, `OpenCode/Browser/`. 29 files, 11,244 lines.

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`. Fill in the Kotlin file(s) as you port; see [README.md](README.md).

| iOS file | Lines | Kotlin file(s) | Status | Notes |
| --- | ---: | --- | --- | --- |
| `OpenCode/Browser/BrowserFeature.swift` | 172 | feature/media/.../browser/BrowserFeature.kt | done | URLs are `String`; `browserHost()` gives Unicode hosts |
| `OpenCode/Browser/BrowserLinkPolicy.swift` | 85 | feature/media/.../browser/BrowserLinkPolicy.kt | done | lab loopback switch: env or `debug.opencode.lab_browser_loopback` (debuggable only) |
| `OpenCode/Browser/BrowserPageContext.swift` | 92 | server/.../model/Composer.kt | n/a: already in :server | `OpenCodePageContext`, `pageBlock`, `removingPageBlock`, `PromptContext.page` |
| `OpenCode/Browser/BrowserView.swift` | 475 | feature/media/.../browser/BrowserView.kt | partial: page padded clear of bars (WebView has no obscured insets); back gesture is wired by the session stack via `BrowserWebViews.shared.canGoBack(id)` / `browserBackAction` / `backProgress` (no BackHandler in media); `WorkingIndicator` is a slot from session |  |
| `OpenCode/Browser/BrowserWebViews.swift` | 368 | feature/media/.../browser/BrowserWebViews.kt | partial: no non-persistent data store on Android (dedicated `opencode-browser` profile cleared per run); reader JS runs in the page world (no content worlds); subframe initial loads bypass the navigation policy | KVO → WebViewClient/WebChromeClient; downloads → DownloadListener; renderer crash recreates in place; long-press link → our menu |
| `OpenCode/Browser/LinkMenu.swift` | 635 | feature/media/.../browser/LinkMenu.kt | partial: no link drag-and-drop; session-chip press shape approximated with line rects (`SessionChipMetrics` is transcript's) | `LinkHitLayout` on `TextLayoutResult`; `LinkMenuPopup`; `UniversalLinkClient` = `FLAG_ACTIVITY_REQUIRE_NON_BROWSER`; `OpenURLClient` (Custom Tab for “Open in Safari”, iOS wording kept); core `PasteboardClient` |
| `OpenCode/Media/AgentFiles/AgentFileLoader.swift` | 432 | feature/media/.../agentfiles/AgentFileLoader.kt | done | downloads in `cache/AgentFiles/<hash>/` with validators sidecar + 304 reuse; `StreamingFileLoader` = Media3 `DataSource` (206 only); errors never include host text |
| `OpenCode/Media/AgentFiles/AgentFileReference.swift` | 354 | `server/.../model/AgentFileReference.kt` | done | moved down to `:server` so `OpenCodeToolCall.fileReference`, the transcript parser and the media viewer share one copy (transcript and media ports reconciled on merge); UTType lookups → fixed extension→MIME table (`mimeForExtension`) plus extension sets for UTType conformance fallbacks; `init?(linkURL:)` → `fromLinkURL`; uses `:core` `AttachmentFileType` (the `:server` duplicate was removed) |
| `OpenCode/Media/AgentFiles/AgentFileViewer.swift` | 1181 | feature/media/.../agentfiles/AgentTextDocument.kt, AgentFileViewerModel.kt, AgentFileViewer.kt, AgentCodeView.kt | partial: Markdown rendering is an `AgentFileMarkdown` slot (renderer is transcript's); Picture-in-Picture; no message when no app opens a document; own Compose player controls instead of AVKit | Quick Look → `PdfRenderer` + FileProvider ACTION_VIEW (“Open in Another App”); images > 24 MP downsampled |
| `OpenCode/Media/AgentFiles/AgentImageBento.swift` | 352 | feature/media/.../agentfiles/AgentImageBento.kt | done | Compose `Layout`; tunables baked (no Tuning Panel) |
| `OpenCode/Media/AgentFiles/AgentImageGallery.swift` | 637 | feature/media/.../agentfiles/AgentImageGallery.kt, _stub_images.kt | partial: no `transcriptDragSource` on tiles (transcript-owned); `AgentFileCards` is a minimal stub (iOS `FileAttachmentViews.swift` is composer's) | environment values → CompositionLocals |
| `OpenCode/Media/AgentFiles/AgentImagePreviews.swift` | 1268 | feature/media/.../agentfiles/AgentImagePreviews.kt, AgentImagePreviewsReads.kt | done | iOS `AgentImagePreviews.Key` → `AgentImageKey`; constrained refusal = `NetworkPathMonitor.isConstrained`; lab deadline/size-cache switches not ported |
| `OpenCode/Media/AgentFiles/AgentImageVisibility.swift` | 394 | feature/media/.../agentfiles/AgentImageVisibility.kt | done | markers = `onGloballyPositioned`; viewport via `LocalAgentImageViewport` |
| `OpenCode/Media/AgentFiles/MediaPlaybackAudio.swift` | 25 | feature/media/.../agentfiles/MediaPlaybackAudio.kt | done | own audio-focus holder count (AudioSessionOwner is voice's, above media); joins calls without requesting focus |
| `OpenCode/Media/ImageLinkPreviewClient.swift` | 162 | feature/media/.../ImageLinkPreviewClient.kt | done |  |
| `OpenCode/Media/LinkPreviews/LinkPreview.swift` | 276 | feature/media/.../linkpreviews/LinkPreview.kt | done | `NSDataDetector` → `Patterns.WEB_URL`; extractor takes `LinkTextBlock`/`LinkTextRun` (Markdown block type is transcript's) |
| `OpenCode/Media/LinkPreviews/LinkPreviewClient.swift` | 613 | feature/media/.../linkpreviews/LinkPreviewClient.kt | done | lab switches via debug `debug.opencode.lab_link_preview*` system properties |
| `OpenCode/Media/LinkPreviews/LinkPreviewStore.swift` | 469 | feature/media/.../linkpreviews/LinkPreviewStore.kt | done | `LocalLinkPreviewStore`, `LocalLinkPreviewRevision` |
| `OpenCode/Media/LinkPreviews/LinkPreviewTransport.swift` | 49 | feature/media/.../linkpreviews/LinkPreviewTransport.kt | done | OkHttp: no cookies/cache/redirects, byte caps, constrained interceptor |
| `OpenCode/Media/LinkPreviews/RichLinkPreview.swift` | 224 | feature/media/.../linkpreviews/RichLinkPreview.kt | done | User-Agent `OpenCode-Android-LinkPreview` |
| `OpenCode/Media/LinkPreviews/SiteIcon.swift` | 301 | feature/media/.../linkpreviews/SiteIcon.kt | done | ICO: platform decoder picks the frame |
| `OpenCode/Media/MediaPresentation.swift` | 847 | feature/media/.../MediaPresentation.kt | partial: document (`.zoom`) style has no interactive swipe-down | `MediaZoomSources`, `MediaThumbnail`, `mediaZoomSource`, `MediaDismissDrag` exact, `MediaViewerHost`; back via OnBackInvokedDispatcher |
| `OpenCode/Media/MediaTransitionTrace.swift` | 79 | feature/media/.../MediaTransitionTrace.kt | done | AppLog.perf; `--ez lab-media-trace true` |
| `OpenCode/Media/Mermaid/MermaidDiagramView.swift` | 231 | feature/media/.../mermaid/MermaidDiagramView.kt | done |  |
| `OpenCode/Media/Mermaid/MermaidRenderer.swift` | 363 | feature/media/.../mermaid/MermaidRenderer.kt | done | one offscreen WebView, assets/mermaid; cached as SVG (iOS PDF) |
| `OpenCode/Media/Mermaid/MermaidSyntax.swift` | 79 | feature/media/.../mermaid/MermaidSyntax.kt | done |  |
| `OpenCode/Media/SyntaxHighlighter.swift` | 391 | feature/media/.../SyntaxHighlighter.kt | done | public; colour-free tokens + `Palette.current`; CRLF ends a line |
| `OpenCode/Media/TranscriptImageViewer.swift` | 352 | feature/media/.../TranscriptImageViewer.kt | done | MediaStore save; FileProvider share |
| `OpenCode/Media/ZoomableImagePage.swift` | 338 | feature/media/.../ZoomableImagePage.kt | done | `MediaZoomState`; fling stops flush instead of bouncing |

## Moved here from other folders

| iOS file / type | Kotlin file(s) | Status | Notes |
| --- | --- | --- | --- |
| `OpenCode/Core/DecodedImageCache.swift` | `feature/media/.../MediaFoundation.kt` (`DecodedImageCache`) | done | 48 MB `rowBytes × height` LRU, ≤ 64 entries, SHA-256 keys; `onTrimMemory`/`onLowMemory` purge = memory warning; Coil's own memory cache holds Coil-loaded images |
| `TranscriptImagePreview` (`Transcript/TranscriptEntryView.swift`) | `feature/media/.../MediaFoundation.kt` | done | moved down: viewer, gallery and Mermaid build it; `Page.image` is an Android `Bitmap` |
| `TranscriptMetrics` constants (`Transcript/TranscriptEntryView.swift`) | `feature/media/.../MediaFoundation.kt` | done | tunable defaults (gutter 16, card radius 14); transcript should reuse rather than redeclare |
| `ExperimentalFeature(s)` (`Settings/ExperimentalFeatures.swift`) | `core/.../dependencies/ExperimentalFeatures.kt` | done | moved down (shared commit); Settings owns titles/summaries |
| `SessionLink` URL helpers (`Transcript/SessionLinks.swift`) | `server/.../model/SessionLink.kt` | done | moved down (shared commit); Transcript owns the rich-text marking |

