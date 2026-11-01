package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerLargeTextTargets: WhatsNewEntry
    get() = smaller(
        id = "composer-large-text-targets",
        day = "2026-10-16",
        title = "Easier Composer Taps",
        symbol = "hand.tap",
        summary = "Buttons in the composer are easier to tap at large text sizes.",
    )
