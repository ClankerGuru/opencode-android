package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callTraySwitchContinuity: WhatsNewEntry
    get() = smaller(
        id = "call-tray-switch-continuity",
        day = "2026-10-20",
        title = "Steadier Call Controls",
        symbol = "waveform",
        summary = "The call waveform keeps moving when you switch sessions, and the controls stay level as you open the session list.",
    )
