package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.alwaysNotify: WhatsNewEntry
    get() = feature(
        id = "always-notify",
        day = "2026-10-09",
        title = "Notify When Done",
        symbol = "bell.badge",
        tint = Tint.red,
        summary = "Get a notification when a session you started here finishes, with a short summary of what happened.",
        details = listOf(
            "Turn on Always Notify in a session’s sheet to hear about every reply, even ones started on your computer.",
            "Tap a notification to open its session.",
        ),
        example = "Send a message, then lock your phone",
        tryIt = Destination.notifications,
    )
