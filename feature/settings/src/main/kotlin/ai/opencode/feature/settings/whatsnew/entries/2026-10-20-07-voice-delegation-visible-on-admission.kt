package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceDelegationVisibleOnAdmission: WhatsNewEntry
    get() = smaller(
        id = "voice-delegation-visible-on-admission",
        day = "2026-10-20",
        title = "Voice Messages Appear Promptly",
        symbol = "waveform",
        summary = "Messages Voice sends into the session appear as soon as the server accepts them, even when session updates are delayed.",
    )
