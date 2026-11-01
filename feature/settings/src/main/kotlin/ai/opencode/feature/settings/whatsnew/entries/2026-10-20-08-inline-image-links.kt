package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.inlineImageLinks: WhatsNewEntry
    get() = smaller(
        id = "inline-image-links",
        day = "2026-10-20",
        title = "Image Link Previews",
        symbol = "photo",
        summary = "Turn on Image Link Previews in Settings › Experimental › Transcript to preview linked images in replies. Tap a thumbnail to open it full screen and zoom.",
        tryIt = Destination.Open(SettingsDestination.Experiment("image-link-previews")),
    )
