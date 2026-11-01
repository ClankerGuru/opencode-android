package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.groupedSteering: WhatsNewEntry
    get() = feature(
        id = "grouped-steering",
        day = "2026-10-13",
        title = "Grouped Messages",
        symbol = "text.bubble",
        tint = Tint.blue,
        summary = "Messages you send in a row sit together as one block, and steering messages waiting for the agent share one Steering label.",
        details = listOf(
            "Bubbles in a row sit close, their corners joining where they meet.",
            "Photos and files keep their own round shape inside the block.",
            "As the agent takes each message, it moves up into place in one motion.",
        ),
        example = "While the agent works, send three quick messages, one with a photo",
    )
