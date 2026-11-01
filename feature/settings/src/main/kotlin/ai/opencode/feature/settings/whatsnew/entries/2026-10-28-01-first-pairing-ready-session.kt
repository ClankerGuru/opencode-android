package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.firstPairingReadySession: WhatsNewEntry
    get() = smaller(
        id = "first-pairing-ready-session",
        day = "2026-10-28",
        title = "A Ready First Session",
        symbol = "checkmark.seal",
        summary = "Pairing your first computer now opens a new session in its most recent project, with a few questions to try, and a tap confirms you’re connected. A new install also starts with What’s New already read.",
    )
