# Parity: app

Module: `:app`. iOS folder: `OpenCode/App/`. 11 files, 3,638 lines.

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`.

| iOS file | Lines | Kotlin file(s) | Status | Notes |
| --- | ---: | --- | --- | --- |
| `OpenCode/App/AppFeature.swift` | 1248 | `app/.../AppFeature.kt` | partial | Done: state shape, launch restore of the saved server (`State.initial()` = iOS `init()`, then `.task` reconnects), manual entry (`connectButtonTapped`, `addServerTapped`, `cancelEditingTapped`, `serverTapped`, `returnToServerTapped`), `connect` (concurrent candidate probe preferring the best `reachabilityRank`), `healthResponse`, `homeResponse`, `FailureContext` (+ `safeTarget`, `serverLabel`, `detailText`), `errorReport`, `copyTextTapped`, Home's Servers delegate → `.picking`. Todo: pairing (`scanPairingCodeTapped`, `pastePairingCodeTapped`, `pairingCodeScanned`, `pairingURLOpened`, `PendingPairing` confirm/cancel, `pairingRedeemed`, pasted pair links in the address field), `editServerTapped`/`serverDeleted`/`forgetIdentity`, `keepsHome(switchingTo:)` + switch confirmation toast, notifications (`notificationOpened`, `notificationSessionLoaded`), system actions (`systemActionsTask`, `systemActionReceived`, `systemSessionLoaded`), shares (`pendingSharesRequested`, `sharesReceived`), `serverTitle` presentation, lab launch environment, `keepsScreenAwake`, cold-launch readiness, persisting Home snapshots (`cachedHomeSnapshots`); the legacy single-snapshot migration is n/a (no earlier Android builds) |
| `OpenCode/App/AppFeature+Migrations.swift` | 45 | — | n/a | moves preferences saved by earlier iOS builds (Realtime → GPT-Live engine, Model Picker switches, Console sign-in); Android has no earlier builds. The flag keys stay in `SharedKeys` for parity |
| `OpenCode/App/AppFeature+Servers.swift` | 174 | `app/.../AppFeature.kt` (`withDefaultScheme`) | partial | `withDefaultScheme` done; `keepsHomeBehindSheet`, `selectServer`, `beginNewServer` (draft restore done inline), `cancelConnection` (simplified) todo |
| `OpenCode/App/AppFeature+Shares.swift` | 37 | — | todo | with `:feature:system` share target |
| `OpenCode/App/AppFeature+Windows.swift` | 92 | — | n/a | iPad multi-window routing; Android runs one activity (`launchMode=singleTask`) |
| `OpenCode/App/AppView.swift` | 929 | `app/.../AppView.kt` | partial | Done: Home vs connection routing, connection screen copy (wordmark, intro, setup steps, copy commands, saved servers, error card with Phase/Server/Attempt + Copy Error Report, Enter a Server Manually, Scan Pairing Code), New Server/Edit Server sheet (Name, Server address, Username, Password with show/hide, Connect / Save and connect, hint copy). Todo: headers editor rows, Paste row + paste error, edit/remove saved server (+ "Remove Server" confirmation copy), pairing confirmation dialog, error alerts (“Couldn’t Add Shared Items”, “Couldn’t Open Session”, “Couldn’t Pair with Server”), server switch toast, deep links (`onOpenURL` → intent filters), backtick-code formatting in messages |
| `OpenCode/App/LaunchBloom.swift` | 251 | `MainActivity` (`installSplashScreen`) | partial | blank cold-launch cover → AndroidX SplashScreen; keep-on-screen until content ready is todo |
| `OpenCode/App/OpenCodeApp.swift` | 212 | `app/.../OpenCodeApplication.kt`, `MainActivity.kt` | partial | app entry, dependency root, launch log done. Quick actions (→ app shortcuts), push token registration (→ `:feature:system` FCM bridge), lab launch flags (`-lab-*`, `OPENCODE_LAB_*` → debug intent extras), `LabVoiceKeys` todo |
| `OpenCode/App/SceneWindows.swift` | 501 | — | n/a | UIKit scene/window installation; Compose `setContent` in one activity |
| `OpenCode/App/ViewSingletons.swift` | 11 | — | n/a | SwiftUI view-singleton anchors |
| `OpenCode/App/WindowRoster.swift` | 138 | — | n/a | iPad window roster |

## Android-only

| Kotlin file | Why |
| --- | --- |
| `app/.../MainActivity.kt` (`RootStore`) | the root `Store` lives in a retained `ViewModel` so configuration changes keep state and effects |
| `app/src/debug/res/xml/network_security_config.xml` | cleartext to `10.0.2.2`/localhost in debug builds only (session-lab fixture) |
