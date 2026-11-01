package ai.opencode.app

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.feature.settings.onboarding.WelcomeFeature

/*
 * Port of iOS `AppFeature+Welcome.swift`.
 *
 * The welcome's pairing requests (Welcome Preview), from the first run with no saved server.
 * They run the classic screen's pairing. iOS also takes them from Show Welcome over Home
 * (`home(.welcome(.presented(.delegate)))`), which waits for drawer's `HomeFeature.welcome`.
 */
internal val welcomeRequests = Reducer<AppFeature.State, AppFeature.Action> { state, action ->
    val request = ((action as? AppFeature.Action.Welcome)?.action as? WelcomeFeature.Action.Delegate)?.request
        ?: return@Reducer Reduced(state)
    welcome(request, state)
}

private fun welcome(request: WelcomeFeature.Request, state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> =
    when (request) {
        WelcomeFeature.Request.Scan -> cancelConnection(beginPairing(state.copy(pasteError = null)))
        WelcomeFeature.Request.EnterAddress -> cancelConnection(beginNewServer(state))
        is WelcomeFeature.Request.Copy -> Reduced(state, Effect.send(AppFeature.Action.CopyTextTapped(request.text)))
        WelcomeFeature.Request.Paste -> {
            val text = dependency(PasteboardClient).string().orEmpty()
            val problem = pasteProblem(text)
            val welcome = state.welcome.copy(
                pasteError = problem,
                isShowingOtherWays = if (problem == null) false else state.welcome.isShowingOtherWays,
            )
            val next = state.copy(welcome = welcome)
            if (problem != null) {
                dependency(HapticsClient).play(Haptic.error)
                Reduced(next)
            } else {
                Reduced(next, Effect.send(AppFeature.Action.PairingCodeScanned(text)))
            }
        }
    }
