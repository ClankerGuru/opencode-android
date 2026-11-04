package ai.opencode.app

import ai.opencode.core.experiments.legacyCallTrayKey
import ai.opencode.core.experiments.legacyFloatingCallKey
import ai.opencode.core.experiments.orbDefaultMigrationKey
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
     * Living Orb is the one call style besides Standard (p217): on the first launch after the
     * update, before the restored session reads its settings, everyone takes it once, a Tray user
     * included, unless they chose a style after the Nov 1 move; whatever is chosen afterwards
     * sticks. A saved server restores the session as the state is made, so it's the case that
     * reads first.
     *
     * Android divergence: iOS's parameterized test is a loop here, and its
     * `home.livingOrbExperiment` check is n/a (Android's state reads the switch live).
     */
    @Test
    fun livingOrbOnceBeforeTheRestoredSessionReadsIt() {
        data class Case(
            val setup: String,
            val orb: Boolean?,
            val tray: Boolean?,
            val legacyFloating: Boolean?,
            val movedNov1: Boolean,
            val expected: Boolean,
        )
        val cases = listOf(
            Case("untouched default", orb = false, tray = null, legacyFloating = null, movedNov1 = false, expected = true),
            Case("saved Floating Call", orb = false, tray = null, legacyFloating = true, movedNov1 = false, expected = true),
            Case("Floating Call switched off", orb = false, tray = false, legacyFloating = false, movedNov1 = false, expected = true),
            Case("Tray", orb = false, tray = true, legacyFloating = null, movedNov1 = false, expected = true),
            Case("Tray kept on Nov 1", orb = false, tray = true, legacyFloating = null, movedNov1 = true, expected = true),
            Case("Living Orb since Nov 1", orb = null, tray = false, legacyFloating = null, movedNov1 = true, expected = true),
            Case("Standard chosen since Nov 1", orb = false, tray = false, legacyFloating = null, movedNov1 = true, expected = false),
        )
        val legacyKeys = listOf(
            ExperimentalFeature.legacyFloatingCallKey,
            ExperimentalFeature.legacyCallTrayKey,
            ExperimentalFeature.orbDefaultMigrationKey,
        )
        for (case in cases) {
            val shared = AppTestShared()
            shared.run {
                // iOS `defaults.set(_:forKey:)`: raw saved values, `false` included.
                fun save(id: String, value: Boolean) = shared(SharedKey.appStorage<Boolean?>(id, null)).set(value)
                if (case.orb != null) shared(experimental(ExperimentalFeature.livingOrb)).set(case.orb)
                if (case.tray != null) save(ExperimentalFeature.legacyCallTrayKey, case.tray)
                if (case.legacyFloating != null) save(ExperimentalFeature.legacyFloatingCallKey, case.legacyFloating)
                if (case.movedNov1) save(ExperimentalFeature.orbDefaultMigrationKey, true)
                shared(ServerSharedKeys.savedServer).set(OpenCodeServer(url = "https://saved.example", username = "kit", password = "test"))

                migrateBeforeLaunch()
                val state = AppFeature.State.initial()
                assertNotNull("The saved server restores Home, which reads the call style", state.home)
                val livingOrb = shared(experimental(ExperimentalFeature.livingOrb))
                assertEquals(case.setup, case.expected, livingOrb.value)
                for (key in legacyKeys) {
                    assertFalse("${case.setup}: $key", shared.store.contains(SharedKey.appStorage<Boolean?>(key, null)))
                }

                // Once: Standard chosen afterwards stays Standard.
                livingOrb.set(false)
                migrateBeforeLaunch()
                assertFalse(case.setup, livingOrb.value)
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
