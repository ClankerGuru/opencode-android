package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callLinksAndAudio: WhatsNewEntry
    get() = feature(
        id = "call-links-and-audio",
        day = "2026-11-04",
        title = "Links and Audio on Calls",
        symbol = "checkmark.seal.fill",
        tint = Tint.green,
        summary = "Open Links from Calls and Audio in Calls are now on for everyone, with no switch.",
        details = listOf(
            "During a call an agent can offer a link, like an Uber ride: tap Open to open it in its app, or Not Now.",
            "An agent can play music or a clip on the call. It turns down while either of you speaks; pause or stop it above the composer.",
        ),
        example = "On a call, ask the agent to play some music",
    )
