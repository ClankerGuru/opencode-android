package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.revertToHere: WhatsNewEntry
    get() = feature(
        id = "revert-to-here",
        day = "2026-10-20",
        title = "Revert to Here",
        symbol = "arrow.uturn.backward",
        tint = Tint.orange,
        summary = "Hold one of your messages and choose Revert to Here to go back to that point and edit it.",
        details = listOf(
            "The message’s words, photos and files return to the message box.",
            "Undo brings the turns and their file changes back until you send a new message.",
            "It’s offered while the agent isn’t working.",
        ),
        example = "Hold a message you sent earlier and choose Revert to Here.",
    )
