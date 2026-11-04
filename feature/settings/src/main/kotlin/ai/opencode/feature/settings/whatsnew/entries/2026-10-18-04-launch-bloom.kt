package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.launchBloom: WhatsNewEntry
    get() = smaller(
        id = "launch-bloom",
        day = "2026-10-18",
        title = "Launch Bloom",
        symbol = "sparkles",
        summary = "Opening the app from a cold start inhales the OpenCode mark and blooms into your session.",
    )
