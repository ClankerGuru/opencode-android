package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionTitleFirstPrompt: WhatsNewEntry
    get() = smaller(
        id = "session-title-first-prompt",
        day = "2026-10-25",
        title = "Titles from First Prompts",
        symbol = "text.quote",
        summary = "Sessions without a title now show their first prompt, on one line, until the server names them.",
    )
