package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.transcriptRowTargets: WhatsNewEntry
    get() = smaller(
        id = "transcript-row-targets",
        day = "2026-10-16",
        title = "Easier Taps",
        symbol = "hand.point.up.left",
        summary = "The Session sheet’s title is easier to tap, and VoiceOver and Voice Control no longer reach composer buttons hidden while you dictate.",
    )
