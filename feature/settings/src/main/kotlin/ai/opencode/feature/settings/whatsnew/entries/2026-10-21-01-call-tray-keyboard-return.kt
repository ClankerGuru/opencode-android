package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callTrayKeyboardReturn: WhatsNewEntry
    get() = smaller(
        id = "call-tray-keyboard-return",
        day = "2026-10-21",
        title = "Call Tray Return",
        symbol = "waveform",
        summary = "The Call Tray stays visible as the drawer closes and the keyboard returns.",
    )
