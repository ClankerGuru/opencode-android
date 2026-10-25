package ai.opencode.feature.settings.whatsnew

import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.feature.settings.ConnectionStatus
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer

/**
 * Port of iOS `Settings/WhatsNew/WhatsNewActions.swift`.
 *
 * iOS's `whatsNewSettingsPage(_:over:)` / `WhatsNewSettingsPage` (a Settings page pushed over the
 * entry's page on What's New's own stack) fold into Settings' stack on Android:
 * [SettingsFeature] pushes `settingsPage()` for an `open` action, and Voice's page for a call
 * before Voice is set up.
 */

/** The page's button: what it does, in Title Case. */
fun WhatsNewEntry.Destination.title(voiceAvailable: Boolean): String = when (this) {
    WhatsNewEntry.Destination.NewSession -> "New Session"
    is WhatsNewEntry.Destination.Prompt -> "Try It"
    WhatsNewEntry.Destination.ModelList -> "Choose a Model"
    WhatsNewEntry.Destination.Call -> if (voiceAvailable) "Start a Call" else "Set Up Voice"
    is WhatsNewEntry.Destination.Open ->
        if (page == WhatsNewEntry.SettingsDestination.Servers) "Manage Servers" else "Open ${page.title}"
}

/** What the button does where its title alone doesn't say. */
val WhatsNewEntry.Destination.hint: String?
    get() = if (this is WhatsNewEntry.Destination.Prompt) "Opens a new session with the prompt typed in" else null

val WhatsNewEntry.SettingsDestination.title: String
    get() = when (this) {
        WhatsNewEntry.SettingsDestination.Themes -> "Themes"
        WhatsNewEntry.SettingsDestination.Composer -> "Composer"
        WhatsNewEntry.SettingsDestination.Voice -> "Voice"
        WhatsNewEntry.SettingsDestination.Notifications -> "Notifications"
        WhatsNewEntry.SettingsDestination.Servers -> "Servers"
        WhatsNewEntry.SettingsDestination.Advanced -> "Advanced"
        is WhatsNewEntry.SettingsDestination.Experiment -> ExperimentalFeature.fromRawValue(id)?.title ?: "Advanced"
        WhatsNewEntry.SettingsDestination.Developer -> "Developer"
    }

/**
 * Whether the action can go now: a new session needs the server, so offline the page stays.
 */
fun SettingsFeature.State.canTryWhatsNew(destination: WhatsNewEntry.Destination): Boolean = when (destination) {
    WhatsNewEntry.Destination.NewSession, is WhatsNewEntry.Destination.Prompt ->
        connectionStatus != ConnectionStatus.disconnected
    else -> true
}

/** Notifications are hidden, not disabled, where the build has none. */
fun SettingsFeature.State.offersWhatsNew(destination: WhatsNewEntry.Destination): Boolean =
    destination != WhatsNewEntry.Destination.notifications || notificationPermission != null

/**
 * Whether a call can start now (iOS `VoiceRoutePreferences.isAvailable(server:)`: Voice Mode on
 * and `VoiceRoute.resolve` not unavailable). Reads the same shared keys.
 *
 * Approximation until `:feature:voice` ports `VoiceRoutePreferences`/`VoiceRoute`/`VoiceBroker`:
 * Android has no Voice Server choice (`voiceServerChoice`) yet, so the broker is the connected
 * server whenever Server Voice is on (iOS's default choice), and iOS's DEBUG lab-demo override is
 * left out.
 */
fun whatsNewVoiceAvailable(server: OpenCodeServer?): Boolean {
    if (!shared(SharedKeys.voiceModeEnabled).value) return false
    val engine = shared(VoiceSharedKeys.voiceEngine).value
    val hasOpenAIKey = !shared(SharedKeys.openAIAPIKey).value.isNullOrBlank()
    val hasXAIKey = !shared(SharedKeys.xaiAPIKey).value.isNullOrBlank()
    // Server Voice never owns Grok, including when Gemini is selected on the server.
    if (engine == VoiceEngine.grok) return hasXAIKey
    val serverVoice = ExperimentalFeature.serverVoice.isActive(shared(ExperimentalFeature.serverVoice.key).value)
    val broker = if (serverVoice) server else null
    val support = broker?.let { shared(VoiceSharedKeys.serverVoiceSupportByServer).value[it.persistenceID] }
        ?: ServerVoiceSupport.unknown
    if (serverVoice && shared(VoiceSharedKeys.serverVoiceProvider).value == ServerVoiceProvider.geminiLive) {
        return support != ServerVoiceSupport.unsupported
    }
    if (serverVoice && support != ServerVoiceSupport.unsupported) return true
    // Preserve the documented fallback only after a confirmed unsupported route.
    return hasOpenAIKey
}
