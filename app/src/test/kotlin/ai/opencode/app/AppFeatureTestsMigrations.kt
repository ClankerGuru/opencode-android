package ai.opencode.app

import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `AppFeatureTests+Migrations.swift`: preferences earlier builds saved, moved once as
 * the app starts (`AppFeatureMigrations.kt`). `theOldConsoleSignInLeavesTheKeychainOnce` is
 * dropped: no Android build kept an OpenCode Console sign-in. Each test's in-memory
 * `SharedStore` stands in for iOS's throwaway `UserDefaults` suite.
 */
class AppFeatureTestsMigrations {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()

    /** The voice orb is Wisp's alone: a saved orb style is dropped at launch. */
    @Test
    fun aSavedOrbStyleIsDropped() = runTest {
        val style = SharedKey.appStorage(RETIRED_ORB_STYLE_KEY, "")
        shared.run { shared(style).set("silkRibbons") }
        val store = shared.appStore(shared.appState(), detachedScope(coroutineContext))

        store.send(AppFeature.Action.Task)
        assertFalse(shared.store.contains(style))
        store.finish()
    }
}
