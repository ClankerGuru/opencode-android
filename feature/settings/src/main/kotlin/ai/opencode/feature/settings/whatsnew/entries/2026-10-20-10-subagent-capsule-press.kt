package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.subagentCapsulePress: WhatsNewEntry
    get() = smaller(
        id = "subagent-capsule-press",
        day = "2026-10-20",
        title = "Rounded Activity Capsule",
        symbol = "capsule",
        summary = "The subagent and shell capsule keeps its rounded ends while you press it.",
    )
