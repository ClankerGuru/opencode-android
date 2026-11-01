package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.wispCallSounds: WhatsNewEntry
    get() = smaller(
        id = "wisp-call-sounds",
        day = "2026-10-30",
        title = "Voice Calls Use Wisp’s Sounds",
        symbol = "speaker.wave.2",
        summary = "Calls start with a soft breath over a quiet connecting shimmer, chime when they connect, tick gently as the agent looks, opens or runs things, and sigh when they end. Pick another set in Settings › Voice › Call Sounds.",
        tryIt = Destination.Open(SettingsDestination.Voice),
    )
