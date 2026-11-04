package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.serverSwitcher: WhatsNewEntry
    get() = feature(
        id = "server-switcher",
        day = "2026-10-09",
        title = "Switch Servers",
        symbol = "laptopcomputer",
        tint = Tint.gray,
        summary = "The server you’re on is the drawer’s title. Tap it to switch to another server or add one.",
        details = listOf(
            "Settings shows the connection as a live heartbeat, with the round trip beside the server’s name.",
            "Rename a server; only this phone uses the name.",
        ),
        example = "Open the drawer, tap the server’s name, and add your other computer",
        tryIt = Destination.servers,
    )
