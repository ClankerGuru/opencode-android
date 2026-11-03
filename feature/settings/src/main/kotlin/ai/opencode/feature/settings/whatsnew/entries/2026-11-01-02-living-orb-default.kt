package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.livingOrbDefault: WhatsNewEntry
    get() = feature(
        id = "living-orb-default",
        day = "2026-11-01",
        title = "Calls Float in the Orb",
        symbol = "circle.hexagongrid.fill",
        tint = Tint.green,
        summary = "Voice calls now float in the Living Orb for everyone, above every screen, while the message field stays yours.",
        details = listOf(
            "Tap the orb to grow it into the call’s card, and drag up for the whole call and its transcript.",
            "Drag the orb anywhere; it stays put as you switch sessions or open Settings.",
            "Start a call from the session list too, with the call button beside Settings.",
            "If you use the Tray, it stays.",
            "Change it in Settings › Experimental › Calls › Call Style.",
        ),
        example = "Start a call, then open the session list: the orb comes with you",
        tryIt = Destination.Call,
    )
