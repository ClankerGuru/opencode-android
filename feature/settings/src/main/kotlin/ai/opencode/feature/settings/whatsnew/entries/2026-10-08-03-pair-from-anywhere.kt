package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.pairFromAnywhere: WhatsNewEntry
    get() = feature(
        id = "pair-from-anywhere",
        day = "2026-10-08",
        title = "Pair From Anywhere",
        symbol = "qrcode",
        tint = Tint.gray,
        summary = "Run opencode pair --remote on your computer to connect from any network, without shared Wi-Fi or Tailscale.",
        details = listOf(
            "Scan the code it shows, or paste the link it prints.",
        ),
        example = "Run opencode pair --remote, then add the server on your phone",
        tryIt = Destination.servers,
    )
