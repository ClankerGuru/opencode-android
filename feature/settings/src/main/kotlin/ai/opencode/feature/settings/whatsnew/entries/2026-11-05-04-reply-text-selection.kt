package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.replyTextSelection: WhatsNewEntry
    get() = feature(
        id = "reply-text-selection",
        day = "2026-11-05",
        title = "Select Across a Reply",
        symbol = "selection.pin.in.out",
        tint = Tint.blue,
        summary = "Hold or double-tap a word in a reply, then drag the handles across paragraphs, lists, code and tables.",
        details = listOf(
            "Hold a handle just below the header or just above the composer and the reply scrolls, faster the closer you are to the edge.",
            "Copy keeps list bullets and numbers, and table rows and columns. Quote adds the selection to the composer and keeps code fenced.",
        ),
        example = "Hold a word near the start of a long reply, then drag the end handle down to the composer and hold it there",
    )
