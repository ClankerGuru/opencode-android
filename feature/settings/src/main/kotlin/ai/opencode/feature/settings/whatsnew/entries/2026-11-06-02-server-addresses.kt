package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.serverAddresses: WhatsNewEntry
    get() = feature(
        id = "server-addresses",
        day = "2026-11-06",
        title = "Steadier Server Connections",
        symbol = "point.3.connected.trianglepath.dotted",
        tint = Tint.green,
        summary = "OpenCode tries all of a server’s addresses at once and uses whichever answers first, so it reconnects when you leave home without you switching your VPN off and on.",
        details = listOf(
            "An address that worked on your home network is no longer tried first everywhere else.",
            "A brief drop, unlocking your phone or pulling down Quick Settings no longer flashes a banner. Reconnecting appears after a few seconds, and Offline after about 12, or 4 when your phone has no network.",
        ),
        example = "With a session open on a server paired with Tailscale, turn off Wi-Fi and keep working",
    )
