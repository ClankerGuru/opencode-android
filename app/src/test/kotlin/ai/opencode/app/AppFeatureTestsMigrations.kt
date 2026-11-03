package ai.opencode.app

import ai.opencode.core.experiments.ExperimentalCallStyle
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.experimental
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /**
     * Living Orb became the default: on the first launch after the update, before the restored
     * session reads its settings, a saved Floating Call and an untouched default move to it, a
     * Tray in use stays the Tray, and whatever is chosen afterwards sticks. A saved server
     * restores the session as the state is made, so it's the case that reads first.
     *
     * Android divergence: iOS's parameterized test is a loop here, and its
     * `home.livingOrbExperiment` check is n/a (Android's state reads the switch live).
     */
    @Test
    fun livingOrbBecomesTheDefaultOnceBeforeTheRestoredSessionReadsIt() {
        data class Case(val setup: String, val tray: Boolean, val legacyFloating: Boolean?, val expected: ExperimentalCallStyle)
        val cases = listOf(
            Case("untouched default", tray = false, legacyFloating = null, expected = ExperimentalCallStyle.floating),
            Case("saved Floating Call", tray = false, legacyFloating = true, expected = ExperimentalCallStyle.floating),
            Case("Floating Call switched off", tray = false, legacyFloating = false, expected = ExperimentalCallStyle.floating),
            Case("Tray", tray = true, legacyFloating = null, expected = ExperimentalCallStyle.tray),
            Case("Tray over Floating Call", tray = true, legacyFloating = true, expected = ExperimentalCallStyle.tray),
        )
        for (case in cases) {
            val shared = AppTestShared()
            val legacy = SharedKey.appStorage<Boolean?>(ExperimentalCallStyle.LEGACY_FLOATING_KEY, null)
            shared.run {
                val livingOrb = shared(experimental(ExperimentalFeature.livingOrb))
                val callTray = shared(experimental(ExperimentalFeature.callTray))
                // What earlier builds left: a written-off orb switch, and the tray as chosen.
                livingOrb.set(false)
                callTray.set(case.tray)
                if (case.legacyFloating != null) shared(legacy).set(case.legacyFloating)
                shared(ServerSharedKeys.savedServer).set(OpenCodeServer(url = "https://saved.example", username = "kit", password = "test"))

                migrateBeforeLaunch()
                val state = AppFeature.State.initial()
                assertNotNull("The saved server restores Home, which reads the call style", state.home)
                assertEquals(case.setup, case.expected, ExperimentalCallStyle.of(callTray = callTray.value, livingOrb = livingOrb.value))
                assertFalse(case.setup, shared.store.contains(legacy))

                // Once: Standard chosen afterwards stays Standard.
                livingOrb.set(false)
                callTray.set(false)
                migrateBeforeLaunch()
                assertEquals(case.setup, ExperimentalCallStyle.standard, ExperimentalCallStyle.of(callTray = callTray.value, livingOrb = livingOrb.value))
            }
        }
    }

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
