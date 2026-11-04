package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.tuningGlassTapTargets: WhatsNewEntry
    get() = feature(
        id = "tuning-glass-tap-targets",
        day = "2026-10-20",
        audience = Audience.developer,
        title = "Easier Tuning Controls",
        symbol = "slider.horizontal.3",
        tint = Tint.gray,
        summary = "The folded tuning pill and edge tab have larger tap targets.",
        details = listOf(
            "Their glass faces keep the same size.",
        ),
        example = "Fold the Tuning Panel, then tap near the edge of its pill or tab",
    )
