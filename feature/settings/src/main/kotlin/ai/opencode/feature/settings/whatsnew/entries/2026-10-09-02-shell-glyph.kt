package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.shellGlyph: WhatsNewEntry
    get() = smaller(
        id = "shell-glyph",
        day = "2026-10-09",
        title = "Shell Activity",
        symbol = "apple.terminal",
        summary = "A running shell’s >_ prompt laps as its output arrives.",
    )
