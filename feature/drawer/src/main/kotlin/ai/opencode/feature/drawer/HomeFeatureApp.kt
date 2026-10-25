package ai.opencode.feature.drawer

import ai.opencode.feature.drawer.HomeFeature.State

/*
 * What `AppFeature` does to a Home it builds or replaces (iOS sets these fields directly).
 */

/**
 * A switch made from Settings stays in Settings (iOS `home?.isSettingsShown = keepsSettings` on a
 * new Home): the new server's Home opens Settings where [previous] had it, told about this Home's
 * connection, so the connection card shows the new server arrive.
 */
fun State.keepingSettings(previous: State?): State {
    val settings = previous?.settings ?: return this
    return presentingSettings(settings)
}
