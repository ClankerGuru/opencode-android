package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.smoothLinkPreviewArrival: WhatsNewEntry
    get() = smaller(
        id = "smooth-link-preview-arrival",
        day = "2026-10-20",
        title = "Smoother Link Previews",
        symbol = "link",
        summary = "Link previews fade in as they load, and the rows below move smoothly when a title changes height.",
    )
