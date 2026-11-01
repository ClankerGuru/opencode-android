package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.firstRunPairingFixes: WhatsNewEntry
    get() = smaller(
        id = "first-run-pairing-fixes",
        day = "2026-10-21",
        title = "Clearer First Pairing",
        symbol = "qrcode.viewfinder",
        summary = "Pasting with nothing on the clipboard says so beside Paste, and setup no longer promises remote access in half a minute.",
    )
