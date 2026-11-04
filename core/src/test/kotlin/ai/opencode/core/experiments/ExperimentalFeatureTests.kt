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

    /** The New Messages Divider is on for everyone who never touched its switch; someone who switched it off keeps it off. */
    @Test
    fun theNewMessagesDividerIsOnUntilSwitchedOff() {
        withFreshStorage {
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.newMessagesDivider))
            assertTrue(shared(experimental(ExperimentalFeature.newMessagesDivider)).value)
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.livingOrb))
            val onByDefault = setOf(ExperimentalFeature.newMessagesDivider, ExperimentalFeature.livingOrb)
            for (feature in ExperimentalFeature.allCases) {
                if (feature in onByDefault) continue
                assertFalse("$feature stays off", ExperimentalFeatures.liveValue(feature))
            }
        }
        withFreshStorage {
            dependency(SharedStore).save("experimental-new-messages-divider", false)
            assertFalse(ExperimentalFeatures.liveValue(ExperimentalFeature.newMessagesDivider))
        }
        assertFalse("tests opt in", ExperimentalFeatures.testValue(ExperimentalFeature.newMessagesDivider))
    }

    /** The divider moved to Settings › Sessions, Welcome Preview to Developer › Playgrounds; neither is in the pane. */
    @Test
    fun theDividerAndWelcomePreviewLeftExperimental() {
        assertEquals(
            listOf(ExperimentalFeature.newMessagesDivider, ExperimentalFeature.welcomePreview),
            ExperimentalFeature.allCases.filter { it.isGraduated },
        )
        assertFalse(ExperimentalGroup.entries.flatMap { it.features }.contains(ExperimentalFeature.newMessagesDivider))
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
                ExperimentalFeature.livingOrb, ExperimentalFeature.serverVoice,
                ExperimentalFeature.backgroundCallIntent, ExperimentalFeature.voiceSessionDelegator,
                ExperimentalFeature.shareScreenWithAgents, ExperimentalFeature.openLinksFromCalls, ExperimentalFeature.audioInCalls,
            ),
            ExperimentalGroup.calls.features,
        )
        assertEquals(listOf(ExperimentalFeature.imageLinkPreviews, ExperimentalFeature.inAppBrowser), ExperimentalGroup.transcript.features)
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
            ExperimentalFeature.removeRetiredSwitches(defaults)
            for (name in retired) {
                assertFalse(name, defaults.has("experimental-$name"))
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

    @Test
    fun livingOrbIsOnByDefault() {
        assertTrue(ExperimentalFeature.livingOrb.defaultEnabled)
        assertFalse("Its switch stays in Settings › Experimental › Calls", ExperimentalFeature.livingOrb.isGraduated)
        assertFalse("Reducer tests still start every experiment off", ExperimentalFeatures.testValue(ExperimentalFeature.livingOrb))
    }

    /**
     * Living Orb is the one call style besides Standard (p217): everyone moves to it once, a Tray
     * user included. Saved switches can't tell a choice from a default an earlier read wrote, so
     * Standard moves too, unless it was chosen after the Nov 1 move (that move's marker is set).
     * Each phase opens its own [SharedStore] over the same bytes, as each launch does.
     */
    @Test
    fun livingOrbOnceForEveryoneATrayUserIncluded() {
        val orb = "experimental-living-orb"
        val tray = ExperimentalFeature.legacyCallTrayKey
        val floating = ExperimentalFeature.legacyFloatingCallKey
        val nov1 = ExperimentalFeature.orbDefaultMigrationKey
        fun migrated(setup: (SharedStore) -> Unit): Pair<Boolean, Boolean> {
            val bytes = InMemorySharedBytes()
            setup(SharedStore(bytes))
            ExperimentalFeature.migrateCallStyles(SharedStore(bytes))
            val after = SharedStore(bytes)
            val livingOrb = after.reference(experimental(ExperimentalFeature.livingOrb)).value
            val leftover = after.has(floating) || after.has(tray) || after.has(nov1)
            return livingOrb to leftover
        }
        assertEquals("Never chose: Living Orb", true to false, migrated { })
        assertEquals("A written default or Standard: Living Orb", true to false, migrated { it.save(orb, false) })
        assertEquals("A Tray user takes Living Orb", true to false, migrated { it.save(tray, true) })
        assertEquals(true to false, migrated { it.save(tray, true); it.save(orb, false) })
        assertEquals("Floating Call moves to Living Orb", true to false, migrated { it.save(floating, true) })
        assertEquals(true to false, migrated { it.save(floating, false) })
        assertEquals(
            "The Tray kept on Nov 1 moves now",
            true to false,
            migrated { it.save(nov1, true); it.save(tray, true); it.save(orb, false) },
        )
        assertEquals("Standard chosen after Nov 1 stays", false to false, migrated { it.save(nov1, true); it.save(orb, false) })
        assertEquals(true to false, migrated { it.save(nov1, true) })

        // Once: a choice made afterwards sticks.
        val bytes = InMemorySharedBytes()
        ExperimentalFeature.migrateCallStyles(SharedStore(bytes))
        SharedStore(bytes).save(orb, false)
        ExperimentalFeature.migrateCallStyles(SharedStore(bytes))
        assertEquals(
            "Standard chosen after the move stays",
            false,
            SharedStore(bytes).reference(SharedKey.appStorage<Boolean?>(orb, null)).value,
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
