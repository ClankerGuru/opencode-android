package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceAppScreenshot: WhatsNewEntry
    get() = feature(
        id = "voice-app-screenshot",
        day = "2026-10-25",
        audience = Audience.developer,
        title = "Voice Screenshots",
        symbol = "camera.viewfinder",
        tint = Tint.gray,
        summary = "On a voice call, say “look at this” and the app’s screen goes to a session with your feedback.",
        details = listOf(
            "It sends only the app’s own screen, with its screen name and call and app state.",
            "Ask for the call log or recent app logs to go with it; addresses and credentials are masked.",
            "It won’t capture while a sign-in screen or password field is showing.",
        ),
        example = "In a voice call, say “take a screenshot, this row is misaligned”",
    )
