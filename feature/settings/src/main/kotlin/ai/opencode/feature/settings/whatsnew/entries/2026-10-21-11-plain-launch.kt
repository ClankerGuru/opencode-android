package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.plainLaunch: WhatsNewEntry
    get() = smaller(
        id = "plain-launch",
        day = "2026-10-21",
        title = "Plain Launch",
        symbol = "bolt",
        summary = "OpenCode opens on a plain screen and switches straight to your session as soon as it’s ready, with no loading animation.",
    )
