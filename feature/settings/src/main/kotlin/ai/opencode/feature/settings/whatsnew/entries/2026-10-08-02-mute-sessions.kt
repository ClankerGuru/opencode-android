package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.muteSessions: WhatsNewEntry
    get() = feature(
        id = "mute-sessions",
        day = "2026-10-08",
        title = "Mute Sessions",
        symbol = "bell.slash",
        tint = Tint.indigo,
        summary = "Muted sessions stay in your list but never join Catch Up or show an unread dot.",
        details = listOf(
            "Hold Catch Up to mark everything as read. Sessions waiting on you stay.",
        ),
        example = "Hold a session in the drawer and tap Mute",
    )
