package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callTrayDocksInDrawer: WhatsNewEntry
    get() = smaller(
        id = "call-tray-docks-in-drawer",
        day = "2026-10-20",
        title = "Call Tray in the Drawer",
        symbol = "phone",
        summary = "With the Call Tray experiment on, opening the drawer during a call carries the tray beneath New Session, with its level, Mute and End, and closing brings it back.",
        tryIt = Destination.Open(SettingsDestination.Experiment("call-tray")),
    )
