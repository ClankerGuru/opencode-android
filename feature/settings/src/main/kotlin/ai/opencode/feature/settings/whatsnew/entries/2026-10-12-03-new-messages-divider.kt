package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.newMessagesDivider: WhatsNewEntry
    get() = feature(
        id = "new-messages-divider",
        day = "2026-10-12",
        title = "Pick Up Where You Left Off",
        symbol = "line.horizontal.star.fill.line.horizontal",
        tint = Tint.orange,
        summary = "Coming back to a session opens it at the bottom, with what arrived while you were away already there. To open a long backlog at a New line instead, turn on New Messages Divider in Settings › Sessions.",
        details = listOf(
            "Sessions you’re likely to open refresh as the drawer opens, so they’re ready when you tap.",
            "Switching back keeps a session’s background work and context gauge while it refreshes.",
        ),
        example = "Start a long task, switch to another session, and come back once it has replied",
    )
