package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.workingOrb: WhatsNewEntry
    get() = smaller(
        id = "working-orb",
        day = "2026-10-09",
        title = "Status Orb",
        symbol = "circle.dotted.circle",
        summary = "An orb beside Working and on subagent cards shows what the agent is doing, and turns into the copy button when it finishes.",
    )
