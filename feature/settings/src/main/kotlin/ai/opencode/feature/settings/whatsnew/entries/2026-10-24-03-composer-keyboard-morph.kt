package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerKeyboardMorph: WhatsNewEntry
    get() = smaller(
        id = "composer-keyboard-morph",
        day = "2026-10-24",
        title = "Composer Follows the Keyboard",
        symbol = "keyboard",
        summary = "The composer’s controls open with the keyboard and move with it as you drag it up and down.",
    )
