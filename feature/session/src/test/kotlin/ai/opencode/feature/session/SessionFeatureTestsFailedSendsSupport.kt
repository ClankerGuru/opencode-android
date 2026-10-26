package ai.opencode.feature.session

import ai.opencode.core.store.TestStore
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Helpers iOS keeps on `SessionFeatureTests` for its send tests: `sentID`
 * (`SessionFeatureTests+FailedSends.swift`) and `expectOptimisticSend`
 * (`SessionFeatureTests+Rendering.swift`), shared by the AgentSelection, Composer, Dictation,
 * FailedSends and Inbox ports.
 */
internal object FailedSendsSupport {
    /** iOS `SessionFeatureTests.sentID`: the first message ID `ServerIDClient.incrementing()` mints. */
    const val sentID = "msg_00000000000100000000000000"

    /** iOS `expectOptimisticSend(&state, text:)`: what a send does to the screen at once. */
    fun State.expectOptimisticSend(text: String = "hello"): State {
        val messageID = sentID
        val signal = workingRunSignal
        return copy(
            // A send supersedes any run-state work.
            runWorkGeneration = runWorkGeneration + 1,
            optimisticPromptIDs = setOf(messageID),
            nextPromptRequestEpoch = 1,
            promptStates = promptStates + (messageID to State.PromptState(requestEpoch = 1)),
            isAwaitingReply = true,
            items = listOf(OpenCodeTranscriptItem.UserText(id = messageID, text = text)),
            lastSentItemID = messageID,
            sendGeneration = 1,
        ).withDraft("")
            // The Working line sees the run start.
            .updateWorkingRun(signal)
    }

    /**
     * TCA's `finish()` on a non-exhaustive store: effects still in flight after the timeout are
     * not a failure (TCA reports them only with `exhaustivity = .on`). Android's `TestStore.finish`
     * fails either way, so non-exhaustive ports whose iOS test ends with a long-lived poll still
     * running finish through this.
     */
    suspend fun <S, A> TestStore<S, A>.finishLikeTCA(timeout: Duration = 1.seconds) {
        if (exhaustive) return finish(timeout)
        try {
            finish(timeout)
        } catch (error: AssertionError) {
            if (error.message?.contains("still running at finish") != true) throw error
        }
    }
}
