package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.scrawlFasterStart: WhatsNewEntry
    get() = feature(
        id = "scrawl-faster-start",
        day = "2026-10-19",
        audience = Audience.developer,
        title = "Faster Scrawl Start",
        symbol = "hand.draw",
        tint = Tint.red,
        summary = "Scrawl’s controls appear while the microphone and recorder start.",
        details = listOf(
            "Draw, Send and Discard taps are kept while recording gets ready.",
            "A three-finger hold prepares the overlay without starting the microphone early.",
            "Fixed a crash when Scrawl’s microphone restarts during an audio route change.",
        ),
        example = "On a paired device, hold three fingers to start a recording",
    )
