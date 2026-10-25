package ai.opencode.feature.settings

/**
 * Port of iOS `Settings/VoiceKeySource.swift`'s `VoiceKeyStatus`: the status shown at a glance on
 * the provider key row in Settings › Voice. `VoiceKeySource` itself lives in `:feature:voice`
 * (`VoiceRoute.keySource` returns it).
 */
sealed interface VoiceKeyStatus {
    data object Missing : VoiceKeyStatus
    data object Saved : VoiceKeyStatus
    data object Checking : VoiceKeyStatus
    data object Verified : VoiceKeyStatus
    data class Invalid(val message: String) : VoiceKeyStatus
    data class OnServer(val providerName: String) : VoiceKeyStatus

    val badgeTitle: String
        get() = when (this) {
            Missing -> "Missing"
            Saved, is Invalid -> "Saved"
            Checking -> "Checking…"
            Verified -> "Verified"
            is OnServer -> providerName
        }
}
