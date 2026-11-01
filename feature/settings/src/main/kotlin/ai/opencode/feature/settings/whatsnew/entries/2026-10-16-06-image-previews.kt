package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.imagePreviews: WhatsNewEntry
    get() = feature(
        id = "image-previews",
        day = "2026-10-16",
        title = "Image Previews",
        symbol = "photo.on.rectangle",
        tint = Tint.purple,
        summary = "Images the agent names appear as previews in a bento layout, and missing files say so.",
        details = listOf(
            "Previews sit under the paragraph that names them.",
            "Tap an image to open it full screen and swipe through the reply’s images.",
            "Images outside the session’s folder wait for you to tap before loading.",
        ),
        example = "Ask the agent to show screenshots from your project.",
        tryIt = Destination.Prompt("Show me the screenshots in this project."),
    )
