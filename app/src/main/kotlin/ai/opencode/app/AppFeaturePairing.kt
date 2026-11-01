package ai.opencode.app

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.InstallHistory
import ai.opencode.core.store.Effect
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.notices.AccessibilityAnnouncer
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName

/*
 * Port of iOS `App/AppFeature+Pairing.swift`: where a pairing lands once its computer's sessions
 * load (t485). The install's first computer opens a ready session, another new computer opens
 * the drawer on its sessions, and re-pairing a known one stays put. Every pairing that lands
 * plays the handshake: `success` and, for TalkBack, the computer's name.
 */

/** iOS `AppFeature.PairingArrival`. */
enum class PairingArrival {
    /** This install's first computer ever: a new session in its latest project. */
    firstComputer,

    /** A computer no saved server is or shares an address with. */
    newComputer,
    knownComputer,
    ;

    companion object {
        fun of(server: OpenCodeServer, saved: List<OpenCodeServer>, history: InstallHistory): PairingArrival = when {
            saved.isEmpty() && history != InstallHistory.paired -> firstComputer
            server.isNewComputer(saved) -> newComputer
            else -> knownComputer
        }
    }
}

/**
 * The pairing's Home just loaded. Anything the person asked for on the way in ([asked]: a share,
 * notification or system action) wins over where the pairing would take them; the handshake
 * plays either way.
 *
 * Partial: the first computer's ready session (iOS `HomeFeature.firstComputerPaired`, starter
 * prompts) is `:feature:drawer`'s to port; until then it opens the drawer like any new computer.
 */
internal fun landPairing(arrival: PairingArrival?, server: OpenCodeServer, asked: Boolean): Effect<AppFeature.Action> {
    if (arrival == null) return Effect.none()
    dependency(HapticsClient).play(Haptic.success)
    dependency(AccessibilityAnnouncer).announce("Connected to ${server.friendlyName}", false)
    if (asked) return Effect.none()
    return when (arrival) {
        PairingArrival.firstComputer, PairingArrival.newComputer ->
            Effect.send(AppFeature.Action.Home(HomeFeature.Action.PairedComputerOpened))
        PairingArrival.knownComputer -> Effect.none()
    }
}
