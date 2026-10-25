# Parity: server

Module: `:server` (package `ai.opencode.server`, sub-packages `.model`, `.retry`, `.live`, `.internal`).
iOS folder: `OpenCode/Server/`, plus the server-facing Core files `ServerID.swift`,
`ServerIDClient.swift`, the server-typed parts of `SharedKeys.swift`, and the `logSummary`
extensions at the bottom of `AppLog.swift`. Paths below are relative to
`server/src/main/kotlin/ai/opencode/server/`.

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`.

## OpenCode/Server

| iOS file | Kotlin file(s) | Status | Notes |
| --- | --- | --- | --- |
| `AGENTS.md` | this file | n/a: docs | Its rules (one retry layer, V2-only routes, `location[directory]` fan-out, idempotency) hold in the port |
| `OpenCodeClient.swift` | `OpenCodeClient.kt` | done | `data class` of suspend function properties with `companion object Key : DependencyKey`; tests override with `copy(...)`. `testValue` throws `AssertionError("Unimplemented: OpenCodeClient.<name>")` except the endpoints iOS's `testValue` stubs inert. `cachedMarkdownImage`/`markdownImage` return bytes, not `UIImage` (decode in UI) |
| `OpenCodeClient+Capabilities.swift` | `model/PendingRequests.kt` (MCP/account/OAuth models), `live/Payloads.kt`, `live/LiveCatalog.kt` | done | `pathComponent` percent-encodes like `urlPathAllowed − "/"`; OAuth `url` is a `java.net.URI` |
| `OpenCodeClient+Catalog.swift` | `live/LiveCatalog.kt` | done | Same routes, timeouts (20/15/30/60 s), idempotency; model catalog shared through `OpenCodeReadCache` |
| `OpenCodeClient+Connection.swift` | `live/LiveConnection.kt` | done | `/api/info` probe (`.once`, connection-probe evidence), pairing redemption races every redeem origin, first success cancels the rest; same failure copy |
| `OpenCodeClient+Events.swift` | `OpenCodeServerEvent.kt`, `EventBatcher.kt`, `live/LiveEvents.kt` | done | Hand-rolled `data:` line reader over OkHttp (as iOS parses `bytes.lines`), 16 MiB frame cap, `_tag` failure frames, same handled-type set and event mapping, 128-event buffer → `EventStreamOverflow`. `EventBatcher` (75 ms on the `Clock` dependency, 256-event caps) is a plain object; `MarkdownStreamClient.prepare` and `AgentActivityLedger.record` become `EventBatcherHooks` (installed by transcript/session) |
| `OpenCodeClient+Family.swift` | `live/LiveFamily.kt` | done | Home snapshot (3 parallel reads), active ancestry (batches of 6, permits of 2), family BFS (6 at a time, cap 100, in-order commit), running-descendant search (24 lookups) |
| `OpenCodeClient+Files.swift` | `live/LiveFiles.kt` | done | Upload: `/api/info` tmp → `/api/experimental/fs/write`, `.send` policy, progress about once per percent (≥ 64 KB). Markdown images return validated bytes (≤ 25 MB) via `MarkdownImageCache` (byte LRU, 32 MB); no bitmap decode here. Directory listing resolves/normalises paths, hidden last, numeric-aware order |
| `OpenCodeClient+Models.swift` | `model/Models.kt`, `model/Project.kt` | done | `OpenCodeSession.updatedAt` is `Instant` (ms epoch double → nanos kept). `OpenCodeDelivery.symbolName` (SF Symbols) n/a |
| `OpenCodeClient+Operations.swift` | `OpenCodeClientOperations.kt` | done | Extensions on `OpenCodeClient`; `transcriptWithContextUsageAndCursor` returns `OpenCodeTranscriptWindow` (iOS returned `SessionFeature…PageCursor`); task locals → `PromptContext` / `TranscriptContextCapture.current` |
| `OpenCodeClient+Payloads.swift` | `live/Payloads.kt` | done | Synthesized `Decodable`s → `@Serializable`; custom `init(from:)` (`MessagePayload`, `ContentPartPayload`, `ToolContentPayload`, `ModelRefPayload`, `SessionMetadataPayload`, `PairingSessionPayload`) → JSON-element walkers with identical type-gated fields and fallbacks |
| `OpenCodeClient+Prompts.swift` | `live/LivePrompts.kt` | done | Same `PromptBody`/metadata shape, nil fields omitted; 409/ambiguous inbox PATCH reads the inbox, never treats 409 as success |
| `OpenCodeClient+Requests.swift` | `live/LiveRequests.kt` | done | 60 s location-boot timeout; `"global"` forms routed by `PromptContext.formLocationDirectory` |
| `OpenCodeClient+Sessions.swift` | `live/LiveSessions.kt` | done | `/wait` long poll loop (25 s, 12 fast passes, `.sessionWait` backoff on the `Clock` dependency); metadata PATCH merges JSON |
| `OpenCodeClient+SharedEvents.swift` | `live/SharedEvents.kt` | done | `SharedEventStreams`/`SharedReads` ported; gated by `MultipleWindows.isSupported` (default false = iPhone behaviour) |
| `OpenCodeClient+Shells.swift` | `live/LiveRequests.kt` | done | |
| `OpenCodeClient+Transcript.swift` | `live/LiveTranscript.kt` | partial: Markdown stays text | Same item IDs, bounding (20 000 chars / 100 items), tool images (16 / 20 MB / 32 MB), context usage, synthetic notices, reply span. **Deviation:** `assistantMarkdown` and compaction summaries carry trimmed Markdown text; iOS parses `[OpenCodeMarkdownBlock]` at decode. The Markdown model belongs to `:feature:transcript`. `VoiceHandoff.parse` is mirrored as internal `isVoiceDelegation` (validity check only). `LabMarks` n/a |
| `OpenCodeClient+Transport.swift` | `live/LiveTransport.kt` | done | `perform`/`request`/`decoded` with the same copy, `Accept`, correlation headers (`X-OpenCode-Op/Req/Attempt`), per-request idle timeout (connect/read/write), redirect control, `ServerContact` wakes, connection-probe DNS evidence. `LabNetworkMetricsRecorder`/`DiagnosticsRecorder`/`NetworkObservation` n/a: Diagnostics subsystem (lab metrics) not ported here |
| `OpenCodeClientError.swift` | `OpenCodeClientError.kt` | done | `data class … : Exception`; `retryAfter` parses seconds or HTTP dates |
| `ConnectionFailureDetail.swift` | `ConnectionFailureDetail.kt` | done | JVM exceptions mapped to iOS `URLError` codes (`UrlErrorCode`) so labels match; DNS via `InetAddress` within 1 s. Tailnet advice copy says "Private DNS or any DNS-changing VPN profile" instead of iCloud Private Relay (no Android equivalent) |
| `NetworkRetry.swift` | `retry/NetworkRetry.kt`, `retry/ServerContact.kt`, `retry/NetworkPath.kt`, `retry/RetrySignals.kt` | done | Policies, verdicts, backoff, budgets identical. `@TaskLocal`s → `TaskLocal` coroutine elements (`withRetryPolicy(RetryPolicy.once) { }`). Sleeps on the `Clock` dependency. `TransportFailure` classifies JVM exceptions through the URLError table (refused/DNS/unroutable/TLS-handshake → notSent; resets, EOF, other IO → interrupted; timeouts → timedOut; cancel/cert → none); iOS ATS -1022 maps to Android's cleartext refusal (`UnknownServiceException`). `NWPathMonitor` → default-network callback (`isExpensive` = metered, `isConstrained` = Data Saver, low power = Battery Saver). App phases from activity start/stop counts (`AppPhases.install`). Cancellation surfaces as `CancellationException` (Kotlin), not the last error |
| `OpenCodePairingLink.swift` | `OpenCodePairingLink.kt` | done | JSON payload, `opencode.ai/pair#` page, `opencode(-ios)://pair` deep links, `/auth/connect/<code>`; URLs as `String` |
| `OpenCodePendingRequests.swift` | `model/PendingRequests.kt` | done | `filePreview` returns `Pair(file, patch)` |
| `OpenCodeReadCache.swift` | `live/OpenCodeReadCache.kt` | done | Locks + `Semaphore` permits; joined in-flight lookups, last waiter cancels |
| `OpenCodeServer.swift` | `OpenCodeServer.kt`, `internal/Urls.kt` | done | `authorize(Request.Builder)`; `endpoint` returns `HttpUrl`; `HostAddress` parses IPv4/IPv6 like `inet_pton`; plus `httpClient(base)` (OkHttp client that authorizes this server's origins, for image loaders/players). OkHttp rejects non-ASCII/control header values URLSession would send: such custom headers are skipped |
| `OpenCodeToolCall.swift` | `model/OpenCodeJSONValue.kt`, `model/OpenCodeToolCall.kt` | partial: no `fileReference` | `fileReference` needs `AgentFileReference` (`:feature:media`); `inputPath` is exposed for it. String helpers `nonEmpty`/`firstLine`/`bounded` are here |
| `OpenCodeTranscriptItem.swift` | `model/OpenCodeTranscriptItem.kt` | done | Sealed interface, `@Serializable` (cache); see Markdown deviation above |
| `OpenCodeTransport.swift` | `OpenCodeTransport.kt` | done | OkHttp pools; event stream 90 s idle timeouts |

## Server-facing Core files

| iOS file | Kotlin file(s) | Status | Notes |
| --- | --- | --- | --- |
| `Core/ServerID.swift` | `ServerID.kt` | done | Same time/counter/base62 layout |
| `Core/ServerIDClient.swift` | `ServerID.kt` (`ServerIDClient`) | done | `testValue` = `incrementing()` |
| `Core/SharedKeys.swift` (server-typed parts) | `ServerSharedKeys.kt` | done | `CachedHomeSnapshot`, `CachedTranscriptSnapshots` (eviction, priority, cap-encoding; mutators return copies), `TranscriptCachePersistence` + 500 ms writer at `filesDir/support/transcript-snapshots.json` (no protected-data blocking), keys `savedServer`, `savedServers` (keychain), `cachedHomeSnapshots`, `legacyCachedHomeSnapshot`; the transcript cache is `ServerSharedKeys.cachedTranscriptSnapshots()` (a `Shared` loaded from and written through the persistence); `remember(original)`, `rememberName()` |
| `Core/AppLog.swift` (`logSummary` extensions) | `OpenCodeClientError.kt`, `retry/NetworkRetry.kt` | done | `OpenCodeClientError.logSummary`, `RetryVerdict.logSummary`, `RetryPause.Kind.logSummary`; `Duration.logSummary` is in `:core` |

## Types ported into `:server` that iOS keeps elsewhere

Wire models the client decodes or encodes, needed below the feature modules: `OpenCodeFormValue`/
`OpenCodeFormRequest` (+ validation) from `Composer/FormModels.swift`; `OpenCodeFileReference`,
`OpenCodePromptPresentation`, `OpenCodeTranscriptFile`, `OpenCodeUploadEvent` from
`Composer/ComposerFileAttachment.swift`; `PastedText`/`PastedTextSummary` from
`Composer/PastedText.swift`; autocomplete skill/agent/command/file/mention types and
`choosable` from `Composer/AutocompleteModels.swift`, `AgentPicker.swift`;
`AttachmentFileType.sanitizedName`; `OpenCodePageContext` + page block from
`Browser/BrowserPageContext.swift`; `OpenCodeProjectIcon`/`ProjectMark` from
`Drawer/ProjectIcon.swift`; `ModelProviderGroup` titles from `Session/SessionView.swift`;
`TranscriptNoticeKind`/`AgentDisplayName` and `OpenCodeRunError`/`OpenCodeRunRetry` from
`Transcript/`; task locals `PromptPageContext`, `PromptNotification.callbackURL`,
`PromptAutocompleteMentions`, `PromptMessageID`, `FormLocationDirectory` → `model/Composer.kt`
`PromptContext`. Feature modules should use these rather than redeclare them.
