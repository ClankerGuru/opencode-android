package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.calmWireframeLaunch: WhatsNewEntry
    get() = smaller(
        id = "calm-wireframe-launch",
        day = "2026-10-21",
        title = "Calmer Loading",
        symbol = "hourglass",
        summary = "Cached sessions open straight away; a session that takes a moment to load shows pulsing message bubbles that fade into your messages.",
    )
