# Parity tracking

The Android app must behave exactly like the iOS app (`/Users/vogel/dev/opencode-ios`): same
features, flows, states, copy, server contract, retry policy, logging privacy, haptics, timings
and thresholds. The Swift code is the source of truth; iOS docs drift.

Each area has one file listing **every** iOS source file it owns:

| File | Module | iOS folders |
| --- | --- | --- |
| [app.md](app.md) | `:app` | `OpenCode/App/` |
| [core.md](core.md), [core-ui.md](core-ui.md) | `:core` | `OpenCode/Core/` |
| [server.md](server.md) | `:server` | `OpenCode/Server/` (+ server-facing Core files) |
| [session.md](session.md) | `:feature:session` | `OpenCode/Session/` |
| [transcript.md](transcript.md) | `:feature:transcript` | `OpenCode/Transcript/` |
| [composer.md](composer.md) | `:feature:composer` | `OpenCode/Composer/` |
| [drawer.md](drawer.md) | `:feature:drawer` | `OpenCode/Drawer/` |
| [settings.md](settings.md) | `:feature:settings` | `OpenCode/Settings/` |
| [voice.md](voice.md) | `:feature:voice` | `OpenCode/Voice/` |
| [media.md](media.md) | `:feature:media` | `OpenCode/Media/`, `OpenCode/Browser/` |
| [system.md](system.md) | `:feature:system` | `OpenCode/System/`, `OpenCodeShare/`, `OpenCodeWidgets/`, `OpenCodeNotifications/` |
| [devtools.md](devtools.md) | `:devtools` | `OpenCode/Tuning/`, `OpenCode/Diagnostics/` (reference only) |

## Status values

- `todo`: not started.
- `sync: <old status>`: the iOS file changed after the synced commit; port the change, then
  restore the status (see [Keeping up with iOS](#keeping-up-with-ios)).
- `removed in <sha>: <old status>`: iOS deleted the file in that commit; delete or re-home the
  Kotlin, then drop the row.
- `partial: <what is missing>`: some behaviour ported; say exactly what is not.
- `done`: every behaviour in the Swift file exists in Kotlin (logic, copy, constants, logging,
  haptics, error states). Visuals are Material equivalents, not pixel copies.
- `n/a: <reason>`: iOS-only plumbing (UIKit hosting seams, Liquid Glass helpers, Metal
  scaffolding, App Group, XCUITest lab hooks). Always say what it maps to on Android, or why
  nothing is needed.

## Rules

1. Update the row in the same commit as the port. One row per Swift file; when one Swift file
   becomes several Kotlin files, list them all; when several Swift files fold into one Kotlin
   file, point each row at it.
2. A type a lower module needs moves down (to `:core`, `:server` or the lowest feature that uses
   it); note the move in both areas' files. Never add an upward or cyclic module dependency.
3. `scripts/ios-sync.py check` must pass: every iOS source file at the synced (or target) commit
   has a row, and every row's Lines matches iOS.

## Keeping up with iOS

[sync.json](sync.json) records the iOS commit these tables describe (`synced`) and, during a
sync, the commit being caught up to (`target`). The first port was taken from iOS `062f3732`
(2026-10-24); every Lines count in these tables matched that commit exactly.
`scripts/ios-sync.py` (iOS checkout in `$OPENCODE_IOS`) moves it forward:

1. `scripts/ios-sync.py status --to origin/main`: what changed, per area.
2. `scripts/ios-sync.py plan --to <sha>` writes `sync/<from>-<to>.md`: every changed iOS file
   with its diff size, the iOS commits that touched it and the Kotlin it maps to, plus the iOS
   tests to port. Port from the net diff (`scripts/ios-sync.py diff <ios path>`), not commit by
   commit: iOS often lands a feature and reworks it a few commits later.
3. `scripts/ios-sync.py stale` marks the rows (`sync:`, `todo`, `removed in`) and records the
   target. Commit the plan and the marked tables together.
4. Port area by area, as in the first port: each commit brings a set of rows and their tests
   back to `done`/`partial`/`n/a` and ticks them in the plan.
5. When `scripts/ios-sync.py check` is clean with nothing waiting, `scripts/ios-sync.py advance`
   makes the target the synced commit.

## Tests

Every iOS unit test (`OpenCodeTests/`, 185 files, 3,126 `@Test`s, of which 173 files hold tests and
the rest are support) is either ported into the module that owns its code or deleted with a reason
in that module's `## Tests` section. A test ported in halves (say a reducer half and a wire half)
counts in each module. In a `## Tests` row, "→ `<module>`" means the test is ported in that module
under the same name; anything not ported anywhere is listed as deleted with its reason. Checked on
2026-10-26 by matching every iOS test name against the Kotlin test functions: 2,858 iOS tests ported,
268 deleted, none unaccounted for.

| Module | iOS test files covered | iOS tests ported | Deleted with reason | JVM tests (Gradle) |
| --- | ---: | ---: | ---: | ---: |
| `:app` | 21 | 125 | 27 | 125 |
| `:core` | 18 | 109 | 72 | 113 |
| `:server` | 35 | 225 | 3 | 244 (2 opt-in live checks skipped) |
| `:devtools` | 4 | 14 | 10 | 14 |
| `:feature:media` | 15 | 175 | 14 | 180 |
| `:feature:voice` | 30 | 444 | 16 | 511 |
| `:feature:system` | 9 | 88 | 2 | 89 |
| `:feature:transcript` | 31 | 268 | 43 | 276 (1 env-gated metric skipped) |
| `:feature:composer` | 36 | 245 | 28 | 245 |
| `:feature:settings` | 17 | 98 | 10 | 98 |
| `:feature:session` | 58 | 706 | 41 | 719 |
| `:feature:drawer` | 49 | 422 | 12 | 424 |
| **Total** | 173 distinct | 2,858 distinct | 268 | 3,023 |

"Deleted" counts whole iOS tests, each charged to the module whose section records it: UIKit and
SwiftUI layout probes, Tuning Panel and Mac playgrounds, MetricKit, Touch Probe, Team Feedback and
Scrawl, Metal shaders, ActivityKit authorization, App Group, Info.plist checks, env-gated benchmarks,
and migrations from iOS builds Android never had. Sub-asserts dropped inside a ported test aren't
counted. A JVM count can exceed the iOS count because parameterized iOS tests and framework halves
(`…FrameworkTests` under Robolectric) are separate Kotlin tests.

Robolectric 4.16 (test-only) runs the tests that need Android framework classes (Bitmap/ImageDecoder
pixels, `Intent`, `android.icu`, `Patterns`, `LruCache`, `MimeTypeMap`, AppSearch, the main looper,
`Vibrator`) in `:app`, `:core`, `:feature:media`, `:feature:voice`, `:feature:system`,
`:feature:transcript`, `:feature:composer`, `:feature:settings`, `:feature:session` and `:feature:drawer`.
