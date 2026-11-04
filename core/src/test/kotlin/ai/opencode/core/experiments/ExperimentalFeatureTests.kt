package ai.opencode.core.experiments

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.InMemorySharedBytes
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.experimental
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `ExperimentalFeatureTests`. `theSessionsPageDividerSwitchKeepsTheSavedChoice`
 * drives `HomeSettingsFeature`, so it's in `:feature:settings`' `ExperimentalFeatureTests`.
 */
class ExperimentalFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    /** iOS `withDependencies { $0.defaultAppStorage = fresh }`: a fresh test container's storage. */
    private fun <R> withFreshStorage(block: () -> R): R = DependencyValues.withValues(DependencyValues.testing(), block)

    /**
     * iOS `defaults.set(_:forKey:)` on a fresh suite: a raw saved value, written even when it
     * equals the key's default (a nullable key, so `false` is stored rather than skipped).
     */
    private fun SharedStore.save(id: String, value: Boolean) = reference(SharedKey.appStorage<Boolean?>(id, null)).set(value)

    /** iOS `defaults.object(forKey:) != nil`. */
    private fun SharedStore.has(id: String) = contains(SharedKey.appStorage<Boolean?>(id, null))

    /** iOS `defaults.bool(forKey:)`. */
    private fun SharedStore.bool(id: String) = reference(SharedKey.appStorage(id, false)).value

    @Test
    fun experimentsAreOffByDefaultAndPersistUnderAStableKey() {
        withFreshStorage {
            assertFalse(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
            val inAppBrowser = shared(experimental(ExperimentalFeature.inAppBrowser))
            assertFalse(inAppBrowser.value)
            inAppBrowser.set(true)
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
            assertEquals("The key is the phone's saved choice: never rename it", "experimental-in-app-browser", inAppBrowser.key.id)
            assertTrue(dependency(SharedStore).has("experimental-in-app-browser"))
        }
        assertFalse(ExperimentalFeatures.testValue(ExperimentalFeature.inAppBrowser))
        assertTrue(ExperimentalFeatures.enabling(setOf(ExperimentalFeature.inAppBrowser))(ExperimentalFeature.inAppBrowser))
    }

    /** No experiment starts on: graduated features have no switch at all. */
    @Test
    fun everyExperimentIsOffUntilSwitchedOn() {
        withFreshStorage {
            for (feature in ExperimentalFeature.allCases) {
                assertFalse("$feature starts off", ExperimentalFeatures.liveValue(feature))
            }
        }
    }

    /** Welcome Preview moved to Developer › Playgrounds; it's not in the pane. */
    @Test
    fun welcomePreviewLeftExperimental() {
        assertEquals(listOf(ExperimentalFeature.welcomePreview), ExperimentalFeature.allCases.filter { it.isGraduated })
        assertFalse(ExperimentalGroup.entries.flatMap { it.features }.contains(ExperimentalFeature.welcomePreview))
    }

    @Test
    fun everyExperimentHasACopyForSettings() {
        for (feature in ExperimentalFeature.allCases) {
            assertTrue(feature.title.isNotEmpty())
            assertTrue(feature.summary.isNotEmpty())
            assertFalse("One line, no trailing period", feature.summary.endsWith("."))
            assertTrue("A few words: ${feature.summary}", feature.summary.split(" ").filter { it.isNotEmpty() }.size <= 6)
        }
        assertEquals("In-App Browser", ExperimentalFeature.inAppBrowser.title)
    }

    @Test
    fun everyExperimentSitsInOneGroupInAStableOrder() {
        assertEquals(listOf(ExperimentalGroup.composer, ExperimentalGroup.calls, ExperimentalGroup.transcript, ExperimentalGroup.drawer), ExperimentalGroup.entries)
        assertEquals(
            ExperimentalFeature.allCases.filter { !it.isGraduated }.toSet(),
            ExperimentalGroup.entries.flatMap { it.features }.toSet(),
        )
        assertEquals(
            listOf(ExperimentalFeature.onePicker, ExperimentalFeature.agentSelection, ExperimentalFeature.hideModels),
            ExperimentalGroup.composer.features,
        )
        assertEquals(
            listOf(
                ExperimentalFeature.serverVoice, ExperimentalFeature.backgroundCallIntent, ExperimentalFeature.voiceSessionDelegator,
                ExperimentalFeature.shareScreenWithAgents, ExperimentalFeature.openLinksFromCalls, ExperimentalFeature.audioInCalls,
            ),
            ExperimentalGroup.calls.features,
        )
        assertEquals(listOf(ExperimentalFeature.inAppBrowser), ExperimentalGroup.transcript.features)
        assertEquals(
            listOf(
                ExperimentalFeature.inboxSidebar, ExperimentalFeature.serverDetails,
                ExperimentalFeature.sessionLiveActivity, ExperimentalFeature.soundEffects,
            ),
            ExperimentalGroup.drawer.features,
        )
        assertEquals(listOf("Composer", "Calls", "Transcript", "Drawer"), ExperimentalGroup.entries.map { it.title })
    }

    /** Removed experiments and ones now always on (p217) leave no switch behind, whatever was saved. */
    @Test
    fun removedExperimentsLeaveNoSwitchBehind() {
        val retired = listOf(
            "session-switcher", "server-title-switch",
            "composer-autocomplete", "server-flow", "plus-menu-sheet", "project-icons",
            "floating-call", "call-tray", "image-link-previews", "living-orb", "new-messages-divider",
        )
        val leftovers = listOf(
            "experimental-all-saved-living-orb", "experimental-all-skip-image-link-previews",
            "call-style-migrated-living-orb", "call-style-migrated-orb-default",
        )
        withFreshStorage {
            val defaults = dependency(SharedStore)
            for ((index, name) in retired.withIndex()) {
                defaults.save("experimental-$name", index % 2 == 0)
            }
            defaults.save("experimental-in-app-browser", true)
            for (name in retired) {
                assertNull(name, ExperimentalFeature.fromRawValue(name))
            }
            for (key in leftovers) {
                defaults.save(key, true)
            }
            ExperimentalFeature.removeRetiredSwitches(defaults)
            for (name in retired) {
                assertFalse(name, defaults.has("experimental-$name"))
            }
            for (key in leftovers) {
                assertFalse(key, defaults.has(key))
            }
            assertTrue("Live experiments keep their switch", defaults.bool("experimental-in-app-browser"))
        }
    }

    @Test
    fun aSavedSheetOrGrowBecomesOnePickerOnce() {
        fun migrated(setup: (SharedStore) -> Unit): Pair<Boolean, Boolean> = withFreshStorage {
            val defaults = dependency(SharedStore)
            setup(defaults)
            ExperimentalFeature.migrateModelPicker(defaults)
            val keys = listOf(LEGACY_MODEL_PICKER_KEY) + legacyModelPickerSwitchKeys
            var leftover = false
            for (key in keys) {
                if (defaults.contains(SharedKey.appStorage<String?>(key, null))) leftover = true
            }
            defaults.bool("experimental-one-picker") to leftover
        }
        fun SharedStore.saveStyle(style: String) = reference(SharedKey.appStorage<String?>(LEGACY_MODEL_PICKER_KEY, null)).set(style)

        assertEquals(false to false, migrated { })
        assertEquals(false to false, migrated { it.saveStyle("today") })
        for (style in listOf("sheet", "one-picker", "grow-from-composer")) {
            assertEquals(style, true to false, migrated { it.saveStyle(style) })
        }
        for (key in legacyModelPickerSwitchKeys) {
            assertEquals(key, true to false, migrated { it.save(key, true) })
            assertEquals(key, false to false, migrated { it.save(key, false) })
        }
        assertEquals(
            "One Picker already on stays on",
            true to false,
            migrated {
                it.saveStyle("today")
                it.save("experimental-one-picker", true)
            },
        )
    }

    /** `VoiceAudioDemoMode` lives in `:feature:voice`; its half of this test belongs there. */
    @Test
    fun voiceDemoModeRequiresDeveloperMode() {
        withFreshStorage {
            val developerMode = shared(SharedKeys.developerMode)
            val inAppBrowser = shared(experimental(ExperimentalFeature.inAppBrowser))
            val demoMode = shared(SharedKeys.voiceDemoMode)
            inAppBrowser.set(true)
            demoMode.set(true)
            developerMode.set(false)
            assertTrue(ExperimentalFeature.inAppBrowser.isActive(true))
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
        }
    }
}
