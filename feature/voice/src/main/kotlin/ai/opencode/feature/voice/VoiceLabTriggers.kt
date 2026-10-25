package ai.opencode.feature.voice

/**
 * UI tests post these to start a call, or open its transcript, while something covers the
 * call button or bar (iOS `VoiceLabTrigger`, `VoiceLabTriggers.swift`, DEBUG only). iOS
 * delivers them as Darwin notifications (`"OpenCodeLab." + rawValue`); on Android a debug
 * build can deliver them as broadcasts with the same names.
 *
 * The `VoiceLabTriggers` view modifier (it sends `SessionFeature` actions: `voiceTapped`,
 * `voice(.presented(.captionsTapped))`, `raisedToEar`) moved to `:feature:session`, which
 * owns that store.
 */
enum class VoiceLabTrigger(val rawValue: String) {
    startCall("ai.opencode.lab.voice-start"),
    showCaptions("ai.opencode.lab.voice-captions"),

    /** Raise to Talk's detector firing: the emulator has no proximity sensor. */
    raiseToEar("ai.opencode.lab.voice-raise"),
    ;

    /** iOS `notification`: `Notification.Name("OpenCodeLab." + rawValue)`. */
    val notification: String get() = "OpenCodeLab.$rawValue"
}
