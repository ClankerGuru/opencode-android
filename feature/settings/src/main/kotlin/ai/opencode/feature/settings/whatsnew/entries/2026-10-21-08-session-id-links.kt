package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionIdLinks: WhatsNewEntry
    get() = feature(
        id = "session-id-links",
        day = "2026-10-21",
        title = "Session Links",
        symbol = "link",
        tint = Tint.blue,
        summary = "A session ID in a reply shows as that session’s title. Tap it to open the session.",
        details = listOf(
            "A subagent’s ID opens over the session you’re in, and Back returns.",
            "Hold one to copy its ID.",
            "An ID this server doesn’t know shows shortened.",
        ),
        example = "Ask the agent to name another session by its ID, then tap the title.",
        tryIt = Destination.Prompt("List my five most recent OpenCode sessions with their session IDs."),
    )
