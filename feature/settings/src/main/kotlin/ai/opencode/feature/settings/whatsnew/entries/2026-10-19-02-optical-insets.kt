package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.opticalInsets: WhatsNewEntry
    get() = smaller(
        id = "optical-insets",
        day = "2026-10-19",
        title = "Evenly Inset Controls",
        symbol = "plus.circle",
        summary = "The composer’s + and mic, the background-work and queued pills’ glyphs, and New Session’s symbol in the sidebar now sit optically centred in the round ends of their glass. The shell glyph is now a circle like the subagent orb, without its blinking cursor, and both show side by side when both are running.",
    )
