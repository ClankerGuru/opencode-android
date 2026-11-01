package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.smartDictationAudio: WhatsNewEntry
    get() = smaller(
        id = "smart-dictation-audio",
        day = "2026-10-19",
        title = "Smoother Dictation with Music",
        symbol = "waveform",
        summary = "Dictating while music plays keeps it in stereo on AirPods without a dropout a minute later, and on your phone alone it stays on the speaker instead of the earpiece.",
    )
