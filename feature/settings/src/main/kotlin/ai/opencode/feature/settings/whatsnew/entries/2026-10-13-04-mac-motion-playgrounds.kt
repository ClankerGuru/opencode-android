package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.macMotionPlaygrounds: WhatsNewEntry
    get() = feature(
        id = "mac-motion-playgrounds",
        day = "2026-10-13",
        audience = Audience.developer,
        title = "Playground Controls on Mac",
        symbol = "slider.horizontal.3",
        tint = Tint.orange,
        summary = "With Developer Mode on, pair tuning with your Mac to replay playground animations in slow motion and inspect captured frames.",
        details = listOf(
            "The Mac’s knobs page holds playground selection, presets and replay controls; the phone shows only the playground.",
            "Compact cards show which controls are on screen, with named presets for motion and spacing and Native to restore the shipped feel; each preset applies its values together in one update.",
            "The floating tuning panel hides when the Mac connects; summon it with the tuning gesture and choose Exit Playground to leave.",
            "Seeded scenarios replay the same sample session so you can compare motion between builds.",
            "Replays start fresh and deliver their steering and queued messages instead of leaving them waiting.",
            "The Mac’s Review Player lets you step through captured geometry, mark a frame, and send animation feedback.",
        ),
        example = "Open Settings → Developer, pair with the Mac tuning page, then try Transcript → Stream Reply at 0.25×",
        tryIt = Destination.Open(SettingsDestination.Developer),
    )
