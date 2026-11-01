package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionViewRefinedStandard: WhatsNewEntry
    get() = smaller(
        id = "session-view-refined-standard",
        day = "2026-10-17",
        title = "Clearer Reply Typography",
        symbol = "textformat.size",
        summary = "Level-3 headings step up above bold text, prose softens slightly so bold stands out, and paragraphs and code blocks get a touch more breathing room.",
    )
