package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.requestDictationLastLine: WhatsNewEntry
    get() = smaller(
        id = "request-dictation-last-line",
        day = "2026-10-21",
        title = "Dictation Shows Its End",
        symbol = "mic",
        summary = "On iOS 26, a long dictation stopped into a pending request’s reply box shows its last lines instead of text from partway through.",
    )
