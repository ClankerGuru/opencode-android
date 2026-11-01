package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.appIconBadge: WhatsNewEntry
    get() = feature(
        id = "app-icon-badge",
        day = "2026-10-12",
        title = "Badge on the App Icon",
        symbol = "app.badge",
        tint = Tint.red,
        summary = "The app icon shows how many sessions are unread or waiting on you, the same count as Catch Up.",
        details = listOf(
            "Muted sessions don’t count.",
            "It needs notifications turned on.",
        ),
        example = "Leave a session waiting on a question, then go to the Home Screen",
        tryIt = Destination.notifications,
    )
