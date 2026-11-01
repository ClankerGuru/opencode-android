package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.dragFromTranscript: WhatsNewEntry
    get() = feature(
        id = "drag-from-transcript",
        day = "2026-10-20",
        title = "Drag from the Session",
        symbol = "hand.draw",
        tint = Tint.orange,
        summary = "Hold and drag code, images, files, links and messages out of a session into other apps.",
        details = listOf(
            "Code blocks arrive as text with their language.",
            "Images and files the agent wrote arrive as files.",
            "Drop any of them into the message box to attach or paste them.",
            "Scrolling, selecting text and menus work as before.",
        ),
        example = "Hold a code block in a reply, then drag it into Notes.",
        tryIt = Destination.Prompt("Write a short Swift function that reverses a string, in a code block."),
    )
