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
3. Re-run the inventory if iOS adds files: the `find OpenCode/<Area> -name '*.swift' -o -name
   '*.metal'` list must match the table.
