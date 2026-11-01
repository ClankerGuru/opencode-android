package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.runErrorLine: WhatsNewEntry
    get() = feature(
        id = "run-error-line",
        day = "2026-10-12",
        title = "When a Run Fails",
        symbol = "exclamationmark.bubble",
        tint = Tint.red,
        summary = "A run that stops early says why in plain words, with one way forward: Edit, Compact, Switch Model, or Try Again.",
        details = listOf(
            "Edit undoes your message and puts it back in the message box to reword.",
            "Hold the line to see the full error and copy it.",
            "When the agent will retry on its own, Working counts down to the next try.",
        ),
        example = "When a reply is blocked, tap Edit, reword the message, and send it again",
    )
