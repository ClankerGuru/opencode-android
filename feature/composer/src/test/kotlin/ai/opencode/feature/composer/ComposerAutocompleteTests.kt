package ai.opencode.feature.composer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.chip
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.sampleAgents
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.sampleCommands
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.sampleFiles
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.sampleSkills
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.session
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.state
import ai.opencode.feature.composer.ComposerAutocompleteTestHost.Action
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeAgentMention
import ai.opencode.server.model.OpenCodeFileMention
import ai.opencode.server.model.OpenCodeMention
import ai.opencode.server.model.OpenCodeSkillMention
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * The autocomplete half of it is the pure [ComposerAutocompleteReducer] the session store hosts, so
 * these tests drive it through [ComposerAutocompleteTestHost] (a draft binding plus `.autocomplete`
 * actions, as `SessionFeatureBindings.kt` wires them) with the same inputs and expected state.
 *
 * Partly dropped:
 * - `caretAwareTriggerDetectionAndAutoRunCancellationWorkCleanly`: step 2 (auto-run) sets the
 *   `composer.autocomplete.commandAction` tunable to Run through the Tuning Panel; Android bakes
 *   the tunables (Fill), so a chosen command never runs by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerAutocompleteTests {
    private val directory = session.directory

    private fun TestScope.makeStore(
        initial: ComposerAutocompleteTestHost.State = state(),
        clock: Clock = TestClock(),
        client: OpenCodeClient.() -> OpenCodeClient = { this },
    ) = TestStore(initial, ComposerAutocompleteTestHost(), this) {
        this[UuidClient] = UuidClient.incrementing()
        this[Clock] = clock
        this[OpenCodeClient] = OpenCodeClient.testValue.client()
    }

    private fun syncMention(simulation: AutocompleteSimulation, query: String = "", items: List<AutocompleteItem>, width: Double) {
        simulation.sync(
            isOpen = true,
            mode = AutocompleteTrigger.Mode.Mention,
            query = query,
            key = "at:0",
            items = items,
            selectedRowID = items.firstOrNull()?.id,
            userMoved = false,
            composerWidth = width,
        )
    }

    @Test fun triggerDetectionMatchesWordStartForAtAndMessageStartForSlash() {
        assertEquals(
            AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "kit", key = "at:0", utf16Start = 0, utf16End = 4),
            AutocompleteTrigger.detect("@kit"),
        )
        assertEquals(
            AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "Ses", key = "at:6", utf16Start = 6, utf16End = 10),
            AutocompleteTrigger.detect("Check @Ses"),
        )
        assertNull("Mid-word @ is not a mention trigger", AutocompleteTrigger.detect("email@example.com"))
        assertEquals(
            AutocompleteTrigger(AutocompleteTrigger.Mode.Command, query = "rev", key = "slash:0", utf16Start = 0, utf16End = 4),
            AutocompleteTrigger.detect("/rev"),
        )
        assertNull("Slash after words is not a command trigger", AutocompleteTrigger.detect("Check /rev"))
        assertNull("Space after a token with no matches dismisses trigger detection", AutocompleteTrigger.detect("@zzzznotamatch "))
    }

    @Test fun typingAtLoadsCatalogFiltersAndCancelsStaleFileSearches() = runTest {
        val clock = TestClock()
        val findQueries = mutableListOf<String>()
        val sessionSearches = mutableListOf<String>()
        val staleGate = CompletableDeferred<Unit>()
        val sessionsGate = CompletableDeferred<Unit>()

        val store = makeStore(clock = clock) {
            copy(
                mentionSessions = { _, search, limit ->
                    assertEquals(50, limit)
                    sessionSearches += search
                    sessionsGate.await()
                    emptyList()
                },
                skills = { _, _ -> sampleSkills },
                agents = { _, _ -> sampleAgents },
                commands = { _, _ -> sampleCommands },
                findFiles = { _, _, query, _ ->
                    findQueries += query
                    if (query == "k") staleGate.await()
                    sampleFiles
                },
            )
        }

        val triggerAt = AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "", key = "at:0", utf16Start = 0, utf16End = 1)
        store.send(Action.Draft("@")) {
            it.copy(draft = "@").autocomplete {
                copy(
                    activeTrigger = triggerAt,
                    isLoadingCatalog = true,
                    loadingCatalogDirectoryKey = directory,
                    fileSearchGeneration = 1,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "",
                    sessionSearch = "",
                )
            }
        }

        val visibleAgents = sampleAgents.filter { !it.hidden && it.mode != "primary" }
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.CatalogLoaded(directory, sampleSkills, sampleAgents))) {
            it.autocomplete {
                copy(
                    isLoadingCatalog = false,
                    loadingCatalogDirectoryKey = null,
                    loadedCatalogDirectoryKey = directory,
                    skills = sampleSkills,
                    agents = visibleAgents,
                ).recomputeRows()
            }
        }
        sessionsGate.complete(Unit)
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "", sessions = emptyList()))) {
            it.autocomplete { copy(answeredSessionSearch = "") }
        }

        clock.advance(120.milliseconds)
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.FilesLoaded(generation = 1, directory = directory, query = "", files = sampleFiles))) {
            it.autocomplete { copy(isSearchingFiles = false, files = sampleFiles).recomputeRows() }
        }

        assertTrue(store.state.autocomplete.isOpen)
        assertFalse(store.state.autocomplete.rows.any { it.id == "agent:compaction" })

        // Type "@k", let the debounce fire so findFiles("k") is in flight, then type "@kit" before "k" completes.
        val triggerK = AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "k", key = "at:0", utf16Start = 0, utf16End = 2)
        store.send(Action.Draft("@k")) {
            it.copy(draft = "@k").autocomplete {
                copy(
                    activeTrigger = triggerK,
                    fileSearchGeneration = 2,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "k",
                    sessionSearch = "k",
                ).recomputeRows()
            }
        }
        clock.advance(100.milliseconds)
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "k", sessions = emptyList()))) {
            it.autocomplete { copy(answeredSessionSearch = "k") }
        }
        clock.advance(20.milliseconds)

        val triggerKit = AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "kit", key = "at:0", utf16Start = 0, utf16End = 4)
        store.send(Action.Draft("@kit")) {
            it.copy(draft = "@kit").autocomplete {
                copy(
                    activeTrigger = triggerKit,
                    fileSearchGeneration = 3,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "kit",
                    sessionSearch = "kit",
                ).recomputeRows()
            }
        }
        staleGate.complete(Unit)
        clock.advance(100.milliseconds)
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "kit", sessions = emptyList()))) {
            it.autocomplete { copy(answeredSessionSearch = "kit") }
        }
        clock.advance(20.milliseconds)
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.FilesLoaded(generation = 3, directory = directory, query = "kit", files = sampleFiles))) {
            it.autocomplete { copy(isSearchingFiles = false, files = sampleFiles).recomputeRows() }
        }

        assertEquals(listOf("", "k", "kit"), findQueries)
        assertEquals(listOf("", "k", "kit"), sessionSearches)
        assertEquals("skill:kit-motion", store.state.autocomplete.rows.firstOrNull()?.id)

        // A non-matching token and a space wait for the session search, then close as prose.
        store.send(Action.Draft("@zzzznotamatch ")) {
            it.copy(draft = "@zzzznotamatch ").autocomplete {
                copy(
                    activeTrigger = AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "zzzznotamatch ", key = "at:0", utf16Start = 0, utf16End = 15),
                    sessionSearch = "zzzznotamatch",
                ).recomputeRows()
            }
        }
        assertFalse(store.state.autocomplete.isOpen)
        clock.advance(100.milliseconds)
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "zzzznotamatch", sessions = emptyList()))) {
            it.autocomplete { copy(answeredSessionSearch = "zzzznotamatch").closePopover(dismissed = false) }
        }
        store.finish()
    }

    @Test fun hardwareKeyboardNavigationChoosingRowAndDismissalWorkCleanly() = runTest {
        val store = makeStore(
            initial = state { autocomplete { copy(loadedCommandsDirectoryKey = directory, commands = sampleCommands) } },
        )

        val trigger = AutocompleteTrigger(AutocompleteTrigger.Mode.Command, query = "", key = "slash:0", utf16Start = 0, utf16End = 1)
        store.send(Action.Draft("/")) {
            it.copy(draft = "/").autocomplete { copy(activeTrigger = trigger).recomputeRows() }
        }

        assertEquals("command:review", store.state.autocomplete.selectedRowID)
        // In bottom order, ↑ (-1) steps to index 1 ("command:compact"), and ↓ (+1) steps back to index 0 ("command:review").
        store.send(Action.Autocomplete(ComposerAutocompleteAction.MoveHighlight(-1))) {
            it.autocomplete { copy(userMovedSelection = true, selectedRowID = "command:compact") }
        }
        store.send(Action.Autocomplete(ComposerAutocompleteAction.MoveHighlight(1))) {
            it.autocomplete { copy(userMovedSelection = true, selectedRowID = "command:review") }
        }

        // Dismiss via Escape / outside tap.
        store.send(Action.Autocomplete(ComposerAutocompleteAction.Dismissed)) {
            it.autocomplete { closePopover(dismissed = true) }
        }
        assertFalse(store.state.autocomplete.isOpen)
        assertEquals("slash:0", store.state.autocomplete.dismissedKey)

        // Backspacing past the trigger clears dismissedKey.
        store.send(Action.Draft("")) {
            it.copy(draft = "").autocomplete { copy(dismissedKey = null) }
        }

        // Re-open "/" and confirm selection (Return/Tab) to insert a chip.
        store.send(Action.Draft("/rev")) {
            it.copy(draft = "/rev").autocomplete {
                copy(activeTrigger = AutocompleteTrigger(AutocompleteTrigger.Mode.Command, query = "rev", key = "slash:0", utf16Start = 0, utf16End = 4))
                    .recomputeRows()
            }
        }
        val expectedChip = chip(0, AutocompleteItem.command(sampleCommands[0]))
        store.send(Action.Autocomplete(ComposerAutocompleteAction.ConfirmSelection)) {
            it.copy(draft = "/review ").autocomplete {
                copy(
                    chips = listOf(expectedChip),
                    chooseGeneration = 1,
                    lastChosenChip = expectedChip,
                    lastChosenRowID = "command:review",
                ).closePopover(dismissed = false)
            }
        }
        assertFalse(store.state.autocomplete.isOpen)
        assertEquals(1, store.state.autocomplete.chips.size)
        store.finish()
    }

    @Test fun choosingChipsMapsSkillFileAndAgentMentionsIntoPromptResolution() {
        val skillChip = chip(101, AutocompleteItem.skill(sampleSkills[0]))
        val fileChip = chip(102, AutocompleteItem.file(sampleFiles[0]))
        val agentChip = chip(103, AutocompleteItem.agent(sampleAgents[0]))

        val draft = "Use @kit-motion on @SessionView.swift with @explore"
        val resolution = ComposerAutocompleteDraftResolution.resolve(draft = draft, chips = listOf(skillChip, fileChip, agentChip))

        val augmentation = (resolution as? ComposerAutocompleteDraftResolution.Prompt)?.augmentation
            ?: return fail("Expected prompt augmentation")
        assertEquals(
            listOf(OpenCodeSkillMention(id = "kit-motion", mention = OpenCodeMention(text = "@kit-motion", start = 4, end = 15))),
            augmentation.mentions.skills,
        )
        assertEquals(
            listOf(OpenCodeAgentMention(name = "explore", mention = OpenCodeMention(text = "@explore", start = 43, end = 51))),
            augmentation.mentions.agents,
        )
        assertEquals(
            listOf(
                OpenCodeFileMention(
                    uri = "file:///Users/kit/code/opencode-ios/OpenCode/SessionView.swift",
                    name = "SessionView.swift",
                    description = "OpenCode/SessionView.swift",
                    mention = OpenCodeMention(text = "@SessionView.swift", start = 19, end = 37),
                ),
            ),
            augmentation.mentions.files,
        )
        // Out-of-range spans are dropped cleanly.
        val truncated = ComposerAutocompleteDraftResolution.resolve(
            draft = draft,
            sentPromptText = "Use @kit-motion",
            chips = listOf(skillChip, fileChip, agentChip),
        )
        val truncatedAugmentation = (truncated as? ComposerAutocompleteDraftResolution.Prompt)?.augmentation
            ?: return fail("Expected prompt augmentation")
        assertEquals(1, truncatedAugmentation.mentions.skills.size)
        assertTrue(truncatedAugmentation.mentions.files.isEmpty())
        assertTrue(truncatedAugmentation.mentions.agents.isEmpty())
    }

    @Test fun choosingCommandChipResolvesSlashCommandPayload() {
        val commandChip = chip(201, AutocompleteItem.command(sampleCommands[0]))
        val resolution = ComposerAutocompleteDraftResolution.resolve(draft = "/review check main", chips = listOf(commandChip))
        val command = (resolution as? ComposerAutocompleteDraftResolution.Command)?.execution
            ?: return fail("Expected slash command execution")
        assertEquals("review", command.name)
        assertEquals("check main", command.argumentsText)
    }

    @Test fun catalogAndCommandLoadsUseSeparateCancelIDsWithoutWedgingLoadingFlags() = runTest {
        val catalogGate = CompletableDeferred<Unit>()

        val store = makeStore {
            copy(
                skills = { _, _ ->
                    catalogGate.await()
                    sampleSkills
                },
                agents = { _, _ ->
                    catalogGate.await()
                    sampleAgents
                },
                commands = { _, _ -> sampleCommands },
                findFiles = { _, _, _, _ -> emptyList() },
                mentionSessions = { _, _, _ -> awaitCancellation() },
            )
        }

        // Start `@` catalog load.
        store.send(Action.Draft("@")) {
            it.copy(draft = "@").autocomplete {
                copy(
                    activeTrigger = AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "", key = "at:0", utf16Start = 0, utf16End = 1),
                    isLoadingCatalog = true,
                    loadingCatalogDirectoryKey = directory,
                    fileSearchGeneration = 1,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "",
                    sessionSearch = "",
                )
            }
        }

        // Switch to `/` while catalog is still in flight; command load must not cancel catalog or wedge `isLoadingCatalog`; it calls off the session search.
        store.send(Action.Draft("/")) {
            it.copy(draft = "/").autocomplete {
                copy(
                    activeTrigger = AutocompleteTrigger(AutocompleteTrigger.Mode.Command, query = "", key = "slash:0", utf16Start = 0, utf16End = 1),
                    isSearchingFiles = false,
                    lastFileSearchQuery = null,
                    sessionSearch = null,
                    isLoadingCommands = true,
                    loadingCommandsDirectoryKey = directory,
                )
            }
        }

        store.receive(Action.Autocomplete(ComposerAutocompleteAction.CommandsLoaded(directory, sampleCommands))) {
            it.autocomplete {
                copy(
                    isLoadingCommands = false,
                    loadingCommandsDirectoryKey = null,
                    loadedCommandsDirectoryKey = directory,
                    commands = sampleCommands,
                ).recomputeRows()
            }
        }

        catalogGate.complete(Unit)
        val visibleAgents = sampleAgents.filter { !it.hidden && it.mode != "primary" }
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.CatalogLoaded(directory, sampleSkills, sampleAgents))) {
            it.autocomplete {
                copy(
                    isLoadingCatalog = false,
                    loadingCatalogDirectoryKey = null,
                    loadedCatalogDirectoryKey = directory,
                    skills = sampleSkills,
                    agents = visibleAgents,
                ).recomputeRows()
            }
        }

        assertFalse(store.state.autocomplete.isLoadingCatalog)
        assertFalse(store.state.autocomplete.isLoadingCommands)
        store.finish()
    }

    @Test fun caretAwareTriggerDetectionAndAutoRunCancellationWorkCleanly() = runTest {
        val store = makeStore(
            initial = state { autocomplete { copy(loadedCatalogDirectoryKey = directory, skills = sampleSkills, loadedCommandsDirectoryKey = directory, commands = sampleCommands) } },
        ) {
            copy(findFiles = { _, _, _, _ -> emptyList() })
        }

        // 1. Caret in the middle of "hello @kit world" (at offset 10) detects "@kit" and replaces only [6, 10).
        store.send(Action.Draft("hello @kit world")) { it.copy(draft = "hello @kit world") }
        store.send(Action.Autocomplete(ComposerAutocompleteAction.CaretChanged(10))) {
            it.autocomplete {
                copy(
                    caretUTF16Offset = 10,
                    caretDraftUTF16Length = 16,
                    activeTrigger = AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "kit", key = "at:6", utf16Start = 6, utf16End = 10),
                    fileSearchGeneration = 1,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "kit",
                    sessionSearch = "kit",
                ).recomputeRows()
            }
        }
        val expectedSkillChip = chip(0, AutocompleteItem.skill(sampleSkills[0]))
        store.send(Action.Autocomplete(ComposerAutocompleteAction.ConfirmSelection)) {
            it.copy(draft = "hello @kit-motion  world").autocomplete {
                copy(
                    caretUTF16Offset = 18,
                    caretDraftUTF16Length = 24,
                    chips = listOf(expectedSkillChip),
                    chooseGeneration = 1,
                    lastChosenChip = expectedSkillChip,
                    lastChosenRowID = "skill:kit-motion",
                ).closePopover(dismissed = false)
            }
        }
        store.finish()
    }

    /** The `shouldChangeText` half (see the class note). */
    /** Hardware keys the bridge takes: ↑, ↓, Escape, Tab, Return pressed down. */
    private fun interceptedKeys(bridge: ComposerAutocompleteTextBridge): Int =
        listOf(
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_ESCAPE,
            android.view.KeyEvent.KEYCODE_TAB,
            android.view.KeyEvent.KEYCODE_ENTER,
        ).count { code ->
            bridge.handleKeyEvent(androidx.compose.ui.input.key.KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)))
        }

    @Test fun keyboardBridgeOnlyInterceptsReturnWhenSelectableAndSingleBackspaceInChip() {
        val bridge = ComposerAutocompleteTextBridge()
        bridge.isEnabled = true
        bridge.isOpen = true
        bridge.hasSelectableRow = false

        var confirmCount = 0
        val deletedChipIDs = mutableListOf<UUID>()
        bridge.onConfirmSelection = { confirmCount += 1 }
        bridge.onDeleteChip = { deletedChipIDs += it }

        // Showing only "No matches" (`hasSelectableRow == false`): Return and Tab are not intercepted.
        assertTrue(bridge.shouldChangeText("", start = 0, length = 0, replacement = "\n"))
        assertTrue(bridge.shouldChangeText("", start = 0, length = 0, replacement = "\t"))
        assertEquals(0, confirmCount)
        // Hardware ↑, ↓ and Escape only.
        assertEquals(3, interceptedKeys(bridge))

        // With a selectable row (`hasSelectableRow == true`): Return and Tab confirm selection.
        bridge.hasSelectableRow = true
        assertFalse(bridge.shouldChangeText("", start = 0, length = 0, replacement = "\n"))
        assertEquals(1, confirmCount)
        // Plus Tab and Return.
        confirmCount = 0
        assertEquals(5, interceptedKeys(bridge))
        assertEquals(2, confirmCount)

        // Single backspace inside a chip removes the chip; multi-character selection across a chip deletes normally.
        val targetChip = chip(901, AutocompleteItem.skill(sampleSkills[0]))
        val text = "Use @kit-motion now"
        bridge.chips = listOf(targetChip)

        // Multi-char range [0, 10) intersecting "@kit-motion" [4, 15) is allowed to delete normally.
        assertTrue(bridge.shouldChangeText(text, start = 0, length = 10, replacement = ""))
        assertTrue(deletedChipIDs.isEmpty())

        // Single-character backspace at the end of "@kit-motion" (index 14, length 1) deletes the chip.
        assertFalse(bridge.shouldChangeText(text, start = 14, length = 1, replacement = ""))
        assertEquals(listOf(targetChip.id), deletedChipIDs)
    }

    @Test fun simulationGrowsGlassSmoothlyCollapsesSlotsWithoutOverlapAndRespectsEdgeFades() {
        val simulation = AutocompleteSimulation(AutocompleteParameters())
        val items = listOf(
            AutocompleteItem.skill(sampleSkills[0]), AutocompleteItem.skill(sampleSkills[1]), AutocompleteItem.skill(sampleSkills[2]),
            AutocompleteItem.file(sampleFiles[0]), AutocompleteItem.file(sampleFiles[1]), AutocompleteItem.agent(sampleAgents[0]),
        )

        val width = 360.0
        syncMention(simulation, items = items, width = width)

        val dt = 1.0 / 120.0
        var previousLift = simulation.frame(composerWidth = width).glassLift
        assertEquals("Glass starts from rest with no initial jump", 0.0, previousLift, 0.0)
        var maxSyncJump = 0.0
        var maxPostPeakRise = 0.0
        var previousStep = 0.0
        var passedOpeningPeak = false
        for (stepIndex in 0 until 100) {
            simulation.step(dt = dt, composerWidth = width)
            val currentFrame = simulation.frame(composerWidth = width)
            val signedStep = currentFrame.glassLift - previousLift
            if (stepIndex < 24) {
                if (signedStep < previousStep - 0.05) {
                    passedOpeningPeak = true
                } else if (passedOpeningPeak && signedStep > previousStep) {
                    maxPostPeakRise = max(maxPostPeakRise, signedStep - previousStep)
                }
                previousStep = signedStep
            }
            previousLift = currentFrame.glassLift

            // Narrow the filter mid-flight and verify leaving rows collapse in their own slot without overlap or a glass jump.
            if (stepIndex == 24) {
                val liftBeforeSync = simulation.frame(composerWidth = width).glassLift
                syncMention(simulation, query = "kit", items = listOf(items[0], items[3]), width = width)
                val liftAfterSync = simulation.frame(composerWidth = width).glassLift
                maxSyncJump = max(maxSyncJump, abs(liftAfterSync - liftBeforeSync))
            }

            val sortedRows = currentFrame.rows.sortedBy { it.rect.minY }
            for (idx in 1 until sortedRows.size) {
                assertTrue(
                    "Rows never overlap vertically during slot collapse",
                    sortedRows[idx].rect.minY >= sortedRows[idx - 1].rect.maxY - 0.5,
                )
            }
        }

        assertTrue("Glass lift never jumps on state sync: $maxSyncJump", maxSyncJump < 1.5)
        assertTrue("Glass lift grows smoothly with no jump above 1.5 pt: $maxPostPeakRise", maxPostPeakRise < 1.5)
        val settledTwoRowFrame = simulation.frame(composerWidth = width)
        // 2 rows * 40 pt + 12 pt padding = 92 pt restingLift.
        assertTrue(abs(settledTwoRowFrame.glassLift - 92) < 1.5)
        assertEquals(0.0, settledTwoRowFrame.edgeFade.topHidden, 0.0)
        assertEquals(0.0, settledTwoRowFrame.edgeFade.bottomHidden, 0.0)
        assertEquals(16.0, settledTwoRowFrame.highlight?.cornerRadius)

        // With 6 rows past maxRows, overflow enables the top edge fade in bottom order.
        val overflowSim = AutocompleteSimulation(AutocompleteParameters())
        syncMention(overflowSim, items = items, width = width)
        repeat(90) { overflowSim.step(dt = dt, composerWidth = width) }
        val overflowFrame = overflowSim.frame(composerWidth = width)
        assertTrue("Top edge fade activates when rows extend past the top of the viewport", overflowFrame.edgeFade.topHidden > 0)
    }

    /** The reducer half (see the class note). */
    @Test fun midDraftChipInsertAndChipDeletePreserveCaretOffset() = runTest {
        val store = makeStore(
            initial = state { autocomplete { copy(loadedCatalogDirectoryKey = directory, skills = sampleSkills) } },
        ) {
            copy(findFiles = { _, _, _, _ -> emptyList() })
        }

        // 1. Type mid-draft at offset 10 ("hello @kit| world") and confirm "@kit-motion".
        store.send(Action.Draft("hello @kit world")) { it.copy(draft = "hello @kit world") }
        store.send(Action.Autocomplete(ComposerAutocompleteAction.CaretChanged(10))) {
            it.autocomplete {
                copy(
                    caretUTF16Offset = 10,
                    caretDraftUTF16Length = 16,
                    activeTrigger = AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "kit", key = "at:6", utf16Start = 6, utf16End = 10),
                    fileSearchGeneration = 1,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "kit",
                    sessionSearch = "kit",
                ).recomputeRows()
            }
        }

        val expectedSkillChip = chip(0, AutocompleteItem.skill(sampleSkills[0]))
        store.send(Action.Autocomplete(ComposerAutocompleteAction.ConfirmSelection)) {
            it.copy(draft = "hello @kit-motion  world").autocomplete {
                copy(
                    caretUTF16Offset = 18,
                    caretDraftUTF16Length = 24,
                    chips = listOf(expectedSkillChip),
                    chooseGeneration = 1,
                    lastChosenChip = expectedSkillChip,
                    lastChosenRowID = "skill:kit-motion",
                ).closePopover(dismissed = false)
            }
        }

        // 2. Delete the mid-draft chip and verify the caret lands at removeRange.location (offset 6).
        store.send(Action.Autocomplete(ComposerAutocompleteAction.ChipDeleted(expectedSkillChip.id))) {
            it.copy(draft = "hello  world").autocomplete {
                copy(caretUTF16Offset = 6, caretDraftUTF16Length = 12, chips = emptyList())
            }
        }
        store.finish()
    }

    @Test fun growAndCollapseAnimationSnapsBothEdgesKeepingAnchoredEdgesPixelStable() {
        val items = listOf(
            AutocompleteItem.skill(sampleSkills[0]), AutocompleteItem.skill(sampleSkills[1]),
            AutocompleteItem.skill(sampleSkills[2]), AutocompleteItem.file(sampleFiles[0]),
        )
        val dt = 1.0 / 120.0
        val width = 359.3333333

        for (scale in listOf(2.0, 3.0)) {
            for (composerHeight in listOf(52.0, 52.1666667, 52.5, 53.25)) {
                val simulation = AutocompleteSimulation(AutocompleteParameters())
                syncMention(simulation, items = items, width = width)

                val expectedGlassBottom = AutocompleteSimulation.snapEdge(composerHeight, scale)
                val expectedRow0Height = AutocompleteSimulation.snapEdge(simulation.params.rowHeight, scale)
                val eps = 1e-5

                for (stepIndex in 0 until 180) {
                    simulation.step(dt = dt, composerWidth = width)

                    // Narrow at frame 30 (while still mid-open) and widen again at frame 75, then close at frame 120.
                    when (stepIndex) {
                        30 -> syncMention(simulation, query = "kit", items = listOf(items[0]), width = width)
                        75 -> syncMention(simulation, items = listOf(items[0], items[1], items[2]), width = width)
                        120 -> simulation.sync(
                            isOpen = false, mode = null, query = "", key = null,
                            items = emptyList(), selectedRowID = null, userMoved = false, composerWidth = width,
                        )
                    }

                    val frame = simulation.frame(composerWidth = width, composerHeight = composerHeight, displayScale = scale)
                    val where = "scale $scale, composerHeight $composerHeight, step $stepIndex"

                    // 1. Composer glass: both top and height lie on the pixel grid, and the anchored
                    //    bottom edge never moves from `expectedGlassBottom`.
                    val snappedGlassTop = AutocompleteSimulation.snapEdge(frame.glassRect.minY, scale)
                    val snappedGlassHeight = AutocompleteSimulation.snapEdge(frame.glassRect.height, scale)
                    assertTrue(where, abs(frame.glassRect.minY - snappedGlassTop) < eps)
                    assertTrue(where, abs(frame.glassRect.height - snappedGlassHeight) < eps)
                    assertTrue(
                        "Glass anchored bottom edge must never flicker at $where",
                        abs((snappedGlassTop + snappedGlassHeight) - expectedGlassBottom) < eps,
                    )
                    assertTrue(where, abs(frame.glassRect.maxY - expectedGlassBottom) < eps)

                    // 2. List window: anchored at the composer bar's top (`y = 0`), growing upward to `-listHeight`.
                    val snappedListTop = AutocompleteSimulation.snapEdge(-frame.listHeight, scale)
                    val snappedListHeight = AutocompleteSimulation.snapEdge(frame.listHeight, scale)
                    assertTrue(where, abs(snappedListTop + snappedListHeight) < eps)

                    // 3. Suggestion rows: in bottom order, row 0 sits on the anchored bottom of the list
                    //    window, and each row above it has its bottom edge on the top edge of the row below.
                    if (frame.rows.isEmpty()) continue
                    for (row in frame.rows) {
                        val rTop = AutocompleteSimulation.snapEdge(row.rect.minY, scale)
                        val rHeight = AutocompleteSimulation.snapEdge(row.rect.height, scale)
                        val rBottom = AutocompleteSimulation.snapEdge(row.rect.maxY, scale)
                        assertTrue(where, abs(row.rect.minY - rTop) < eps)
                        assertTrue(where, abs(row.rect.height - rHeight) < eps)
                        assertTrue(where, abs((rTop + rHeight) - rBottom) < eps)
                    }

                    frame.rows.firstOrNull { it.id == items[0].id }?.let { row0 ->
                        val row0TopInComposer = AutocompleteSimulation.snapEdge(row0.rect.minY - frame.listHeight, scale)
                        val row0Height = AutocompleteSimulation.snapEdge(row0.rect.height, scale)
                        assertTrue(
                            "Bottom-anchored row 0 bottom edge in composer space must never flicker at $where",
                            abs(row0TopInComposer + row0Height) < eps,
                        )
                    }

                    frame.rows.firstOrNull { it.id == items[1].id && it.rect.height > 0 }?.let { row1 ->
                        val row1TopInComposer = AutocompleteSimulation.snapEdge(row1.rect.minY - frame.listHeight, scale)
                        val row1Height = AutocompleteSimulation.snapEdge(row1.rect.height, scale)
                        val expectedRow1BottomInComposer = -expectedRow0Height
                        assertTrue(
                            "Row 1 bottom edge anchored on top of settled row 0 must never flicker while its slot grows/collapses at $where",
                            abs((row1TopInComposer + row1Height) - expectedRow1BottomInComposer) < eps,
                        )
                    }

                    val sortedRows = frame.rows.filter { it.rect.height > 0 }.sortedBy { it.rect.minY }
                    for (idx in 1 until sortedRows.size) {
                        assertTrue(
                            "Adjacent rows share the exact pixel-snapped edge with no 1px gap or overlap at $where",
                            abs(sortedRows[idx].rect.minY - sortedRows[idx - 1].rect.maxY) < eps,
                        )
                    }
                }
            }
        }
    }
}
