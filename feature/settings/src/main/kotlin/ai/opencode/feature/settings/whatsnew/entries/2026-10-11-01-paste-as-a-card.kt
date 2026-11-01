package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.pasteAsACard: WhatsNewEntry
    get() = feature(
        id = "paste-as-a-card",
        day = "2026-10-11",
        title = "Long Pastes as Cards",
        symbol = "doc.on.clipboard",
        tint = Tint.brown,
        summary = "A long paste becomes a Pasted Text card beside your images, and the agent still reads all of it.",
        details = listOf(
            "Tap the card to read the whole paste, with Copy and Share.",
            "Long messages fold to a few lines with Show More.",
        ),
        example = "Copy a long log, paste it into the message box, and tap the card",
    )
