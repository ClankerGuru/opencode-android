package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.newMessagesDividerOn: WhatsNewEntry
    get() = feature(
        id = "new-messages-divider-on",
        day = "2026-10-24",
        title = "New Messages Divider",
        symbol = "text.line.first.and.arrowtriangle.forward",
        tint = Tint.blue,
        summary = "When a reply that arrived while you were away is longer than the screen, the session opens on a New divider at its start.",
        details = listOf(
            "A reply that fits on screen opens at the end, with no divider.",
            "Replies that stream in while you watch are never marked.",
            "Reading down to the end clears the divider.",
            "Turn it off in Settings › Sessions.",
        ),
        example = "Ask for a long reply, switch to another app until it finishes, then come back.",
    )
