package ai.opencode.feature.drawer

import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.bridge.SessionDetail
import ai.opencode.feature.drawer.bridge.SessionDraftState
import ai.opencode.feature.drawer.bridge.WhatsNewAction
import ai.opencode.feature.drawer.catchup.CatchUpFeature

/*
 * Port of iOS `HomeFeature+Settings.swift`: What's New's Try It, which leaves the page and the
 * drawer for the session, then opens where the feature is.
 */

/**
 * iOS `WhatsNewEntry.Destination`: where an entry's Try It goes.
 * TODO(merge): replace with `:feature:settings`' `WhatsNewEntry.Destination`; the bridge's
 * `WhatsNewAction.TryTapped` carries it as a string ([of] reads it).
 */
sealed interface WhatsNewDestination {
    /** A new session. */
    data object NewSession : WhatsNewDestination
    /** A new session with this prompt typed in its composer, not sent. */
    data class Prompt(val text: String) : WhatsNewDestination
    /** The open session's model list. */
    data object ModelList : WhatsNewDestination
    /** A call in the open session; Voice's settings while Voice isn't set up. */
    data object Call : WhatsNewDestination
    /** A Settings page, pushed on What's New's own stack. */
    data class Open(val page: String) : WhatsNewDestination

    companion object {
        /** `newSession`, `modelList`, `call`, `prompt:<text>`, `open:<page>`. */
        fun of(raw: String): WhatsNewDestination = when {
            raw == "newSession" -> NewSession
            raw == "modelList" -> ModelList
            raw == "call" -> Call
            raw.startsWith("prompt:") -> Prompt(raw.removePrefix("prompt:"))
            else -> Open(raw.removePrefix("open:"))
        }
    }
}

/** Home's What's New Try It (`WhatsNewAction.TryTapped`). */
internal fun HomeFeature.reduceSettings(state: State, action: WhatsNewAction.TryTapped): HomeReduced =
    tryWhatsNew(WhatsNewDestination.of(action.destination), action.entryID, state)

/**
 * What's New's Try It: leaves the page and the drawer for the session, then opens where the
 * feature is, through the paths the drawer, Catch Up and the call button use. It only navigates;
 * nothing is sent. What can't go now keeps the page: a new session while offline, and a call
 * before Voice is set up (its settings push on the page instead).
 */
@Suppress("UNUSED_PARAMETER")
internal fun HomeFeature.tryWhatsNew(destination: WhatsNewDestination, entryID: String, state: State): HomeReduced {
    when (destination) {
        // Settings pages push on What's New's own stack (WhatsNewFeature).
        is WhatsNewDestination.Open -> return Reduced(state)
        WhatsNewDestination.NewSession, is WhatsNewDestination.Prompt ->
            if (state.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected) return Reduced(state)
        // iOS: `guard state.voicePreferences.isAvailable(server:)` else
        // `state.whatsNew.settingsPage = .init(destination: .voice, entryID:)`.
        // TODO(merge): Voice isn't ported, so it's never set up; What's New's own page stack (and
        // its `settingsPage`) belongs to `:feature:settings`. The page stays.
        WhatsNewDestination.Call -> return Reduced(state)
        WhatsNewDestination.ModelList -> Unit
    }
    var s = state.copy(isSettingsShown = false, drawerCloseRequests = state.drawerCloseRequests + 1)
    return when (destination) {
        WhatsNewDestination.NewSession -> reduceDrawer(s, Action.NewSessionTapped)
        is WhatsNewDestination.Prompt -> {
            // There's one new-session draft. Words already in it stay, and the prompt goes below
            // them; New Session over a draft would clear it.
            val detail = s.sessionDetail
            if (s.catchUp == null && s.subagentStack.isEmpty() && detail != null && detail.isDraft && detail.readsOnView &&
                !detail.sessionComposer.isEmpty
            ) {
                return Reduced(s.updatingDetail { it.typingBelow(destination.text) })
            }
            val opened = reduceDrawer(s, Action.NewSessionTapped)
            Reduced(opened.state.updatingDetail { it.typingBelow(destination.text) }, opened.effect)
        }
        WhatsNewDestination.ModelList, WhatsNewDestination.Call -> {
            // The screen the reader comes back to: Catch Up's card opened as the session (or, with
            // no card left, where Catch Up returns), and no in-app page over it.
            val effects = mutableListOf<Effect<Action>>()
            if (s.catchUp != null) {
                val opened = reduceCatchUp(s, CatchUpFeature.Action.OpenTapped)
                // Onto the session itself: Try It shows the feature there, not the drawer.
                s = opened.state.returningFromCatchUp()
                effects += opened.effect
            }
            if (s.sessionDetail == null) s = s.openingDraft(reason = "What’s New’s Try It")
            // TODO(merge): close the top screen's in-app browser (`.browser(.closeTapped)`) once
            // `:feature:media` ports it.
            if (destination == WhatsNewDestination.Call) {
                // A pushed subagent's call belongs to the root, aimed at the top.
                // TODO(merge): `startCallOnTop` for a pushed subagent once `:feature:voice` ports.
                val call = startSystemCall(s)
                s = call.state
                effects += call.effect
            }
            // TODO(merge): otherwise send the top screen `.modelButtonTapped` (the model list) once
            // `:feature:session` ports it.
            Reduced(s, Effect.concatenate(effects))
        }
        is WhatsNewDestination.Open -> Reduced(s)
    }
}

/**
 * iOS `SessionFeature.State.typeBelow`: text the app adds below what's already typed, never over
 * it, and once: a repeat tap finds it there. TODO(merge): `SessionFeature`'s (it leaves a live
 * recording's words alone).
 */
private fun SessionDetail.typingBelow(text: String): SessionDetail {
    val added = text.trim()
    val current = draft.text
    return when {
        current.isEmpty() -> copy(draft = SessionDraftState(text))
        added.isNotEmpty() && !current.contains(added) -> copy(draft = SessionDraftState("$current\n\n$text"))
        else -> this
    }
}
