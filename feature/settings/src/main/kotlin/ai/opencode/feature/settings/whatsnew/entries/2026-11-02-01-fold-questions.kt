package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.foldQuestions: WhatsNewEntry
    get() = smaller(
        id = "fold-questions",
        day = "2026-11-02",
        title = "Fold Questions Out of the Way",
        symbol = "chevron.down",
        summary = "Drag a question down to fold it into a small bar above the composer, and tap it to bring it back. A failed answer now waits on Retry.",
    )
