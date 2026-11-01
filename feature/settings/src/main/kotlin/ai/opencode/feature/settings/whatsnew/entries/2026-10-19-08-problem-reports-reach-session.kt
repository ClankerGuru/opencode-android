package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.problemReportsReachSession: WhatsNewEntry
    get() = feature(
        id = "problem-reports-reach-session",
        day = "2026-10-19",
        audience = Audience.developer,
        title = "Reports Reach the Session",
        symbol = "exclamationmark.bubble",
        tint = Tint.orange,
        summary = "Report a Problem now also sends your note into the session you reported from, with the full report attached for the agent to read.",
        details = listOf(
            "The message opens with a problem report line, your note, the report ID and a link to the uploaded trace.",
            "The device, build, screen, timeline and app log arrive as an attached file, like one you attach yourself.",
            "From Home it goes to your most recent session.",
            "If the upload fails, the note and summary still arrive and say so; Try Again then sends just the trace link.",
            "A “Sent to session” confirmation shows when it lands.",
        ),
        example = "Open a session’s menu, choose Report a Problem, add a note and tap Send",
    )
