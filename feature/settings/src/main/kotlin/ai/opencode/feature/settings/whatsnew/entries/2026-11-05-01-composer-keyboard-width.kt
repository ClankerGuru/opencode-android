package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerKeyboardWidth: WhatsNewEntry
    get() = smaller(
        id = "composer-keyboard-width",
        day = "2026-11-05",
        title = "Wider Composer While Typing",
        symbol = "arrow.left.and.right",
        summary = "With the keyboard up, the composer widens to a small, even margin, so long drafts wrap less.",
    )
