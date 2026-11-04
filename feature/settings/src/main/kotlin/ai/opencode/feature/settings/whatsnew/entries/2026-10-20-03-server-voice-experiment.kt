package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.serverVoiceExperiment: WhatsNewEntry
    get() = smaller(
        id = "server-voice-experiment",
        day = "2026-10-20",
        title = "Server Voice",
        symbol = "waveform",
        summary = "Turn on Server Voice in Settings › Experimental › Calls to start calls with your OpenCode server’s keys instead of your own, and to try Gemini Live as the voice provider.",
        tryIt = Destination.Open(SettingsDestination.Experiment("server-voice")),
    )
