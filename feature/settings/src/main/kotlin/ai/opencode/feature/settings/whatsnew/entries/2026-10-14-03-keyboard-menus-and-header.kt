package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.keyboardMenusAndHeader: WhatsNewEntry
    get() = smaller(
        id = "keyboard-menus-and-header",
        day = "2026-10-14",
        title = "Steadier Keyboard and Menus",
        symbol = "keyboard",
        summary = "Tap + while typing to open attachments without lowering the keyboard or moving the controls; the header blur stays in place as the keyboard moves.",
    )
