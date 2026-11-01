package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callVoice: WhatsNewEntry
    get() = feature(
        id = "call-voice",
        day = "2026-10-10",
        title = "Voice Calls",
        symbol = "phone.and.waveform.fill",
        tint = Tint.green,
        summary = "Tap the waveform in an empty message box and talk. Voice works in your sessions while you talk and tells you when the work is done.",
        details = listOf(
            "Ask for any session by name: Voice opens it and starts or steers work there.",
            "Talk over it to interrupt, or ask how the work is going.",
            "Tap the call bar for live captions.",
            "Mute and End from the Lock Screen and the Dynamic Island.",
            "Touch and hold Stop to call while the agent works.",
            "Choose a voice in Settings → Voice.",
        ),
        example = "Start a call and say “open my upload fixes session and check the tests”",
        tryIt = Destination.Call,
    )
