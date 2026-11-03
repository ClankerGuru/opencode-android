package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.screenshotShutter: WhatsNewEntry
    get() = feature(
        id = "screenshot-shutter",
        day = "2026-11-01",
        audience = Audience.developer,
        title = "Screenshot Shutter",
        symbol = "camera.shutter.button",
        tint = Tint.gray,
        summary = "When the app screenshots itself for a session, the screen flashes and the shutter clicks.",
        details = listOf(
            "The flash comes after the capture, so it’s never in the picture.",
            "Reduce Motion makes it a soft dim, and Dim Flashing Lights turns it off.",
            "In a call the click plays through the call, so the agent doesn’t hear it.",
        ),
        example = "In a voice call, say “look at this”",
    )
