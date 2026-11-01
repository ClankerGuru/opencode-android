package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.soundEffectsExperiment: WhatsNewEntry
    get() = feature(
        id = "sound-effects-experiment",
        day = "2026-10-25",
        audience = Audience.developer,
        title = "Sound Effects",
        symbol = "speaker.wave.2.fill",
        tint = Tint.gray,
        summary = "An experiment that plays soft sounds with the haptics for replies, requests, Catch Up and failed sends.",
        details = listOf(
            "Silent and vibrate modes mute them, and music keeps playing.",
            "They stay quiet during a call, dictation or media, and in the background.",
            "Volume and an optional Send Sound sit beneath the switch.",
        ),
        example = "Turn on Sound Effects in Settings › Experimental, then wait for a reply in another session",
        tryIt = Destination.Open(SettingsDestination.Experiment("sound-effects")),
    )
