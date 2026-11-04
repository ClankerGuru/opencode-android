package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.plusMenuPhotoSheet: WhatsNewEntry
    get() = smaller(
        id = "plus-menu-photo-sheet",
        day = "2026-10-16",
        title = "Photos in Place of the Keyboard",
        symbol = "photo.on.rectangle",
        summary = "With the New Plus Menu experiment on, Photos grows the + menu into a sheet of your photos where the keyboard was, and holding + shows your newest photos: slide to one and let go to add it.",
    )
