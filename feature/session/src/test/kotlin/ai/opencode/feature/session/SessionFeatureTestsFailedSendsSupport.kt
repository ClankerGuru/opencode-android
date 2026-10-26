package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.server.model.OpenCodeTranscriptItem

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
}
