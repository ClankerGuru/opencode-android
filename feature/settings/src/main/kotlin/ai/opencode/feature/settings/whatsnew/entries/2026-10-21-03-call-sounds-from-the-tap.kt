package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callSoundsFromTheTap: WhatsNewEntry
    get() = smaller(
        id = "call-sounds-from-the-tap",
        day = "2026-10-21",
        title = "Quicker Call Sounds",
        symbol = "phone",
        summary = "A call’s connecting pings start with the first sweep instead of a couple of seconds after you tap.",
    )
