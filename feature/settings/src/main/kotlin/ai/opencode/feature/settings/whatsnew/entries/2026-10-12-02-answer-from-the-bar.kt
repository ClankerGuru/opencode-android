package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.answerFromTheBar: WhatsNewEntry
    get() = feature(
        id = "answer-from-the-bar",
        day = "2026-10-12",
        title = "Answer From the Message Box",
        symbol = "checklist",
        tint = Tint.green,
        summary = "Questions and permissions grow out of the message box, and you answer right there: tap a choice, or type or dictate your own.",
        details = listOf(
            "Permissions show the command or edit before you allow it.",
            "Deny takes what you typed as what to do instead, so the agent keeps going.",
            "Your draft comes back once you’ve answered.",
        ),
        example = "When the agent asks a question, hold the mic and say your answer",
    )
