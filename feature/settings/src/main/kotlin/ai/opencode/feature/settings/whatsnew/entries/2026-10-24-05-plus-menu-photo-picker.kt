package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.plusMenuPhotoPicker: WhatsNewEntry
    get() = smaller(
        id = "plus-menu-photo-picker",
        day = "2026-10-24",
        title = "New Photo Picker",
        symbol = "photo.on.rectangle",
        summary = "Turn on New Plus Menu in Settings › Experimental › Composer to pick up to 8 recent photos right from the + menu, browse All Photos, and watch them fly into the composer and then into the session.",
    )
