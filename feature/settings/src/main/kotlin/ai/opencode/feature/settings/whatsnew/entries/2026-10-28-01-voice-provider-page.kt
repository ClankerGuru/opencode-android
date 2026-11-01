package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceProviderPage: WhatsNewEntry
    get() = smaller(
        id = "voice-provider-page",
        day = "2026-10-28",
        title = "Voice Provider",
        symbol = "waveform",
        summary = "With Server Voice on, Settings › Voice has one Voice Provider row that says who runs your calls, where, and whether they’re ready. Calls choose automatically: the server you’re on, then your own OpenAI account, then another saved server that’s ready. A call that can’t start says why, with the one button that fixes it.",
        tryIt = Destination.Open(SettingsDestination.Experiment("server-voice")),
    )
