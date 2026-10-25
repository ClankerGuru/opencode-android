package ai.opencode.feature.session.seams

import ai.opencode.core.dependencies.DependencyKey

// Stand-ins for :feature:system clients `SessionFeature` uses that no module has ported yet.
// Each carries only what Session uses.

// region System clients

/**
 * SEAM: real client belongs to :feature:system (iOS `SceneWindowClient`, which tracks the app's
 * scenes and which one is frontmost); delete when that area ports it.
 */
class SceneWindowClient {
    companion object Key : DependencyKey<SceneWindowClient> {
        override val liveValue = SceneWindowClient()
        override val testValue = SceneWindowClient()
    }
}

/**
 * SEAM: real member belongs to :feature:system (iOS `SceneWindowClient.isFront`): whether this
 * screen's window is the frontmost one. Stand-in: one window, always in front.
 */
@Suppress("UnusedReceiverParameter")
val SceneWindowClient.isFront: Boolean get() = true

// endregion
