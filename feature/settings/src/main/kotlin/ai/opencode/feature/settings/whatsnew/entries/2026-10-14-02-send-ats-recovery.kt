package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sendAtsRecovery: WhatsNewEntry
    get() = smaller(
        id = "send-ats-recovery",
        day = "2026-10-14",
        title = "Reliable Sends",
        symbol = "arrow.clockwise",
        summary = "Messages retry automatically if iOS briefly rejects an already-working server connection.",
    )
