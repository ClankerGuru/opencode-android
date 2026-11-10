package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.serverVoice: WhatsNewEntry
    get() = feature(
        id = "server-voice",
        day = "2026-11-07",
        title = "Calls on Your Server’s Keys",
        symbol = "waveform",
        tint = Tint.green,
        summary = "Server Voice is now on for everyone, with no switch. Calls can use your OpenCode server’s keys, so this phone doesn’t need its own OpenAI key.",
        details = listOf(
            "With Choose Automatically on, calls use the server you’re on, then your OpenAI key on this phone, then another saved server that’s ready.",
            "If this phone and your server both have an OpenAI key, calls now use the server’s. To keep using yours, open Your OpenAI Account in Voice Provider and tap Use for Calls.",
            "Gemini Live is now a provider too, on servers with a key for Gemini.",
            "A call that can’t start says why and shows the button that fixes it.",
        ),
        example = "Open Settings › Voice and tap Voice Provider",
        tryIt = Destination.Open(SettingsDestination.Voice),
    )
