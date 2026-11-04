package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerPhotoViewer: WhatsNewEntry
    get() = smaller(
        id = "composer-photo-viewer",
        day = "2026-10-24",
        title = "Full-Screen Composer Photos",
        symbol = "photo",
        summary = "Tap a photo in the composer to see it full screen, swipe between photos, and remove one before you send.",
    )
