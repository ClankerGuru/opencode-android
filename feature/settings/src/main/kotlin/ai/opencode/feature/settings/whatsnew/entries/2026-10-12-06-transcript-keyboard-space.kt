package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.transcriptKeyboardSpace: WhatsNewEntry
    get() = smaller(
        id = "transcript-keyboard-space",
        day = "2026-10-12",
        title = "Keyboard Closes Cleanly",
        symbol = "keyboard.chevron.compact.down",
        summary = "Dragging the keyboard away no longer leaves a gap under the last message.",
    )
