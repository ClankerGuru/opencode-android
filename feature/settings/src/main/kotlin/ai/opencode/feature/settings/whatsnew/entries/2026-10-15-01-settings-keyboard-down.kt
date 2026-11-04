package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.settingsKeyboardDown: WhatsNewEntry
    get() = smaller(
        id = "settings-keyboard-down",
        day = "2026-10-15",
        title = "No Keyboard Over Settings",
        symbol = "keyboard.chevron.compact.down",
        summary = "Opening Settings from the drawer while you were typing puts the keyboard away instead of leaving it over Settings.",
    )
