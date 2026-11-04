package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerEditingCaret: WhatsNewEntry
    get() = smaller(
        id = "composer-editing-caret",
        day = "2026-11-04",
        title = "Editing Drafts",
        symbol = "text.cursor",
        summary = "Editing a word in the middle of a draft keeps the cursor where you’re typing.",
    )
