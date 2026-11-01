package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.siriShortcutsSpotlight: WhatsNewEntry
    get() = feature(
        id = "siri-shortcuts-spotlight",
        day = "2026-10-08",
        title = "App Actions and Shortcuts",
        symbol = "square.grid.2x2",
        tint = Tint.purple,
        summary = "Start a call or a session, or send a message, from Google Assistant’s App Actions, app shortcuts, or Quick Settings.",
        details = listOf(
            "Hold the app icon for your recent sessions.",
        ),
        example = "Say “Hey Google, start a voice call in OpenCode”",
    )
