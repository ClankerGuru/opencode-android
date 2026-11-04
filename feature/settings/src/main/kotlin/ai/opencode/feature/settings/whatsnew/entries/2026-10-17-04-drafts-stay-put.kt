package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.draftsStayPut: WhatsNewEntry
    get() = smaller(
        id = "drafts-stay-put",
        day = "2026-10-17",
        title = "Drafts Stay Put",
        symbol = "square.and.pencil",
        summary = "Drafts, attached photos and locations, and messages waiting to send stay in place if the app closes in the background.",
    )
