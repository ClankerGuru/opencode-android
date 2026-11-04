package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.welcomePairingCopy: WhatsNewEntry
    get() = smaller(
        id = "welcome-pairing-copy",
        day = "2026-10-21",
        title = "Pair from the OpenCode App",
        symbol = "laptopcomputer.and.iphone",
        summary = "The welcome screen points you to Settings → Pairing in OpenCode on your computer, or /pair in the terminal, with the install command for anyone who doesn’t have OpenCode yet.",
    )
