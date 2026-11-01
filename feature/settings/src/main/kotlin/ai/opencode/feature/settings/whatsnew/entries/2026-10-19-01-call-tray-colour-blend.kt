package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callTrayColourBlend: WhatsNewEntry
    get() = smaller(
        id = "call-tray-colour-blend",
        day = "2026-10-19",
        title = "Smoother Call Button",
        symbol = "phone",
        summary = "With the Call Tray experiment on, the call button blends straight into the red End button as a call starts, and back as you hang up, instead of flashing through the background.",
        tryIt = Destination.Open(SettingsDestination.Experiment("call-tray")),
    )
