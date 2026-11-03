package ai.opencode.feature.session

import ai.opencode.feature.session.ComposerControls.DictationControls
import ai.opencode.feature.session.ComposerControls.MicPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * iOS `ComposerItemProviderLoaderTests.swift`, the cases that pin `ComposerControls` (it lives in
 * `ComposerViews.kt` here, so they run in `:feature:session`). The import cases are in
 * `:feature:composer`'s `ComposerItemProviderLoaderTests`.
 */
class ComposerItemProviderLoaderTests {
    private data class Case(
        val isDictating: Boolean,
        val isFinishing: Boolean,
        val isRunning: Boolean,
        val canSend: Boolean,
        val expanded: Boolean,
        val expected: ComposerControls,
    )

    private fun controls(trailing: ComposerTrailingControl, secondaryMic: MicPlacement, dictation: DictationControls? = null) =
        ComposerControls(trailing, secondaryMic, dictation)

    @Test
    fun composerOffersACallWhenVoiceIsSetUp() {
        // Voice set up: empty and idle shows the call with dictation beside it; text or a run replaces it.
        listOf(
            Case(false, false, false, false, false, controls(ComposerTrailingControl.Call, MicPlacement.trailing)),
            Case(false, false, false, false, true, controls(ComposerTrailingControl.Call, MicPlacement.leading)),
            Case(false, false, false, true, true, controls(ComposerTrailingControl.Send(enabled = true), MicPlacement.leading)),
            Case(false, false, true, false, false, controls(ComposerTrailingControl.Stop, MicPlacement.trailing)),
            Case(true, false, false, false, true, controls(ComposerTrailingControl.Mic, MicPlacement.none, DictationControls(isFinishing = false))),
        ).forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                ComposerControls.make(case.isDictating, case.isFinishing, case.isRunning, case.canSend, case.expanded, canCall = true),
            )
        }
    }

    @Test
    fun activeCallKeepsSendInsteadOfHiddenCall() {
        for (expanded in listOf(false, true)) {
            for (ready in listOf(false, true)) {
                val controls = ComposerControls.make(
                    isDictating = false, isFinishing = false, isRunning = false,
                    canSend = ready, expanded = expanded, canCall = true,
                    keepsSendVisible = true, isInCall = true,
                )
                assertEquals(ComposerTrailingControl.Send(enabled = ready), controls.trailing)
                assertEquals(if (expanded) MicPlacement.leading else MicPlacement.trailing, controls.secondaryMic)
            }
            assertEquals(
                ComposerTrailingControl.Stop,
                ComposerControls.make(
                    isDictating = false, isFinishing = false, isRunning = true,
                    canSend = false, expanded = expanded, canCall = true,
                    keepsSendVisible = true, isInCall = true,
                ).trailing,
            )
        }
    }

    @Test
    fun composerControlPresentation() {
        listOf(
            Case(false, false, false, false, false, controls(ComposerTrailingControl.Mic, MicPlacement.none)),
            Case(false, false, true, false, false, controls(ComposerTrailingControl.Stop, MicPlacement.trailing)),
            Case(false, false, false, true, true, controls(ComposerTrailingControl.Send(enabled = true), MicPlacement.leading)),
            Case(true, false, true, true, true, controls(ComposerTrailingControl.Send(enabled = true), MicPlacement.none, DictationControls(isFinishing = false))),
            Case(false, true, true, true, true, controls(ComposerTrailingControl.Send(enabled = true), MicPlacement.none, DictationControls(isFinishing = true))),
        ).forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                ComposerControls.make(case.isDictating, case.isFinishing, case.isRunning, case.canSend, case.expanded),
            )
        }
    }
}
