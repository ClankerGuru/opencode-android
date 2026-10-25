# Parity: media

Module: `:feature:media`. iOS folders: `OpenCode/Media/`, `OpenCode/Browser/`. 29 files, 11,244 lines.

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`. Fill in the Kotlin file(s) as you port; see [README.md](README.md).

| iOS file | Lines | Kotlin file(s) | Status | Notes |
| --- | ---: | --- | --- | --- |
| `OpenCode/Browser/BrowserFeature.swift` | 172 | | todo | |
| `OpenCode/Browser/BrowserLinkPolicy.swift` | 85 | | todo | |
| `OpenCode/Browser/BrowserPageContext.swift` | 92 | | todo | |
| `OpenCode/Browser/BrowserView.swift` | 475 | | todo | |
| `OpenCode/Browser/BrowserWebViews.swift` | 368 | | todo | |
| `OpenCode/Browser/LinkMenu.swift` | 635 | | todo | |
| `OpenCode/Media/AgentFiles/AgentFileLoader.swift` | 432 | | todo | |
| `OpenCode/Media/AgentFiles/AgentFileReference.swift` | 354 | `server/.../model/AgentFileReference.kt` | done | moved down to `:server` so `OpenCodeToolCall.fileReference` can use it; UTType lookups → fixed extension→MIME table; `init?(linkURL:)` → `fromLinkURL` |
| `OpenCode/Media/AgentFiles/AgentFileViewer.swift` | 1181 | | todo | |
| `OpenCode/Media/AgentFiles/AgentImageBento.swift` | 352 | | todo | |
| `OpenCode/Media/AgentFiles/AgentImageGallery.swift` | 637 | | todo | |
| `OpenCode/Media/AgentFiles/AgentImagePreviews.swift` | 1268 | | todo | |
| `OpenCode/Media/AgentFiles/AgentImageVisibility.swift` | 394 | | todo | |
| `OpenCode/Media/AgentFiles/MediaPlaybackAudio.swift` | 25 | | todo | |
| `OpenCode/Media/ImageLinkPreviewClient.swift` | 162 | | todo | |
| `OpenCode/Media/LinkPreviews/LinkPreview.swift` | 276 | | todo | |
| `OpenCode/Media/LinkPreviews/LinkPreviewClient.swift` | 613 | | todo | |
| `OpenCode/Media/LinkPreviews/LinkPreviewStore.swift` | 469 | | todo | |
| `OpenCode/Media/LinkPreviews/LinkPreviewTransport.swift` | 49 | | todo | |
| `OpenCode/Media/LinkPreviews/RichLinkPreview.swift` | 224 | | todo | |
| `OpenCode/Media/LinkPreviews/SiteIcon.swift` | 301 | | todo | |
| `OpenCode/Media/MediaPresentation.swift` | 847 | | todo | |
| `OpenCode/Media/MediaTransitionTrace.swift` | 79 | | todo | |
| `OpenCode/Media/Mermaid/MermaidDiagramView.swift` | 231 | | todo | |
| `OpenCode/Media/Mermaid/MermaidRenderer.swift` | 363 | | todo | |
| `OpenCode/Media/Mermaid/MermaidSyntax.swift` | 79 | | todo | |
| `OpenCode/Media/SyntaxHighlighter.swift` | 391 | | todo | |
| `OpenCode/Media/TranscriptImageViewer.swift` | 352 | | todo | |
| `OpenCode/Media/ZoomableImagePage.swift` | 338 | | todo | |

## Moved here from other folders

| iOS file / type | Kotlin file(s) | Status | Notes |
| --- | --- | --- | --- |
| `OpenCode/Core/DecodedImageCache.swift` | `feature/media/.../MediaFoundation.kt` (`DecodedImageCache`) | done | 48 MB `rowBytes × height` LRU, ≤ 64 entries, SHA-256 keys; `onTrimMemory`/`onLowMemory` purge = memory warning; Coil's own memory cache holds Coil-loaded images |
| `TranscriptImagePreview` (`Transcript/TranscriptEntryView.swift`) | `feature/media/.../MediaFoundation.kt` | done | moved down: viewer, gallery and Mermaid build it; `Page.image` is an Android `Bitmap` |
| `TranscriptMetrics` constants (`Transcript/TranscriptEntryView.swift`) | `feature/media/.../MediaFoundation.kt` | done | tunable defaults (gutter 16, card radius 14); transcript should reuse rather than redeclare |
| `ExperimentalFeature(s)` (`Settings/ExperimentalFeatures.swift`) | `core/.../dependencies/ExperimentalFeatures.kt` | done | moved down (shared commit); Settings owns titles/summaries |
| `SessionLink` URL helpers (`Transcript/SessionLinks.swift`) | `server/.../model/SessionLink.kt` | done | moved down (shared commit); Transcript owns the rich-text marking |

