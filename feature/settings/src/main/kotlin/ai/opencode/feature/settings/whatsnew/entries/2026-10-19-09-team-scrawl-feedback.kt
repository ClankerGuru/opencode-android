package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.teamScrawlFeedback: WhatsNewEntry
    get() = feature(
        id = "team-scrawl-feedback",
        day = "2026-10-19",
        audience = Audience.developer,
        title = "Team Scrawl Feedback",
        symbol = "hand.draw.fill",
        tint = Tint.teal,
        summary = "Join Team Feedback with your personal link to send three-finger Scrawl recordings and redacted diagnostics to the shared queue on the Problems board.",
        details = listOf(
            "Open your opencode://team/join link to review what gets collected and accept.",
            "A three-step interactive tutorial walks through holding three fingers, talking and drawing, and sending a practice clip to the test lane.",
            "Each recording attaches the same redacted timeline, app log, and Motion Watch diagnostics as Report a Problem.",
            "Replay the tutorial or leave Team Feedback anytime in Settings › Developer.",
        ),
        example = "Open Settings › Developer to check your Team Feedback status or replay the tutorial.",
        tryIt = Destination.Open(SettingsDestination.Developer),
    )
