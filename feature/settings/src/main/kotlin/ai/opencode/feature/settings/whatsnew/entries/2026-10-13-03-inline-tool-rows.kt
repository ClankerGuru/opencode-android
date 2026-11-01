package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.inlineToolRows: WhatsNewEntry
    get() = feature(
        id = "inline-tool-rows",
        day = "2026-10-13",
        title = "Quiet Tool and Question Rows",
        symbol = "rectangle.split.1x2",
        tint = Tint.indigo,
        summary = "Tool groups and answered questions open onto a full-width band like code, with consecutive reads merged into one row.",
        details = listOf(
            "Collapsed groups read “Used N tools” and mark failed tool calls in red.",
            "Execute rows name the tools their script called, and Skill rows name the skill.",
            "Answered questions show the prompt and every option offered, with your choice checked.",
        ),
        example = "Tap a “Used N tools” row in a session to open its band",
    )
