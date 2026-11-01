package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.steadierRoundTrip: WhatsNewEntry
    get() = smaller(
        id = "steadier-round-trip",
        day = "2026-10-27",
        title = "Steadier Round Trip",
        symbol = "waveform.path.ecg",
        summary = "The connection card’s ms number is now a rolling average of the last few checks, so one slow answer nudges it instead of making it jump.",
    )
