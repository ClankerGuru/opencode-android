package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceWebrtcWeakConnection: WhatsNewEntry
    get() = smaller(
        id = "voice-webrtc-weak-connection",
        day = "2026-10-08",
        title = "Steadier Calls",
        symbol = "antenna.radiowaves.left.and.right",
        summary = "A patchy connection costs a call a brief glitch instead of seconds of silence.",
    )
