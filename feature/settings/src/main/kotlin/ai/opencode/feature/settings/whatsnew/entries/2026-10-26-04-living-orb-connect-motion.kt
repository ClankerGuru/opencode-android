package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.livingOrbConnectMotion: WhatsNewEntry
    get() = smaller(
        id = "living-orb-connect-motion",
        day = "2026-10-26",
        title = "Orb Connect Motion",
        symbol = "phone.connection",
        summary = "With Living Orb on, the call button lifts into the orb and spins up while the call connects, then lands back in the button when it ends. Redialing right away starts a fresh call instead of sticking.",
    )
