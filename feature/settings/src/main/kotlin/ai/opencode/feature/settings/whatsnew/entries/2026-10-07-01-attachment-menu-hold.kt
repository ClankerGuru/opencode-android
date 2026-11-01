package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.attachmentMenuHold: WhatsNewEntry
    get() = feature(
        id = "attachment-menu-hold",
        day = "2026-10-07",
        title = "Attach With +",
        symbol = "plus",
        tint = Tint.blue,
        summary = "The + menu grows out of the message box with Camera, Photos, and Files. Hold + and slide to choose in one motion.",
        details = listOf(
            "Photos opens right above the message box; tap photos to attach them.",
            "The camera has lens buttons, pinch to zoom, tap to focus, and flash.",
        ),
        example = "Hold +, slide up to Photos, and let go",
    )
