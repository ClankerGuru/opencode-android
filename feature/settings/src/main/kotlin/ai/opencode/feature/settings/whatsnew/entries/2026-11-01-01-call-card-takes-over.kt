package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callCardTakesOver: WhatsNewEntry
    get() = smaller(
        id = "call-card-takes-over",
        day = "2026-11-01",
        title = "The Call’s Card Takes Over the Bottom",
        symbol = "rectangle.bottomhalf.inset.filled",
        summary = "In Living Orb, the call’s card now fills the bottom of the screen over the message field, with even margins all round, so the keyboard no longer squeezes it. Shrink it back to the orb to type.",
        tryIt = Destination.Open(SettingsDestination.Experiment("living-orb")),
    )
